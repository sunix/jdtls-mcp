# Agent Guide — jdtls-mcp

This document provides everything an LLM coding agent needs to work effectively
on the `jdtls-mcp` project.

---

## Project goal

`jdtls-mcp` exposes the Eclipse JDT Language Server (jdtls) to LLM agents via
the Model Context Protocol (MCP).

The inspiration and closest analog is
[vscode-java](https://github.com/redhat-developer/vscode-java), which surfaces
jdtls to human developers through the VS Code IDE:

```
Human → codes in VS Code → vscode-java → jdtls (LSP) → Java intelligence
```

Our project follows the same pattern but the consumer is an LLM agent instead of
a human developer:

```
Human prompts Copilot / Claude → LLM agent writes Java → jdtls-mcp → jdtls (same JVM) → Java intelligence
```

Key references:

- [redhat-developer/vscode-java](https://github.com/redhat-developer/vscode-java) — how jdtls is configured and used as a language server
- [eclipse-jdtls/eclipse.jdt.ls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) — the upstream language server being extended

The MCP bundle calls jdtls handler classes **directly in the same JVM** — no
subprocess, no network hop, no second Java process.

---

## Required environment

| Tool  | Version | Notes                       |
|-------|---------|-----------------------------|
| Java  | 21 +    | Must be on `PATH`           |
| Maven | 3.9 +   | For building with Tycho     |

No other tooling is required.  Internet access is needed on first build to
resolve the Eclipse p2 target platform and Maven dependencies.

---

## Project structure

```
jdtls-mcp/
├── agent.md                              # This file
├── pom.xml                               # Tycho parent POM
├── scripts/
│   ├── start-mcp-server.sh               # Start the server against any workspace
│   └── test-mcp.sh                       # Smoke-test all tools against test-workspace
├── org.eclipse.jdt.ls.mcp.target/       # Eclipse target-platform definition (p2 + Maven deps)
│   └── org.eclipse.jdt.ls.mcp.tp.target
├── org.eclipse.jdt.ls.mcp/              # OSGi eclipse-plugin — MCP bridge
│   ├── META-INF/MANIFEST.MF
│   ├── plugin.xml
│   └── src/main/java/org/eclipse/jdt/ls/mcp/
│       ├── McpServerPlugin.java          # BundleActivator
│       ├── McpApplication.java           # Eclipse IApplication — MCP server entry point
│       └── JdtlsMcpTools.java            # @Tool methods + MCP tool registration
├── org.eclipse.jdt.ls.mcp.product/      # Eclipse product packaging
│   └── jdtls-mcp.product
└── test-workspace/                       # Basic Maven project used to test the MCP server
    └── hello-jdtls/
```

The project uses the **Tycho OSGi/Eclipse-plugin build system** (not a standard
Maven fat-jar build).  All modules have `<packaging>` set to Tycho types
(`eclipse-plugin`, `eclipse-repository`, `eclipse-target-definition`).

---

## How to build

```bash
cd /path/to/jdtls-mcp
mvn package
```

The built product lands under:

```
org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/
├── linux/gtk/x86_64/
├── linux/gtk/aarch64/
├── macosx/cocoa/x86_64/
├── macosx/cocoa/aarch64/
└── win32/win32/x86_64/
```

To skip tests and speed up iteration:

```bash
mvn package -DskipTests
```

To build only the plugin module (useful during development):

```bash
mvn package -pl org.eclipse.jdt.ls.mcp.product -am
```

---

## How to start in development mode

Use the provided script — it auto-detects OS and architecture:

```bash
# Against the bundled test workspace
./scripts/start-mcp-server.sh

# Against your own project
./scripts/start-mcp-server.sh /path/to/my-java-project /tmp/jdtls-data
```

The server reads MCP messages from **stdin** and writes responses to **stdout**.
It will block until the client disconnects or the process is killed.

If you need to launch manually (e.g., from a different working directory):

```bash
export PRODUCT_DIR="$PWD/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/linux/gtk/x86_64"
LAUNCHER=$(ls "$PRODUCT_DIR/plugins/org.eclipse.equinox.launcher_"*.jar | head -1)

java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
     -Dosgi.bundles.defaultStartLevel=4 \
     -jar "$LAUNCHER" \
     -configuration "$PRODUCT_DIR/configuration" \
     -data "$PWD/test-workspace/hello-jdtls"
```

---

## How to test the MCP server (agent self-testing)

The `test-workspace/hello-jdtls/` directory in this repo is a ready-to-use
Maven Java project.  Use it as the jdtls workspace when testing MCP tools.

### Quickest path — smoke-test script

```bash
mvn package -DskipTests     # build first if you haven't already
./scripts/test-mcp.sh       # runs all tool calls, prints raw JSON-RPC responses
```

`scripts/test-mcp.sh` starts the server, sends the MCP handshake followed by
one call for every tool, and exits.  Startup takes **~60 s** on first run while
Maven imports the project and the JDT type-name index warms up.

### Manual step-by-step

#### Step 1 — Build

```bash
mvn package -DskipTests
```

#### Step 2 — Start the server and send messages via FIFO

> **Transport format:** the server uses **NDJSON** (one JSON object per line,
> no `Content-Length` framing).  Do NOT use LSP-style `Content-Length` headers.

```bash
# Create a named FIFO so stdin stays open while the server processes requests
mkfifo /tmp/mcp-test.fifo

WORKSPACE="$PWD/test-workspace/hello-jdtls"
FILE_URI="file://$WORKSPACE/src/main/java/com/example/Greeter.java"

# Write messages into the FIFO in the background
{
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"java_document_symbols\",\"arguments\":{\"uri\":\"$FILE_URI\"}}}"
  sleep 300   # keep stdin open
} > /tmp/mcp-test.fifo &
WRITER=$!

# Run the server (reads from fifo, writes JSON responses to stdout)
./scripts/start-mcp-server.sh 2>/dev/null < /tmp/mcp-test.fifo

kill $WRITER 2>/dev/null
rm /tmp/mcp-test.fifo
```

#### Step 3 — Interpret the output

Each response is one JSON object on its own line.  Responses may arrive out of
order relative to requests (identified by matching `id` fields).

---

## Conventional commits

All commits **must** follow the
[Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<optional scope>): <short description>

[optional body]

[optional footer(s)]
```

Allowed types:

| Type       | When to use                                                    |
|------------|----------------------------------------------------------------|
| `feat`     | A new feature or new MCP tool                                  |
| `fix`      | A bug fix                                                      |
| `refactor` | Code change that neither fixes a bug nor adds a feature        |
| `test`     | Adding or fixing tests                                         |
| `docs`     | Documentation changes only                                     |
| `build`    | Changes to build configuration (pom.xml, target platform, …)  |
| `chore`    | Maintenance tasks (CI config, .gitignore, …)                   |

Examples:

```
feat(tools): add java_diagnostics MCP tool
fix(McpApplication): handle null workspace root gracefully
docs: update agent.md with macOS launch instructions
build: upgrade Tycho to 5.1.0
```

---

## Key code locations

| File | Purpose |
|------|---------|
| `org.eclipse.jdt.ls.mcp/src/main/java/org/eclipse/jdt/ls/mcp/McpApplication.java` | Eclipse `IApplication` entry point — initialises jdtls workspace and starts the MCP stdio server |
| `org.eclipse.jdt.ls.mcp/src/main/java/org/eclipse/jdt/ls/mcp/JdtlsMcpTools.java` | All MCP tool implementations (hover, definition, references, completion, symbols) |
| `org.eclipse.jdt.ls.mcp/src/main/java/org/eclipse/jdt/ls/mcp/McpServerPlugin.java` | OSGi `BundleActivator` |
| `org.eclipse.jdt.ls.mcp/plugin.xml` | Registers the `org.eclipse.jdt.ls.mcp.app` Eclipse application extension |
| `org.eclipse.jdt.ls.mcp.target/org.eclipse.jdt.ls.mcp.tp.target` | Target platform: jdtls p2 repo + MCP Java SDK + langchain4j from Maven Central |
| `org.eclipse.jdt.ls.mcp.product/jdtls-mcp.product` | Eclipse product descriptor — lists all OSGi bundles |
| `test-workspace/hello-jdtls/` | Sample Maven project used as the jdtls workspace during testing |

---

## Adding a new MCP tool

1. Add a new method annotated with `@Tool` and `@P` in `JdtlsMcpTools.java`.
2. Call the appropriate jdtls handler class (see `org.eclipse.jdt.ls.core.internal.handlers`).
3. Register the tool in `JdtlsMcpTools.registerTools()` following the existing
   `toolCall(tool(…), this::mcpXxx)` pattern.
4. Test the tool manually using the test-workspace (see self-testing section above).
5. Commit with `feat(tools): add java_<toolname> MCP tool`.

---

## Architecture notes

- **Same JVM**: the MCP bundle and jdtls core run in the same Equinox OSGi
  container.  There is no subprocess or network socket between them.
- **Tycho build**: use only OSGi-aware dependencies declared in the `.target`
  file.  Do **not** add plain Maven `<dependency>` entries to plugin modules.
- **LSP position convention**: all positions are **0-based** line and character
  offsets (LSP convention), not 1-based.
- **MCP transport**: stdio, **NDJSON** format — one JSON object per line with a
  trailing newline.  There is no `Content-Length` header framing (unlike LSP).
  The `StdioServerTransportProvider` from the MCP Java SDK uses
  `BufferedReader.readLine()` for input and appends `\n` to each output object.
- **Startup sequence**: `McpApplication` blocks stdin until the full startup
  sequence completes: Maven import → `waitForProjectRegistryRefreshJob()` →
  `workspace.build(FULL_BUILD)` → `waitUntilIndexesReady()`.  This ensures the
  JDT type-name index is populated before any tool call arrives, avoiding empty
  results from `java_workspace_symbols` and `java_references`.
- **jdt.ls-java-project**: when the workspace root contains no `.project` file,
  jdtls creates a synthetic `jdt.ls-java-project` directory there.  This is
  generated at runtime and is listed in `.gitignore`.
- **MCP transport**: stdio only (no HTTP/SSE transport is implemented yet).

---

## Useful references

- [Model Context Protocol specification](https://modelcontextprotocol.io/docs/concepts/architecture)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [eclipse-jdtls source](https://github.com/eclipse-jdtls/eclipse.jdt.ls) — especially `org.eclipse.jdt.ls.core.internal.handlers`
- [vscode-java](https://github.com/redhat-developer/vscode-java) — reference implementation of a jdtls client
- [Tycho documentation](https://tycho.eclipseprojects.io/doc/latest/)
- [langchain4j `@Tool` annotations](https://docs.langchain4j.dev/tutorials/tools)
