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
| Java  | 17 +    | Must be on `PATH`           |
| Maven | 3.9 +   | For building with Tycho     |

No other tooling is required.  Internet access is needed on first build to
resolve the Eclipse p2 target platform and Maven dependencies.

---

## Project structure

```
jdtls-mcp/
├── agent.md                              # This file
├── pom.xml                               # Tycho parent POM
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

After a successful build, set `PRODUCT_DIR` to your platform folder, then launch
the MCP server pointing at the included test workspace:

```bash
# Linux x86_64
export PRODUCT_DIR="$PWD/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/linux/gtk/x86_64"

# macOS arm64 (Apple Silicon)
# export PRODUCT_DIR="$PWD/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/macosx/cocoa/aarch64"

# Discover the exact equinox launcher jar name
LAUNCHER=$(ls "$PRODUCT_DIR/plugins/" | grep equinox.launcher_ | head -1)

java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
     -jar "$PRODUCT_DIR/plugins/$LAUNCHER" \
     -configuration "$PRODUCT_DIR/configuration" \
     -data "$PWD/test-workspace/hello-jdtls"
```

The server reads MCP messages from **stdin** and writes responses to **stdout**.
It will block until the client disconnects or the process is killed.

---

## How to test the MCP server (agent self-testing)

The `test-workspace/hello-jdtls/` directory in this repo is a ready-to-use
Maven Java project.  Use it as the jdtls workspace when testing MCP tools.

### Step 1 — Build the project

```bash
mvn package -DskipTests
```

### Step 2 — Start the MCP server in the background

```bash
export REPO_ROOT="$PWD"
export PRODUCT_DIR="$REPO_ROOT/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/linux/gtk/x86_64"
LAUNCHER=$(ls "$PRODUCT_DIR/plugins/" | grep equinox.launcher_ | head -1)

java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
     -jar "$PRODUCT_DIR/plugins/$LAUNCHER" \
     -configuration "$PRODUCT_DIR/configuration" \
     -data "$REPO_ROOT/test-workspace/hello-jdtls" \
     > /tmp/jdtls-mcp.log 2>&1 &

MCP_PID=$!
echo "MCP server PID: $MCP_PID"
sleep 5   # allow the Eclipse workspace to initialise
```

### Step 3 — Send an MCP initialise handshake and tool call

Use a small Python or shell script to send JSON-RPC messages over the process's
stdin/stdout.  Example: list all symbols in `Greeter.java`:

```bash
WORKSPACE="$REPO_ROOT/test-workspace/hello-jdtls"
FILE_URI="file://$WORKSPACE/src/main/java/com/example/Greeter.java"

python3 - <<'EOF'
import json, subprocess, sys

req_init = {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
    "protocolVersion":"2024-11-05",
    "clientInfo":{"name":"test-client","version":"0.0.1"},
    "capabilities":{}}}

req_initialized = {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}

req_symbols = {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
    "name":"java_document_symbols",
    "arguments":{"uri":"file://$WORKSPACE/src/main/java/com/example/Greeter.java"}}}

def encode(msg):
    body = json.dumps(msg)
    return f"Content-Length: {len(body)}\r\n\r\n{body}"

msgs = encode(req_init) + encode(req_initialized) + encode(req_symbols)
print(msgs)
EOF
```

For a fully automated smoke-test, pipe MCP messages to the server process's
stdin and read the JSON-RPC responses from stdout.

### Step 4 — Stop the server

```bash
kill $MCP_PID
```

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
- **MCP transport**: stdio only (no HTTP/SSE transport is implemented yet).

---

## Useful references

- [Model Context Protocol specification](https://modelcontextprotocol.io/docs/concepts/architecture)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [eclipse-jdtls source](https://github.com/eclipse-jdtls/eclipse.jdt.ls) — especially `org.eclipse.jdt.ls.core.internal.handlers`
- [vscode-java](https://github.com/redhat-developer/vscode-java) — reference implementation of a jdtls client
- [Tycho documentation](https://tycho.eclipseprojects.io/doc/latest/)
- [langchain4j `@Tool` annotations](https://docs.langchain4j.dev/tutorials/tools)
