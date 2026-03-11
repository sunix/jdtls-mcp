package io.github.sunix.jdtls.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the lifecycle of the Eclipse JDT Language Server (jdtls) subprocess.
 *
 * <p>jdtls is an Eclipse application that exposes Java language intelligence via the
 * Language Server Protocol (LSP). This class starts jdtls as a child process and
 * provides access to its standard input/output streams for LSP communication.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * JdtlsProcess process = new JdtlsProcess("/path/to/jdtls", "/path/to/workspace");
 * process.start();
 * // use process.getInputStream() and process.getOutputStream() for LSP communication
 * process.stop();
 * }</pre>
 */
public class JdtlsProcess {

    private static final Logger logger = LoggerFactory.getLogger(JdtlsProcess.class);

    private static final String LAUNCHER_JAR_PREFIX = "org.eclipse.equinox.launcher_";

    private final Path jdtlsHome;
    private final Path workspacePath;
    private final Path dataDirectory;
    private Process process;

    /**
     * Creates a new JdtlsProcess.
     *
     * @param jdtlsHome     path to the jdtls installation directory (contains plugins/ and config_linux/, config_win/, config_mac/)
     * @param workspacePath path to the Java project workspace to analyse
     */
    public JdtlsProcess(Path jdtlsHome, Path workspacePath) {
        this.jdtlsHome = jdtlsHome;
        this.workspacePath = workspacePath;
        this.dataDirectory = workspacePath.resolve(".jdtls-data");
    }

    /**
     * Starts the jdtls process.
     *
     * @throws IOException          if the process cannot be started
     * @throws IllegalStateException if jdtls is already running or the installation is invalid
     */
    public void start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("jdtls process is already running");
        }

        Path launcherJar = findLauncherJar();
        Path configDir = findConfigDirectory();

        Files.createDirectories(dataDirectory);

        List<String> command = buildCommand(launcherJar, configDir);
        logger.info("Starting jdtls with command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);
        // jdtls logs go to a file or to its own stderr; we capture its stderr to our logger
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        process = processBuilder.start();
        logger.info("jdtls process started with PID {}", process.pid());

        // Register shutdown hook to clean up jdtls when the JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process != null && process.isAlive()) {
                logger.info("Shutdown hook: stopping jdtls process");
                process.destroy();
            }
        }, "jdtls-shutdown-hook"));
    }

    /**
     * Stops the jdtls process gracefully, or forcibly if it does not terminate.
     */
    public void stop() {
        if (process == null) {
            return;
        }
        if (process.isAlive()) {
            logger.info("Stopping jdtls process");
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        process = null;
    }

    /**
     * Returns the standard input stream of the jdtls process (used to send LSP messages to jdtls).
     */
    public java.io.OutputStream getOutputStream() {
        ensureRunning();
        return process.getOutputStream();
    }

    /**
     * Returns the standard output stream of the jdtls process (used to receive LSP messages from jdtls).
     */
    public java.io.InputStream getInputStream() {
        ensureRunning();
        return process.getInputStream();
    }

    /**
     * Returns true if the jdtls process is currently running.
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    private void ensureRunning() {
        if (process == null || !process.isAlive()) {
            throw new IllegalStateException("jdtls process is not running");
        }
    }

    private Path findLauncherJar() {
        Path pluginsDir = jdtlsHome.resolve("plugins");
        if (!Files.isDirectory(pluginsDir)) {
            throw new IllegalStateException(
                    "jdtls plugins directory not found: " + pluginsDir +
                    ". Please ensure JDTLS_HOME points to a valid jdtls installation.");
        }
        File[] launchers = pluginsDir.toFile().listFiles(
                (dir, name) -> name.startsWith(LAUNCHER_JAR_PREFIX) && name.endsWith(".jar"));
        if (launchers == null || launchers.length == 0) {
            throw new IllegalStateException(
                    "Eclipse Equinox launcher jar not found in: " + pluginsDir +
                    ". Looking for files starting with '" + LAUNCHER_JAR_PREFIX + "'");
        }
        // Pick the latest version if multiple exist
        Arrays.sort(launchers, (a, b) -> b.getName().compareTo(a.getName()));
        return launchers[0].toPath();
    }

    private Path findConfigDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String configDirName;
        if (os.contains("win")) {
            configDirName = "config_win";
        } else if (os.contains("mac")) {
            configDirName = "config_mac";
        } else {
            configDirName = "config_linux";
        }
        Path configDir = jdtlsHome.resolve(configDirName);
        if (!Files.isDirectory(configDir)) {
            throw new IllegalStateException(
                    "jdtls config directory not found: " + configDir +
                    ". Please ensure JDTLS_HOME points to a valid jdtls installation.");
        }
        return configDir;
    }

    private List<String> buildCommand(Path launcherJar, Path configDir) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java"));
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=ALL");
        command.add("-Xmx1G");
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(launcherJar.toAbsolutePath().toString());
        command.add("-configuration");
        command.add(configDir.toAbsolutePath().toString());
        command.add("-data");
        command.add(dataDirectory.toAbsolutePath().toString());
        return command;
    }
}
