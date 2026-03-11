package io.github.sunix.jdtls.mcp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LSP client that communicates with the Eclipse JDT Language Server (jdtls) using LSP4J.
 *
 * <p>This client handles the LSP handshake (initialize/initialized), manages open files,
 * and provides methods for Java language intelligence operations such as hover, go-to-definition,
 * find-references, diagnostics, and code completions.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * JdtlsLanguageClient client = new JdtlsLanguageClient(inputStream, outputStream, workspacePath);
 * client.initialize();
 * String hoverInfo = client.hover("file:///path/to/MyClass.java", 10, 5);
 * client.shutdown();
 * }</pre>
 */
public class JdtlsLanguageClient implements LanguageClient {

    private static final Logger logger = LoggerFactory.getLogger(JdtlsLanguageClient.class);

    /** Timeout for LSP operations in seconds. */
    private static final long LSP_TIMEOUT_SECONDS = 30;

    private final Path workspacePath;
    private LanguageServer server;
    private Future<?> listenerFuture;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Tracks diagnostics received from jdtls, keyed by file URI. */
    private final Map<String, List<Diagnostic>> diagnosticsMap = new ConcurrentHashMap<>();

    /** Tracks files currently open in jdtls, mapped to their current version number. */
    private final Map<String, Integer> openFileVersions = new ConcurrentHashMap<>();

