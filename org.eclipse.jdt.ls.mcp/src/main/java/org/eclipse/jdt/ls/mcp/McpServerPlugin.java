package org.eclipse.jdt.ls.mcp;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator for the JDT LS MCP bridge plugin.
 *
 * <p>This bundle registers an Eclipse Application ({@code org.eclipse.jdt.ls.mcp.app})
 * that exposes the Java language intelligence from
 * {@code org.eclipse.jdt.ls.core} as
 * <a href="https://modelcontextprotocol.io/">Model Context Protocol (MCP)</a> tools.</p>
 *
 * <p>Start the MCP application instead of the standard LSP application by passing
 * {@code -Declipse.application=org.eclipse.jdt.ls.mcp.app} to the JVM.</p>
 */
public class McpServerPlugin implements BundleActivator {

	/** The plugin singleton, set during {@link #start(BundleContext)}. */
	private static McpServerPlugin instance;

	private BundleContext context;

	@Override
	public void start(BundleContext bundleContext) throws Exception {
		instance = this;
		this.context = bundleContext;
	}

	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		instance = null;
		this.context = null;
	}

	/** Returns the singleton plugin instance. */
	public static McpServerPlugin getInstance() {
		return instance;
	}

	/** Returns the OSGi bundle context. */
	public BundleContext getBundleContext() {
		return context;
	}
}
