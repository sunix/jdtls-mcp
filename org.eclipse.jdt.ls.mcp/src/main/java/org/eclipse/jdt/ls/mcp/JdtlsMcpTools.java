package org.eclipse.jdt.ls.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.ls.core.internal.handlers.CompletionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.DocumentSymbolHandler;
import org.eclipse.jdt.ls.core.internal.handlers.HoverHandler;
import org.eclipse.jdt.ls.core.internal.handlers.NavigateToDefinitionHandler;
import org.eclipse.jdt.ls.core.internal.handlers.ReferencesHandler;
import org.eclipse.jdt.ls.core.internal.handlers.WorkspaceSymbolHandler;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceSymbolParams;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Java language intelligence tools implemented by delegating directly to the
 * jdtls handler classes that run in the same OSGi container.
 *
 * <p>Each method is annotated with langchain4j {@link Tool} and {@link P}
 * annotations so they can also be consumed directly by langchain4j-based agents
 * without going through the MCP transport layer.</p>
 *
 * <p>Use {@link #registerTools(McpServer.SyncSpecification)} to register all
 * tools with the MCP server.</p>
 */
public class JdtlsMcpTools {

	private final PreferenceManager preferencesManager;

	@SuppressWarnings("unused")
	private final ProjectsManager projectsManager;

	public JdtlsMcpTools(PreferenceManager preferencesManager,
			ProjectsManager projectsManager) {
		this.preferencesManager = preferencesManager;
		this.projectsManager = projectsManager;
	}

	// ---- Tool implementations (also usable as langchain4j @Tool methods) ----

	/**
	 * Returns hover information (Javadoc, type signature) for the symbol at the
	 * given position in a Java source file.
	 */
	@Tool("Get hover information (Javadoc, type info) for the Java symbol at a given position.")
	public String hover(
			@P("Absolute file URI, e.g. file:///path/to/MyClass.java") String uri,
			@P("0-based line number") int line,
			@P("0-based character offset") int character) {

		HoverHandler handler = new HoverHandler(preferencesManager);
		HoverParams params = new HoverParams(
				new TextDocumentIdentifier(uri),
				new Position(line, character));

		var result = handler.hover(params, new NullProgressMonitor());
		if (result == null || result.getContents() == null) {
			return "No hover information available.";
		}
		return String.valueOf(result.getContents());
	}

	/**
	 * Returns the definition location(s) of the Java symbol at the given position.
	 */
	@Tool("Find the definition of the Java symbol (class, method, field) at the given position.")
	public String definition(
			@P("Absolute file URI, e.g. file:///path/to/MyClass.java") String uri,
			@P("0-based line number") int line,
			@P("0-based character offset") int character) {

		NavigateToDefinitionHandler handler =
				new NavigateToDefinitionHandler(preferencesManager);
		DefinitionParams params = new DefinitionParams(
				new TextDocumentIdentifier(uri),
				new Position(line, character));

		List<? extends Location> result = handler.definition(params, new NullProgressMonitor());

		if (result == null) {
			return "No definition found.";
		}
		List<String> locations = new java.util.ArrayList<>();
		for (Location loc : result) {
			locations.add(formatLocation(loc.getUri(), loc.getRange().getStart()));
		}
		return locations.isEmpty() ? "No definition found." : String.join("\n", locations);
	}

	/**
	 * Returns all references to the Java symbol at the given position.
	 */
	@Tool("Find all references to the Java symbol at the given position.")
	public String references(
			@P("Absolute file URI, e.g. file:///path/to/MyClass.java") String uri,
			@P("0-based line number") int line,
			@P("0-based character offset") int character,
			@P("Include the declaration itself in results") boolean includeDeclaration) {

		ReferencesHandler handler = new ReferencesHandler(preferencesManager);
		ReferenceParams params = new ReferenceParams(
				new TextDocumentIdentifier(uri),
				new Position(line, character),
				new ReferenceContext(includeDeclaration));

		List<? extends Location> result =
				handler.findReferences(params, new NullProgressMonitor());

		if (result == null || result.isEmpty()) {
			return "No references found.";
		}
		List<String> lines = new java.util.ArrayList<>();
		for (Location loc : result) {
			lines.add(formatLocation(loc.getUri(), loc.getRange().getStart()));
		}
		return String.join("\n", lines);
	}

	/**
	 * Returns code completion suggestions at the given position.
	 */
	@Tool("Get code completion suggestions at the given position in a Java source file.")
	public String completion(
			@P("Absolute file URI, e.g. file:///path/to/MyClass.java") String uri,
			@P("0-based line number") int line,
			@P("0-based character offset") int character) {

		CompletionHandler handler = new CompletionHandler(preferencesManager);
		CompletionParams params = new CompletionParams(
				new TextDocumentIdentifier(uri),
				new Position(line, character));

		Object result = handler.completion(params, new NullProgressMonitor());

		return result == null ? "No completions found." : String.valueOf(result);
	}

	/**
	 * Returns the symbols (classes, methods, fields) defined in the given document.
	 */
	@Tool("List all symbols (classes, methods, fields) defined in a Java source file.")
	public String documentSymbols(
			@P("Absolute file URI, e.g. file:///path/to/MyClass.java") String uri) {

		DocumentSymbolHandler handler =
				new DocumentSymbolHandler(preferencesManager);
		DocumentSymbolParams params = new DocumentSymbolParams(
				new TextDocumentIdentifier(uri));

		var result = handler.documentSymbol(params, new NullProgressMonitor());

		if (result == null || result.isEmpty()) {
			return "No symbols found.";
		}
		return String.valueOf(result);
	}

	/**
	 * Searches for Java symbols matching the given query across the workspace.
	 */
	@Tool("Search for Java symbols (classes, methods, fields) by name across the workspace.")
	public String workspaceSymbols(
			@P("Search query, e.g. a class name or method name prefix") String query) {

		List<SymbolInformation> result = WorkspaceSymbolHandler.search(query, new NullProgressMonitor());

		if (result == null) {
			return "No symbols found for: " + query;
		}
		List<String> lines = new java.util.ArrayList<>();
		for (SymbolInformation si : result) {
			lines.add("[" + si.getKind() + "] " + si.getName()
					+ " — " + si.getLocation().getUri());
		}
		return lines.isEmpty() ? "No symbols found for: " + query : String.join("\n", lines);
	}

	/**
	 * Returns compilation errors and warnings for a Java source file, or for
	 * the entire workspace when no URI is supplied.
	 */
	@Tool("Get compilation errors and warnings for a Java source file or the entire workspace.")
	public String diagnostics(
			@P("Absolute file URI (e.g. file:///path/to/MyClass.java). Omit or leave empty to get diagnostics for the entire workspace.") String uri) {

		try {
			IMarker[] markers;
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

			if (uri != null && !uri.isBlank()) {
				IFile[] files = root.findFilesForLocationURI(new java.net.URI(uri));
				if (files.length == 0) {
					return "File not found in workspace: " + uri;
				}
				markers = files[0].findMarkers(
						"org.eclipse.jdt.core.problem", true, IResource.DEPTH_ZERO);
			} else {
				markers = root.findMarkers(
						"org.eclipse.jdt.core.problem", true, IResource.DEPTH_INFINITE);
			}

			if (markers == null || markers.length == 0) {
				return uri != null && !uri.isBlank()
						? "No diagnostics found for: " + uri
						: "No diagnostics found in workspace.";
			}

			StringBuilder sb = new StringBuilder();
			for (IMarker marker : markers) {
				int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
				String severityStr = severity == IMarker.SEVERITY_ERROR ? "ERROR"
						: severity == IMarker.SEVERITY_WARNING ? "WARNING" : "INFO";
				String message = marker.getAttribute(IMarker.MESSAGE, "");
				int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
				java.net.URI resUri = marker.getResource().getLocationURI();
				String location = resUri != null ? resUri.toString()
						: marker.getResource().getFullPath().toString();
				sb.append(severityStr).append(' ')
				  .append(location).append(':').append(line)
				  .append(' ').append(message).append('\n');
			}
			return sb.toString().trim();
		} catch (Exception e) {
			return "Error getting diagnostics: " + e.getMessage();
		}
	}

	/**
	 * Returns the current state of the jdtls workspace: whether background import
	 * or indexing jobs are still running, and whether any projects have classpath
	 * or Maven dependency problems.
	 *
	 * <p>Possible {@code status} values:</p>
	 * <ul>
	 *   <li>{@code READY}     — workspace is fully initialized; no classpath issues detected</li>
	 *   <li>{@code WARNING}   — workspace is ready but one or more projects have classpath or
	 *                           Maven dependency problems (e.g. unresolved dependencies)</li>
	 *   <li>{@code IMPORTING} — Maven / Gradle import job is still running</li>
	 *   <li>{@code BUILDING}  — workspace build job is still running</li>
	 *   <li>{@code INDEXING}  — JDT search-index job is still running</li>
	 *   <li>{@code ERROR}     — unexpected error while querying workspace state</li>
	 * </ul>
	 */
	@Tool("Returns the current state of the jdtls workspace (project import progress, "
			+ "build status, index readiness, classpath / Maven dependency errors). "
			+ "Call this before running other tools to verify the workspace is ready, "
			+ "or to diagnose dependency-resolution failures.")
	public String workspaceStatus() {
		try {
			// 1. Detect any still-running background jobs → IMPORTING / BUILDING / INDEXING
			IJobManager jobManager = Job.getJobManager();
			for (Job job : jobManager.find(null)) {
				int state = job.getState();
				if (state != Job.RUNNING && state != Job.WAITING) {
					continue;
				}
				String name = job.getName();
				if (name == null) {
					continue;
				}
				String lower = name.toLowerCase();
				if (lower.contains("maven") || lower.contains("m2e")
						|| lower.contains("classpath") || lower.contains("import")) {
					return "status: IMPORTING\nmessage: " + name;
				}
				if (lower.contains("index") || lower.contains("indexing")) {
					return "status: INDEXING\nmessage: " + name;
				}
				// Exclude Equinox framework internal "build" jobs (e.g. "Equinox Coordinator")
				// which are unrelated to project compilation and always running.
				if (lower.contains("build") && !lower.contains("equinox")) {
					return "status: BUILDING\nmessage: " + name;
				}
			}

			// 2. Check workspace markers for classpath / Maven problems → WARNING
			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

			List<String> issues = new ArrayList<>();
			boolean hasIssue = false;

			// JDT build-path problem markers (unresolved classpath entries, etc.)
			IMarker[] classpathMarkers = root.findMarkers(
					"org.eclipse.jdt.core.buildpath_problem", true, IResource.DEPTH_INFINITE);
			for (IMarker m : classpathMarkers) {
				int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
				if (severity >= IMarker.SEVERITY_WARNING) {
					hasIssue = true;
				}
				String sev = severity == IMarker.SEVERITY_ERROR ? "ERROR" : "WARNING";
				issues.add("  [" + sev + "] " + m.getResource().getFullPath()
						+ ": " + m.getAttribute(IMarker.MESSAGE, ""));
			}

			// m2e Maven project markers (missing dependencies, POM errors, etc.)
			IMarker[] m2eMarkers = root.findMarkers(
					"org.eclipse.m2e.core.maven2Problem", true, IResource.DEPTH_INFINITE);
			for (IMarker m : m2eMarkers) {
				int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
				if (severity >= IMarker.SEVERITY_WARNING) {
					hasIssue = true;
				}
				String sev = severity == IMarker.SEVERITY_ERROR ? "ERROR" : "WARNING";
				issues.add("  [" + sev + "] (Maven) " + m.getResource().getFullPath()
						+ ": " + m.getAttribute(IMarker.MESSAGE, ""));
			}

			// 3. Count compilation errors / warnings
			IMarker[] problemMarkers = root.findMarkers(
					"org.eclipse.jdt.core.problem", true, IResource.DEPTH_INFINITE);
			int errorCount = 0;
			int warningCount = 0;
			for (IMarker m : problemMarkers) {
				int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
				if (severity == IMarker.SEVERITY_ERROR) {
					errorCount++;
				} else if (severity == IMarker.SEVERITY_WARNING) {
					warningCount++;
				}
			}

			// 4. Build response
			String status = hasIssue ? "WARNING" : "READY";
			StringBuilder sb = new StringBuilder();
			sb.append("status: ").append(status).append('\n');

			IProject[] projects = root.getProjects();
			sb.append("projects: ").append(projects.length).append('\n');
			for (IProject project : projects) {
				sb.append("  - ").append(project.getName());
				if (!project.isOpen()) {
					sb.append(" (closed)");
				}
				sb.append('\n');
			}

			if (!issues.isEmpty()) {
				sb.append("classpath_issues:\n");
				for (String issue : issues) {
					sb.append(issue).append('\n');
				}
			}

			sb.append("compilation_errors: ").append(errorCount).append('\n');
			sb.append("compilation_warnings: ").append(warningCount);
			return sb.toString();

		} catch (Exception e) {
			return "status: ERROR\nmessage: " + e.getMessage();
		}
	}

	// ---- MCP server registration ----

	/**
	 * Registers all Java language tools with the given MCP server specification.
	 *
	 * @param spec the MCP sync server specification to register tools with
	 */
	public void registerTools(McpServer.SyncSpecification<?> spec) {
		spec
			.toolCall(tool("java_hover",
					"Get hover information (Javadoc, type info) for the Java symbol at a given position.",
					Map.of(
						"uri",       prop("string",  "Absolute file URI, e.g. file:///path/to/MyClass.java"),
						"line",      prop("integer", "0-based line number"),
						"character", prop("integer", "0-based character offset")),
					List.of("uri", "line", "character")),
				this::mcpHover)

			.toolCall(tool("java_definition",
					"Find the definition of the Java symbol (class, method, field) at the given position.",
					Map.of(
						"uri",       prop("string",  "Absolute file URI, e.g. file:///path/to/MyClass.java"),
						"line",      prop("integer", "0-based line number"),
						"character", prop("integer", "0-based character offset")),
					List.of("uri", "line", "character")),
				this::mcpDefinition)

			.toolCall(tool("java_references",
					"Find all references to the Java symbol at the given position.",
					Map.of(
						"uri",                prop("string",  "Absolute file URI, e.g. file:///path/to/MyClass.java"),
						"line",               prop("integer", "0-based line number"),
						"character",          prop("integer", "0-based character offset"),
						"includeDeclaration", prop("boolean", "Whether to include the declaration itself")),
					List.of("uri", "line", "character", "includeDeclaration")),
				this::mcpReferences)

			.toolCall(tool("java_completion",
					"Get code completion suggestions at the given position in a Java source file.",
					Map.of(
						"uri",       prop("string",  "Absolute file URI, e.g. file:///path/to/MyClass.java"),
						"line",      prop("integer", "0-based line number"),
						"character", prop("integer", "0-based character offset")),
					List.of("uri", "line", "character")),
				this::mcpCompletion)

			.toolCall(tool("java_document_symbols",
					"List all symbols (classes, methods, fields) defined in a Java source file.",
					Map.of(
						"uri", prop("string", "Absolute file URI, e.g. file:///path/to/MyClass.java")),
					List.of("uri")),
				this::mcpDocumentSymbols)

			.toolCall(tool("java_workspace_symbols",
					"Search for Java symbols (classes, methods, fields) by name across the workspace.",
					Map.of(
						"query", prop("string", "Search query, e.g. a class name or method name prefix")),
					List.of("query")),
				this::mcpWorkspaceSymbols)

			.toolCall(tool("java_diagnostics",
					"Get compilation errors and warnings for a Java source file or the entire workspace.",
					Map.of(
						"uri", prop("string",
							"Absolute file URI (e.g. file:///path/to/MyClass.java). Omit for workspace-wide diagnostics.")),
					List.of()),
				this::mcpDiagnostics)

			.toolCall(tool("java_workspace_status",
					"Returns the current state of the jdtls workspace (project import progress, "
					+ "build status, index readiness, classpath / Maven dependency errors). "
					+ "Call this before running other tools to verify the workspace is ready, "
					+ "or to diagnose dependency-resolution failures.",
					Map.of(), List.of()),
				this::mcpWorkspaceStatus);
	}

	// ---- MCP handler adapters ----

	private McpSchema.CallToolResult mcpHover(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		Map<String, Object> a = req.arguments();
		return text(hover(str(a, "uri"), num(a, "line"), num(a, "character")));
	}

	private McpSchema.CallToolResult mcpDefinition(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		Map<String, Object> a = req.arguments();
		return text(definition(str(a, "uri"), num(a, "line"), num(a, "character")));
	}

	private McpSchema.CallToolResult mcpReferences(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		Map<String, Object> a = req.arguments();
		return text(references(str(a, "uri"), num(a, "line"), num(a, "character"), bool(a, "includeDeclaration")));
	}

	private McpSchema.CallToolResult mcpCompletion(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		Map<String, Object> a = req.arguments();
		return text(completion(str(a, "uri"), num(a, "line"), num(a, "character")));
	}

	private McpSchema.CallToolResult mcpDocumentSymbols(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		return text(documentSymbols(str(req.arguments(), "uri")));
	}

	private McpSchema.CallToolResult mcpWorkspaceSymbols(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		return text(workspaceSymbols(str(req.arguments(), "query")));
	}

	private McpSchema.CallToolResult mcpDiagnostics(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		Map<String, Object> arguments = req.arguments();
		Object uriValue = arguments != null ? arguments.get("uri") : null;
		return text(diagnostics(uriValue != null ? uriValue.toString() : ""));
	}

	private McpSchema.CallToolResult mcpWorkspaceStatus(McpSyncServerExchange ex,
			McpSchema.CallToolRequest req) {
		return text(workspaceStatus());
	}

	// ---- Schema helpers ----

	private static McpSchema.Tool tool(String name, String description,
			Map<String, Object> properties, List<String> required) {
		return McpSchema.Tool.builder()
				.name(name)
				.description(description)
				.inputSchema(new McpSchema.JsonSchema(
						"object", properties, required, null, null, null))
				.build();
	}

	private static Map<String, Object> prop(String type, String description) {
		return Map.of("type", type, "description", description);
	}

	private static McpSchema.CallToolResult text(String value) {
		return McpSchema.CallToolResult.builder()
				.addTextContent(value)
				.isError(false)
				.build();
	}

	// ---- Argument helpers ----

	private static String str(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) throw new IllegalArgumentException("Missing argument: " + key);
		return v.toString();
	}

	private static int num(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) throw new IllegalArgumentException("Missing argument: " + key);
		return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
	}

	private static boolean bool(Map<String, Object> args, String key) {
		Object v = args.get(key);
		if (v == null) return false;
		return v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
	}

	// ---- Formatting helpers ----

	private static String formatLocation(String uri, Position pos) {
		return uri + ":" + (pos.getLine() + 1) + ":" + (pos.getCharacter() + 1);
	}

	private static void appendDocumentSymbol(StringBuilder sb,
			DocumentSymbol sym, int depth) {
		sb.append("  ".repeat(depth))
		  .append("- [").append(sym.getKind()).append("] ")
		  .append(sym.getName()).append('\n');
		if (sym.getChildren() != null) {
			for (DocumentSymbol child : sym.getChildren()) {
				appendDocumentSymbol(sb, child, depth + 1);
			}
		}
	}
}
