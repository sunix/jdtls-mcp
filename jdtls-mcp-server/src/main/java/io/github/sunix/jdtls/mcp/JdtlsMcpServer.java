package io.github.sunix.jdtls.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Main entry point for the jdtls MCP server.
 *
 * <p>This server exposes the Eclipse JDT Language Server (jdtls) capabilities as
 * <a href="https://modelcontextprotocol.io/">Model Context Protocol (MCP)</a> tools,
 * allowing LLM agents to interact with Java codebases through a standardised interface.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 * [LLM / MCP Client]   &lt;--MCP (stdio)--&gt;   [jdtls-mcp-server]   &lt;--LSP (stdio)--&gt;   [jdtls]
 * </pre>
 *
 * <h2>Configuration</h2>
 * <p>The server is configured via command-line arguments or environment variables:</p>
 * <ul>
 *   <li>{@code --jdtls-home} or {@code JDTLS_HOME} – path to the jdtls installation directory</li>
 *   <li>{@code --workspace} or {@code JDTLS_WORKSPACE} – path to the Java workspace/project to analyse</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * java -jar jdtls-mcp-server.jar --jdtls-home /path/to/jdtls --workspace /path/to/project
 * </pre>
 *
 * <p>Or using environment variables:</p>
 * <pre>
 * export JDTLS_HOME=/path/to/jdtls
 * export JDTLS_WORKSPACE=/path/to/project
 * java -jar jdtls-mcp-server.jar
 * </pre>
 *
 * <h2>MCP Transport</h2>
 * <p>The server communicates with MCP clients over stdio. All logging is directed to
 * stderr so as not to interfere with the MCP protocol messages on stdout.</p>
 */
public class JdtlsMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(JdtlsMcpServer.class);

    private static final String SERVER_NAME = "jdtls-mcp";
    private static final String SERVER_VERSION = "1.0-SNAPSHOT";

    public static void main(String[] args) throws Exception {
        Config config = parseConfig(args);

        logger.info("Starting {} v{}", SERVER_NAME, SERVER_VERSION);
        logger.info("jdtls home: {}", config.jdtlsHome);
        logger.info("Workspace:  {}", config.workspacePath);

        // Start jdtls subprocess
        JdtlsProcess jdtlsProcess = new JdtlsProcess(config.jdtlsHome, config.workspacePath);
        jdtlsProcess.start();

        // Connect LSP client to jdtls
        JdtlsLanguageClient lspClient = new JdtlsLanguageClient(
                jdtlsProcess.getInputStream(),
                jdtlsProcess.getOutputStream(),
                config.workspacePath);

        try {
            lspClient.initialize();
        } catch (Exception e) {
            logger.error("Failed to initialize jdtls", e);
            jdtlsProcess.stop();
            System.exit(1);
        }

        // Create MCP server using stdio transport (System.in / System.out)
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

        // Build and register all tools
        JavaLanguageServerTools tools = new JavaLanguageServerTools(lspClient);
        var serverSpec = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION);
        serverSpec.capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build());

        tools.registerTools(serverSpec);

        McpSyncServer mcpServer = serverSpec.build();
        logger.info("MCP server started – listening on stdio");

        // Keep the server running until stdin is closed (MCP client disconnects)
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down jdtls MCP server");
            lspClient.shutdown();
            jdtlsProcess.stop();
            mcpServer.close();
            latch.countDown();
        }, "jdtls-mcp-shutdown"));

        // Block until the server is stopped
        latch.await();
    }

    // ---- Configuration ----

    private record Config(Path jdtlsHome, Path workspacePath) {}

    private static Config parseConfig(String[] args) {
        Path jdtlsHome = null;
        Path workspacePath = null;

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jdtls-home" -> {
                    if (i + 1 < args.length) jdtlsHome = Path.of(args[++i]);
                    else logger.warn("Missing value for --jdtls-home");
                }
                case "--workspace" -> {
                    if (i + 1 < args.length) workspacePath = Path.of(args[++i]);
                    else logger.warn("Missing value for --workspace");
                }
                default -> logger.warn("Unknown argument: {}", args[i]);
            }
        }

        // Fall back to environment variables
        if (jdtlsHome == null) {
            String env = System.getenv("JDTLS_HOME");
            if (env != null) jdtlsHome = Path.of(env);
        }
        if (workspacePath == null) {
            String env = System.getenv("JDTLS_WORKSPACE");
            if (env != null) workspacePath = Path.of(env);
        }

        // Validate
        if (jdtlsHome == null) {
            System.err.println("ERROR: jdtls home path is required. " +
                    "Provide --jdtls-home <path> or set JDTLS_HOME environment variable.");
            printUsage();
            System.exit(1);
        }
        if (workspacePath == null) {
            System.err.println("ERROR: Workspace path is required. " +
                    "Provide --workspace <path> or set JDTLS_WORKSPACE environment variable.");
            printUsage();
            System.exit(1);
        }
        if (!Files.isDirectory(jdtlsHome)) {
            System.err.println("ERROR: jdtls home directory does not exist: " + jdtlsHome);
            System.exit(1);
        }
        if (!Files.isDirectory(workspacePath)) {
            System.err.println("ERROR: Workspace directory does not exist: " + workspacePath);
            System.exit(1);
        }

        return new Config(jdtlsHome, workspacePath);
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -jar jdtls-mcp-server.jar --jdtls-home <path> --workspace <path>

                Arguments:
                  --jdtls-home <path>   Path to the jdtls installation directory.
                                        Can also be set via the JDTLS_HOME environment variable.
                  --workspace <path>    Path to the Java workspace/project to analyse.
                                        Can also be set via the JDTLS_WORKSPACE environment variable.

                Example:
                  java -jar jdtls-mcp-server.jar \\
                    --jdtls-home /opt/jdtls \\
                    --workspace /home/user/my-java-project
                """);
    }
}
