package io.github.sunix.jdtls.mcp;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Java language intelligence tools exposed as MCP tools.
 *
 * <p>Each method in this class is annotated with langchain4j {@link Tool} and {@link P}
 * annotations, making it suitable for direct use with LLM function-calling through
 * langchain4j, as well as for registration as MCP tools via the Model Context Protocol SDK.</p>
 *
 * <p>Internally, the tools delegate to a {@link JdtlsLanguageClient} that communicates with
 * the Eclipse JDT Language Server (jdtls) using the Language Server Protocol (LSP).</p>
 */
public class JavaLanguageServerTools {

    private static final Logger logger = LoggerFactory.getLogger(JavaLanguageServerTools.class);

    private final JdtlsLanguageClient lspClient;

    public JavaLanguageServerTools(JdtlsLanguageClient lspClient) {
        this.lspClient = lspClient;
    }

    // ---- Tool implementations ----

    /**
     * Opens a Java source file in the language server for analysis.
     * This must be called before using hover, definition, references, or completion on a file.
     */
    @Tool("Open a Java source file in the language server for analysis. " +
          "Must be called before requesting hover, definition, references, or completions on the file.")
    public String openFile(
            @P("Absolute file URI of the Java source file, e.g. file:///path/to/MyClass.java") String uri,
            @P("Full text content of the file") String content) {
        try {
            lspClient.openFile(uri, content);
            return "Opened file: " + uri;
        } catch (Exception e) {
            logger.error("Error opening file {}", uri, e);
            return "Error opening file: " + e.getMessage();
        }
    }

    /**
     * Updates the content of an already-open file.
     */
    @Tool("Update the content of an already-open Java source file in the language server.")
    public String updateFile(
            @P("Absolute file URI of the Java source file, e.g. file:///path/to/MyClass.java") String uri,
            @P("Updated full text content of the file") String content) {
        try {
            lspClient.updateFile(uri, content);
            return "Updated file: " + uri;
        } catch (Exception e) {
            logger.error("Error updating file {}", uri, e);
            return "Error updating file: " + e.getMessage();
        }
    }

    /**
     * Gets hover information (documentation, type info) at a position in a Java file.
     */
    @Tool("Get hover information (documentation, type info, Javadoc) for a Java symbol " +
          "at a given position in a source file.")
    public String hover(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri,
            @P("0-based line number") int line,
            @P("0-based character offset") int character) {
        try {
            String result = lspClient.hover(uri, line, character);
            return result.isEmpty() ? "No hover information available at " + uri + ":" + line + ":" + character : result;
        } catch (Exception e) {
            logger.error("Error getting hover info at {}:{}:{}", uri, line, character, e);
            return "Error getting hover information: " + e.getMessage();
        }
    }

    /**
     * Goes to the definition of a Java symbol at a given position.
     */
    @Tool("Find the definition location of a Java symbol (class, method, field, variable) " +
          "at a given position in a source file.")
    public String definition(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri,
            @P("0-based line number") int line,
            @P("0-based character offset") int character) {
        try {
            return lspClient.definition(uri, line, character);
        } catch (Exception e) {
            logger.error("Error getting definition at {}:{}:{}", uri, line, character, e);
            return "Error getting definition: " + e.getMessage();
        }
    }

    /**
     * Finds all references to the Java symbol at a given position.
     */
    @Tool("Find all references to the Java symbol (class, method, field) " +
          "at a given position in a source file.")
    public String references(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri,
            @P("0-based line number") int line,
            @P("0-based character offset") int character,
            @P("Whether to include the declaration itself in the results") boolean includeDeclaration) {
        try {
            return lspClient.references(uri, line, character, includeDeclaration);
        } catch (Exception e) {
            logger.error("Error finding references at {}:{}:{}", uri, line, character, e);
            return "Error finding references: " + e.getMessage();
        }
    }

    /**
     * Gets current diagnostics (errors, warnings) for a Java file.
     */
    @Tool("Get current diagnostics (compiler errors, warnings, code issues) for a Java source file.")
    public String diagnostics(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri) {
        try {
            return lspClient.getDiagnostics(uri);
        } catch (Exception e) {
            logger.error("Error getting diagnostics for {}", uri, e);
            return "Error getting diagnostics: " + e.getMessage();
        }
    }

    /**
     * Gets code completion suggestions at a given position in a Java file.
     */
    @Tool("Get code completion suggestions at a given position in a Java source file.")
    public String completion(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri,
            @P("0-based line number") int line,
            @P("0-based character offset") int character) {
        try {
            return lspClient.completion(uri, line, character);
        } catch (Exception e) {
            logger.error("Error getting completions at {}:{}:{}", uri, line, character, e);
            return "Error getting completions: " + e.getMessage();
        }
    }

    /**
     * Lists all symbols defined in a Java source file.
     */
    @Tool("List all symbols (classes, methods, fields, etc.) defined in a Java source file.")
    public String documentSymbols(
            @P("File URI, e.g. file:///path/to/MyClass.java") String uri) {
        try {
            return lspClient.documentSymbols(uri);
        } catch (Exception e) {
            logger.error("Error getting document symbols for {}", uri, e);
            return "Error getting document symbols: " + e.getMessage();
        }
    }

    /**
     * Searches for Java symbols across the workspace.
     */
    @Tool("Search for Java symbols (classes, methods, fields) by name across the entire workspace.")
    public String workspaceSymbols(
            @P("Search query string, e.g. a class name or method name prefix") String query) {
        try {
            return lspClient.workspaceSymbols(query);
        } catch (Exception e) {
            logger.error("Error searching workspace symbols for '{}'", query, e);
            return "Error searching workspace symbols: " + e.getMessage();
        }
    }