    /**
     * Creates a new JdtlsLanguageClient.
     *
     * @param inputStream   the output stream of the jdtls process (LSP messages from jdtls)
     * @param outputStream  the input stream of the jdtls process (LSP messages to jdtls)
     * @param workspacePath the workspace path to initialise jdtls with
     */
    public JdtlsLanguageClient(InputStream inputStream, OutputStream outputStream, Path workspacePath) {
        this.workspacePath = workspacePath;

        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                this, inputStream, outputStream);
        this.server = launcher.getRemoteProxy();
        this.listenerFuture = launcher.startListening();
    }

    /**
     * Initialises the language server for the configured workspace.
     * Must be called before any other language operations.
     *
     * @throws Exception if initialization fails or times out
     */
    public void initialize() throws Exception {
        logger.info("Initializing jdtls for workspace: {}", workspacePath);

        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setRootUri(workspacePath.toUri().toString());
        params.setRootPath(workspacePath.toAbsolutePath().toString());

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setApplyEdit(true);
        workspace.setWorkspaceEdit(new WorkspaceEditCapabilities());
        workspace.setDidChangeConfiguration(new DidChangeConfigurationCapabilities());
        workspace.setSymbol(new SymbolCapabilities());

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        textDocument.setSynchronization(new SynchronizationCapabilities(true, true, true));
        textDocument.setHover(new HoverCapabilities(
                List.of("markdown", "plaintext"), true));
        textDocument.setDefinition(new DefinitionCapabilities());
        textDocument.setReferences(new ReferencesCapabilities());
        textDocument.setDocumentSymbol(new DocumentSymbolCapabilities());
        textDocument.setCompletion(new CompletionCapabilities());
        textDocument.setDiagnostic(new DiagnosticCapabilities());
        textDocument.setCodeAction(new CodeActionCapabilities());

        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setWorkspace(workspace);
        capabilities.setTextDocument(textDocument);
        params.setCapabilities(capabilities);

        InitializeResult result = server.initialize(params).get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        logger.info("jdtls initialized. Server capabilities: {}", result.getCapabilities());

        server.initialized(new InitializedParams());
        initialized.set(true);
        logger.info("jdtls ready");
    }

    /**
     * Opens a file in jdtls so it can be analysed. If the file is already open, this is a no-op.
     *
     * @param uri     the file URI (e.g. {@code file:///path/to/MyClass.java})
     * @param content the full text content of the file
     */
    public void openFile(String uri, String content) {
        ensureInitialized();
        if (openFileVersions.containsKey(uri)) {
            logger.debug("File already open: {}", uri);
            return;
        }
        String languageId = detectLanguageId(uri);
        TextDocumentItem textDocumentItem = new TextDocumentItem(uri, languageId, 1, content);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDocumentItem));
        openFileVersions.put(uri, 1);
        logger.debug("Opened file: {}", uri);
    }

    /**
     * Notifies jdtls that a file has been updated with new content.
     *
     * @param uri     the file URI
     * @param content the updated file content
     */
    public void updateFile(String uri, String content) {
        ensureInitialized();
        if (!openFileVersions.containsKey(uri)) {
            openFile(uri, content);
            return;
        }
        int nextVersion = openFileVersions.merge(uri, 1, Integer::sum);
        VersionedTextDocumentIdentifier docId = new VersionedTextDocumentIdentifier(uri, nextVersion);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(content);
        server.getTextDocumentService().didChange(
                new DidChangeTextDocumentParams(docId, List.of(change)));
        logger.debug("Updated file {} to version {}", uri, nextVersion);
    }

    /**
     * Closes a file in jdtls.
     *
     * @param uri the file URI
     */
    public void closeFile(String uri) {
        if (!openFileVersions.containsKey(uri)) {
            return;
        }
        TextDocumentIdentifier docId = new TextDocumentIdentifier(uri);
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(docId));
        openFileVersions.remove(uri);
        logger.debug("Closed file: {}", uri);
    }

    /**
     * Requests hover information at a given position in a file.
     *
     * @param uri       the file URI
     * @param line      the 0-based line number
     * @param character the 0-based character offset
     * @return hover information as a string, or an empty string if none is available
     * @throws Exception if the operation fails or times out
     */
    public String hover(String uri, int line, int character) throws Exception {
        ensureInitialized();
        ensureFileIsOpen(uri);

        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character));

        Hover hover = server.getTextDocumentService()
                .hover(params)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (hover == null || hover.getContents() == null) {
            return "";
        }

        MarkupContent contents = hover.getContents().getRight();
        if (contents != null) {
            return contents.getValue();
        }
        // Fall back to MarkedString list
        List<Either<String, MarkedString>> markedStrings = hover.getContents().getLeft();
        if (markedStrings != null) {
            StringBuilder sb = new StringBuilder();
            for (Either<String, MarkedString> ms : markedStrings) {
                if (ms.isLeft()) {
                    sb.append(ms.getLeft());
                } else {
                    sb.append(ms.getRight().getValue());
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        }
        return "";
    }

    /**
     * Requests the definition location(s) for the symbol at a given position.
     *
     * @param uri       the file URI
     * @param line      the 0-based line number
     * @param character the 0-based character offset
     * @return a human-readable description of the definition location(s)
     * @throws Exception if the operation fails or times out
     */
    public String definition(String uri, int line, int character) throws Exception {
        ensureInitialized();
        ensureFileIsOpen(uri);

        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character));

        Either<List<? extends Location>, List<? extends LocationLink>> result =
                server.getTextDocumentService()
                        .definition(params)
                        .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null) {
            return "No definition found";
        }

        List<String> locations = new ArrayList<>();
        if (result.isLeft()) {
            for (Location loc : result.getLeft()) {
                locations.add(formatLocation(loc.getUri(), loc.getRange()));
            }
        } else {
            for (LocationLink link : result.getRight()) {
                locations.add(formatLocation(link.getTargetUri(), link.getTargetRange()));
            }
        }
        return locations.isEmpty() ? "No definition found" : String.join("\n", locations);
    }

    /**
     * Finds all references to the symbol at a given position.
     *
     * @param uri                the file URI
     * @param line               the 0-based line number
     * @param character          the 0-based character offset
     * @param includeDeclaration whether to include the declaration in the results
     * @return a human-readable list of reference locations
     * @throws Exception if the operation fails or times out
     */
    public String references(String uri, int line, int character, boolean includeDeclaration) throws Exception {
        ensureInitialized();
        ensureFileIsOpen(uri);

        ReferenceParams params = new ReferenceParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character),
                new ReferenceContext(includeDeclaration));

        List<? extends Location> locations = server.getTextDocumentService()
                .references(params)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (locations == null || locations.isEmpty()) {
            return "No references found";
        }

        List<String> results = new ArrayList<>();
        for (Location loc : locations) {
            results.add(formatLocation(loc.getUri(), loc.getRange()));
        }
        return String.join("\n", results);
    }

    /**
     * Returns the current diagnostics (errors and warnings) for a file.
     *
     * @param uri the file URI
     * @return a human-readable list of diagnostics
     */
    public String getDiagnostics(String uri) {
        ensureInitialized();
        List<Diagnostic> diags = diagnosticsMap.getOrDefault(uri, Collections.emptyList());
        if (diags.isEmpty()) {
            return "No diagnostics found for " + uri;
        }
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : diags) {
            sb.append(formatDiagnostic(d)).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Requests completion suggestions at a given position.
     *
     * @param uri       the file URI
     * @param line      the 0-based line number
     * @param character the 0-based character offset
     * @return a human-readable list of completion items
     * @throws Exception if the operation fails or times out
     */
    public String completion(String uri, int line, int character) throws Exception {
        ensureInitialized();
        ensureFileIsOpen(uri);

        CompletionParams params = new CompletionParams(
                new TextDocumentIdentifier(uri),
                new Position(line, character));

        Either<List<CompletionItem>, CompletionList> result = server.getTextDocumentService()
                .completion(params)
                .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null) {
            return "No completions found";
        }

        List<CompletionItem> items;
        if (result.isLeft()) {
            items = result.getLeft();
        } else {
            items = result.getRight().getItems();
        }

        if (items == null || items.isEmpty()) {
            return "No completions found";
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(items.size(), 20);
        for (int i = 0; i < limit; i++) {
            CompletionItem item = items.get(i);
            sb.append("- ").append(item.getLabel());
            if (item.getDetail() != null) {
                sb.append(" : ").append(item.getDetail());
            }
            sb.append('\n');
        }
        if (items.size() > 20) {
            sb.append("... and ").append(items.size() - 20).append(" more\n");
        }
        return sb.toString().trim();
    }

    /**
     * Requests the list of symbols defined in a document.
     *
     * @param uri the file URI
     * @return a human-readable list of document symbols
     * @throws Exception if the operation fails or times out
     */
    public String documentSymbols(String uri) throws Exception {
        ensureInitialized();
        ensureFileIsOpen(uri);

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));

        List<Either<SymbolInformation, DocumentSymbol>> result =
                server.getTextDocumentService()
                        .documentSymbol(params)
                        .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null || result.isEmpty()) {
            return "No symbols found in " + uri;
        }

        StringBuilder sb = new StringBuilder();
        for (Either<SymbolInformation, DocumentSymbol> symbol : result) {
            if (symbol.isLeft()) {
                SymbolInformation si = symbol.getLeft();
                sb.append("- ").append(si.getKind()).append(' ').append(si.getName()).append('\n');
            } else {
                appendDocumentSymbol(sb, symbol.getRight(), 0);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Searches for symbols across the workspace.
     *
     * @param query the search query
     * @return a human-readable list of matching symbols
     * @throws Exception if the operation fails or times out
     */
    public String workspaceSymbols(String query) throws Exception {
        ensureInitialized();

        Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                server.getWorkspaceService()
                        .symbol(new WorkspaceSymbolParams(query))
                        .get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null) {
            return "No symbols found for query: " + query;
        }

        List<String> symbols = new ArrayList<>();
        if (result.isLeft()) {
            for (SymbolInformation si : result.getLeft()) {
                symbols.add(si.getKind() + " " + si.getName() + " in " + si.getLocation().getUri());
            }
        } else {
            for (WorkspaceSymbol ws : result.getRight()) {
                String location = ws.getLocation().isLeft()
                        ? ws.getLocation().getLeft().getUri()
                        : ws.getLocation().getRight().getUri();
                symbols.add(ws.getKind() + " " + ws.getName() + " in " + location);
            }
        }

        if (symbols.isEmpty()) {
            return "No symbols found for query: " + query;
        }
        return String.join("\n", symbols);
    }

    /**
     * Shuts down the language server gracefully.
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }
        try {
            server.shutdown().get(LSP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server.exit();
        } catch (Exception e) {
            logger.warn("Error during jdtls shutdown", e);
        }
        if (listenerFuture != null) {
            listenerFuture.cancel(true);
        }
        initialized.set(false);
    }

    // ---- LanguageClient callbacks ----

    @Override
    public void telemetryEvent(Object object) {
        logger.debug("Telemetry: {}", object);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        logger.debug("Diagnostics for {}: {} items", diagnostics.getUri(), diagnostics.getDiagnostics().size());
        diagnosticsMap.put(diagnostics.getUri(), new ArrayList<>(diagnostics.getDiagnostics()));
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        logger.info("jdtls [{}]: {}", messageParams.getType(), messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        logger.info("jdtls showMessageRequest [{}]: {}", requestParams.getType(), requestParams.getMessage());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        logger.debug("jdtls log [{}]: {}", message.getType(), message.getMessage());
    }

    // ---- Helpers ----

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException(
                    "jdtls is not initialized. Call initialize() first.");
        }
    }

    private void ensureFileIsOpen(String uri) {
        if (!openFileVersions.containsKey(uri)) {
            // Try to read the file from disk if it's a file URI
            try {
                URI fileUri = URI.create(uri);
                if ("file".equals(fileUri.getScheme())) {
                    Path filePath = Path.of(fileUri);
                    String content = java.nio.file.Files.readString(filePath);
                    openFile(uri, content);
                    return;
                }
            } catch (Exception e) {
                logger.warn("Could not auto-open file {}: {}", uri, e.getMessage());
            }
            throw new IllegalStateException(
                    "File not open: " + uri + ". Call openFile() first or provide a valid file:// URI.");
        }
    }

    private String detectLanguageId(String uri) {
        if (uri.endsWith(".java")) return "java";
        if (uri.endsWith(".class")) return "java";
        if (uri.endsWith(".xml")) return "xml";
        if (uri.endsWith(".gradle")) return "groovy";
        return "plaintext";
    }

    private String formatLocation(String uri, Range range) {
        return uri + ":" + (range.getStart().getLine() + 1) + ":" + (range.getStart().getCharacter() + 1);
    }

    private String formatDiagnostic(Diagnostic d) {
        String severity = d.getSeverity() != null ? d.getSeverity().toString() : "?";
        Range range = d.getRange();
        String pos = range != null
                ? (range.getStart().getLine() + 1) + ":" + (range.getStart().getCharacter() + 1)
                : "?:?";
        String source = d.getSource() != null ? "[" + d.getSource() + "] " : "";
        return severity + " at line " + pos + ": " + source + d.getMessage();
    }

    private void appendDocumentSymbol(StringBuilder sb, DocumentSymbol symbol, int indent) {
        sb.append("  ".repeat(indent))
          .append("- ")
          .append(symbol.getKind())
          .append(' ')
          .append(symbol.getName())
          .append('\n');
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                appendDocumentSymbol(sb, child, indent + 1);
            }
        }
    }
}
