package org.eclipse.jdt.ls.mcp;

import java.util.concurrent.CountDownLatch;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferencesManager;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Eclipse application entry point for the JDT LS MCP bridge.
 *
 * <p>This application replaces the standard jdtls LSP application
 * ({@code org.eclipse.jdt.ls.core.id1}) with an MCP server that exposes the
 * same Java language intelligence through the Model Context Protocol.</p>
 *
 * <h2>Communication model</h2>
 * <pre>
 * [MCP Client / LLM agent]
 *         ↕  MCP over stdio
 * [McpApplication — this class]
 *         ↕  direct Java API calls (same JVM / OSGi container)
 * [org.eclipse.jdt.ls.core handlers]
 *         ↕  Eclipse JDT APIs
 * [Eclipse workspace / JDT compiler]
 * </pre>
 *
 * <h2>Usage</h2>
 * <p>Launch the Eclipse product with:</p>
 * <pre>
 * java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
 *      -jar plugins/org.eclipse.equinox.launcher_*.jar \
 *      -configuration config_linux \
 *      -data /path/to/workspace
 * </pre>
 */
public class McpApplication implements IApplication {

	private static final String SERVER_NAME    = "jdtls-mcp";
	private static final String SERVER_VERSION = "1.0.0";

	private McpSyncServer mcpServer;
	private final CountDownLatch shutdownLatch = new CountDownLatch(1);

	@Override
	public Object start(IApplicationContext appContext) throws Exception {
		IProgressMonitor monitor = new NullProgressMonitor();

		// ---------------------------------------------------------------
		// 1.  Obtain jdtls core services
		//     org.eclipse.jdt.ls.core's BundleActivator has already run by
		//     this point; its singleton is available immediately.
		// ---------------------------------------------------------------
		JavaLanguageServerPlugin jdtlsPlugin = JavaLanguageServerPlugin.getInstance();
		PreferencesManager preferencesManager = jdtlsPlugin.getPreferencesManager();
		ProjectsManager projectsManager = jdtlsPlugin.getProjectsManager();

		// ---------------------------------------------------------------
		// 2.  Initialise the workspace
		//     This mirrors what InitHandler does when the LSP client sends
		//     an "initialize" request.  We use the workspace that was
		//     passed to Equinox via the standard -data argument.
		// ---------------------------------------------------------------
		String workspaceRoot = org.eclipse.core.runtime.Platform
				.getLocation().toOSString();
		projectsManager.initializeProjects(
				java.util.Collections.singletonList(workspaceRoot), monitor);

		// ---------------------------------------------------------------
		// 3.  Build MCP server with all Java language tools
		// ---------------------------------------------------------------
		JdtlsMcpTools tools = new JdtlsMcpTools(preferencesManager, projectsManager);

		ObjectMapper objectMapper = new ObjectMapper()
				.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

		StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

		var spec = McpServer.sync(transport)
				.serverInfo(SERVER_NAME, SERVER_VERSION);
		spec.capabilities(McpSchema.ServerCapabilities.builder()
				.tools(true)
				.build());

		tools.registerTools(spec);

		mcpServer = spec.build();

		// ---------------------------------------------------------------
		// 4.  Register a shutdown hook and wait
		// ---------------------------------------------------------------
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (mcpServer != null) {
				mcpServer.close();
			}
			shutdownLatch.countDown();
		}, "jdtls-mcp-shutdown"));

		// Block here; the MCP server listener runs on its own thread.
		shutdownLatch.await();

		return IApplication.EXIT_OK;
	}

	@Override
	public void stop() {
		if (mcpServer != null) {
			mcpServer.close();
		}
		shutdownLatch.countDown();
	}
}