    // ---- MCP server registration ----

    /**
     * Registers all Java language tools with the given MCP server builder.
     *
     * @param serverBuilder the MCP server builder to register tools with
     */
    public void registerTools(McpServer.SyncSpecification<?> serverBuilder) {
        serverBuilder
                .toolCall(buildTool("java_open_file",
                        "Open a Java source file in the language server for analysis. " +
                        "Must be called before requesting hover, definition, references, or completions on the file.",
                        Map.of(
                                "uri", prop("string", "Absolute file URI, e.g. file:///path/to/MyClass.java"),
                                "content", prop("string", "Full text content of the file")),
                        List.of("uri", "content")),
                        this::mcpOpenFile)

                .toolCall(buildTool("java_update_file",
                        "Update the content of an already-open Java source file in the language server.",
                        Map.of(
                                "uri", prop("string", "Absolute file URI, e.g. file:///path/to/MyClass.java"),
                                "content", prop("string", "Updated full text content of the file")),
                        List.of("uri", "content")),
                        this::mcpUpdateFile)

                .toolCall(buildTool("java_hover",
                        "Get hover information (documentation, type info, Javadoc) for a Java symbol " +
                        "at a given position in a source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java"),
                                "line", prop("integer", "0-based line number"),
                                "character", prop("integer", "0-based character offset")),
                        List.of("uri", "line", "character")),
                        this::mcpHover)

                .toolCall(buildTool("java_definition",
                        "Find the definition location of a Java symbol " +
                        "(class, method, field, variable) at a given position in a source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java"),
                                "line", prop("integer", "0-based line number"),
                                "character", prop("integer", "0-based character offset")),
                        List.of("uri", "line", "character")),
                        this::mcpDefinition)

                .toolCall(buildTool("java_references",
                        "Find all references to the Java symbol (class, method, field) " +
                        "at a given position in a source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java"),
                                "line", prop("integer", "0-based line number"),
                                "character", prop("integer", "0-based character offset"),
                                "includeDeclaration", prop("boolean", "Whether to include the declaration itself")),
                        List.of("uri", "line", "character", "includeDeclaration")),
                        this::mcpReferences)

                .toolCall(buildTool("java_diagnostics",
                        "Get current diagnostics (compiler errors, warnings, code issues) " +
                        "for a Java source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java")),
                        List.of("uri")),
                        this::mcpDiagnostics)

                .toolCall(buildTool("java_completion",
                        "Get code completion suggestions at a given position in a Java source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java"),
                                "line", prop("integer", "0-based line number"),
                                "character", prop("integer", "0-based character offset")),
                        List.of("uri", "line", "character")),
                        this::mcpCompletion)

                .toolCall(buildTool("java_document_symbols",
                        "List all symbols (classes, methods, fields, etc.) defined in a Java source file.",
                        Map.of(
                                "uri", prop("string", "File URI, e.g. file:///path/to/MyClass.java")),
                        List.of("uri")),
                        this::mcpDocumentSymbols)

                .toolCall(buildTool("java_workspace_symbols",
                        "Search for Java symbols (classes, methods, fields) by name " +
                        "across the entire workspace.",
                        Map.of(
                                "query", prop("string", "Search query string, e.g. a class name or method name prefix")),
                        List.of("query")),
                        this::mcpWorkspaceSymbols);
    }

    // ---- MCP handler methods ----

    private McpSchema.CallToolResult mcpOpenFile(McpSyncServerExchange exchange,
                                                  McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        String content = getString(args, "content");
        String result = openFile(uri, content);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpUpdateFile(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        String content = getString(args, "content");
        String result = updateFile(uri, content);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpHover(McpSyncServerExchange exchange,
                                               McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        int line = getInt(args, "line");
        int character = getInt(args, "character");
        String result = hover(uri, line, character);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpDefinition(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        int line = getInt(args, "line");
        int character = getInt(args, "character");
        String result = definition(uri, line, character);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpReferences(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        int line = getInt(args, "line");
        int character = getInt(args, "character");
        boolean includeDeclaration = getBoolean(args, "includeDeclaration");
        String result = references(uri, line, character, includeDeclaration);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpDiagnostics(McpSyncServerExchange exchange,
                                                     McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        String result = diagnostics(uri);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpCompletion(McpSyncServerExchange exchange,
                                                    McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        int line = getInt(args, "line");
        int character = getInt(args, "character");
        String result = completion(uri, line, character);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpDocumentSymbols(McpSyncServerExchange exchange,
                                                         McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String uri = getString(args, "uri");
        String result = documentSymbols(uri);
        return textResult(result);
    }

    private McpSchema.CallToolResult mcpWorkspaceSymbols(McpSyncServerExchange exchange,
                                                          McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String query = getString(args, "query");
        String result = workspaceSymbols(query);
        return textResult(result);
    }

    // ---- Schema helpers ----

    private static McpSchema.Tool buildTool(String name, String description,
                                             Map<String, Object> properties,
                                             List<String> required) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", properties, required, null, null, null);
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(false)
                .build();
    }

    // ---- Argument extraction helpers ----

    private static String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return val.toString();
    }

    private static int getInt(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(val.toString());
    }

    private static boolean getBoolean(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) {
            return false;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(val.toString());
    }
}
