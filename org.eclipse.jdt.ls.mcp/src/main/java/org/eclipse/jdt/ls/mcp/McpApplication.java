package org.eclipse.jdt.ls.mcp;

import java.util.concurrent.CountDownLatch;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.JobHelpers;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.lsp4j.ClientCapabilities;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
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
		PreferenceManager preferencesManager = jdtlsPlugin.getPreferencesManager();
		ProjectsManager projectsManager = jdtlsPlugin.getProjectsManager();

		// ---------------------------------------------------------------
		// 2.  Initialise the workspace
		//     This mirrors what InitHandler does when the LSP client sends
		//     an "initialize" request.  We use the workspace that was
		//     passed to Equinox via the standard -data argument.
		// ---------------------------------------------------------------
		// Seed ClientPreferences so ProjectsManager.initializeProjects() does not NPE.
		// Normally this is done during the LSP "initialize" handshake; we do it
		// manually here because MCP starts without an LSP client connection.
		preferencesManager.updateClientPrefences(
				new ClientCapabilities(), java.util.Collections.emptyMap());

		IPath workspaceRoot = Path.fromOSString(
				org.eclipse.core.runtime.Platform.getLocation().toOSString());
		projectsManager.initializeProjects(
				java.util.Collections.singletonList(workspaceRoot), monitor);

		// Wait for m2e's ProjectRegistryRefreshJob to finish configuring the
		// Maven project (adds org.eclipse.jdt.core.javanature, classpath, etc.)
		// before we trigger a build.  Without this the workspace build runs on
		// an incompletely-configured project and the JDT type-name index stays
		// empty, causing java_workspace_symbols to return no results.
		JobHelpers.waitForProjectRegistryRefreshJob();

		// Now do a full workspace build so Eclipse compiles the sources and
		// populates the per-project output folders.
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		workspace.build(IncrementalProjectBuilder.FULL_BUILD, monitor);

		// Finally, wait for the JDT search index (type-name index) to be fully
		// populated.  This ensures java_workspace_symbols works on the first call.
		JobHelpers.waitUntilIndexesReady();

		// ---------------------------------------------------------------
		// 3.  Build MCP server with all Java language tools
		// ---------------------------------------------------------------
		JdtlsMcpTools tools = new JdtlsMcpTools(preferencesManager, projectsManager);

		// Obtain the McpJsonMapper. McpJsonDefaults.getMapper() uses ServiceLoader
		// which does not work across OSGi bundles. We work around this by:
		// 1. Finding and fully starting the mcp-json-jackson2 bundle.
		// 2. Loading JacksonMcpJsonMapperSupplier from that bundle's classloader.
		// 3. Injecting it into McpJsonDefaults' static service loader via reflection.
		BundleContext ctx = McpServerPlugin.getInstance().getBundleContext();

		Bundle mcpJsonBundle = null;
		for (Bundle b : ctx.getBundles()) {
			if ("io.modelcontextprotocol.sdk.mcp-json-jackson2".equals(b.getSymbolicName())) {
				mcpJsonBundle = b;
				break;
			}
		}
		if (mcpJsonBundle == null) {
			throw new IllegalStateException(
					"io.modelcontextprotocol.sdk.mcp-json-jackson2 bundle not found");
		}
		// Force the bundle to ACTIVE state (bypasses lazy-activation policy).
		if (mcpJsonBundle.getState() != Bundle.ACTIVE) {
			// Loading a class from the bundle forces it from STARTING to ACTIVE state
			// (which is how lazy activation works) without requiring start() which can
			// cause deadlocks when called from an application thread.
			mcpJsonBundle.loadClass(
					"io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier");
		}

		// Instantiate JacksonMcpJsonMapperSupplier via the bundle's own classloader.
		// The implementation class is in a non-exported package, so we must use
		// the bundle's classloader directly rather than a normal import.
		Class<?> supplierClass = mcpJsonBundle.loadClass(
				"io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier");
		McpJsonMapperSupplier mapperSupplier = (McpJsonMapperSupplier) supplierClass
				.getDeclaredConstructor().newInstance();

		// Inject into McpJsonDefaults static service loader via reflection
		// so that McpJsonDefaults.getMapper() (and McpJsonDefaults.getSchemaValidator()
		// used internally by McpServer.build()) work correctly in OSGi.
		{
			java.lang.reflect.Constructor<McpJsonDefaults> ctor =
					McpJsonDefaults.class.getDeclaredConstructor();
			ctor.setAccessible(true);
			McpJsonDefaults dummy = ctor.newInstance();

			java.lang.reflect.Method mapperSetter = McpJsonDefaults.class
					.getDeclaredMethod("setMcpJsonMapperSupplier", McpJsonMapperSupplier.class);
			mapperSetter.setAccessible(true);
			mapperSetter.invoke(dummy, mapperSupplier);

			// Also inject the schema validator supplier.
			Class<?> validatorSupplierClass = mcpJsonBundle.loadClass(
					"io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier");
			JsonSchemaValidatorSupplier validatorSupplier = (JsonSchemaValidatorSupplier)
					validatorSupplierClass.getDeclaredConstructor().newInstance();
			java.lang.reflect.Method validatorSetter = McpJsonDefaults.class
					.getDeclaredMethod("setJsonSchemaValidatorSupplier", JsonSchemaValidatorSupplier.class);
			validatorSetter.setAccessible(true);
			validatorSetter.invoke(dummy, validatorSupplier);
		}
		// McpJsonDefaults.getMapper() and getSchemaValidator() now work.

		StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

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
