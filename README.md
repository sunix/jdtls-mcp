# jdtls-mcp

> Expose the Eclipse JDT Language Server to LLM agents via the Model Context Protocol (MCP)

## Overview

`jdtls-mcp` is an Eclipse application that extends
[eclipse-jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) with a new
OSGi bundle (`org.eclipse.jdt.ls.mcp`) that exposes Java language intelligence
as [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) tools.

```
┌─────────────────────────────────────────────────────────┐
│  Eclipse / Equinox OSGi container (one JVM process)     │
│                                                          │
│  ┌───────────────────────┐   ┌──────────────────────┐   │
│  │ org.eclipse.jdt.ls.core│   │ org.eclipse.jdt.ls   │   │
│  │ (jdtls: handlers,      │◄──│ .mcp                 │   │
│  │  project manager, JDT) │   │ (McpApplication +    │   │
│  └───────────────────────┘   │  JdtlsMcpTools)       │   │
│                               └──────────┬───────────┘   │
└──────────────────────────────────────────│───────────────┘
                                           │ MCP over stdio
                                 ┌─────────▼─────────┐
                                 │  LLM / MCP client  │
                                 │  (Claude, GPT, …)  │
                                 └────────────────────┘
```

The MCP bundle calls jdtls handler classes **directly in the same JVM** — no
subprocess spawning, no network hop, no second Java process.  LLM agents talk
to the Eclipse application via stdio using the MCP protocol.

## Project structure

```
jdtls-mcp/
├── agent.md                             # LLM agent onboarding guide
├── pom.xml                              # Tycho parent (mirrors eclipse-jdtls)
├── org.eclipse.jdt.ls.mcp.target/      # Target-platform definition
│   ├── pom.xml
│   └── org.eclipse.jdt.ls.mcp.tp.target  # p2 + Maven deps (MCP SDK, langchain4j)
├── org.eclipse.jdt.ls.mcp/             # New OSGi eclipse-plugin bundle
│   ├── META-INF/MANIFEST.MF
│   ├── plugin.xml                      # Registers org.eclipse.jdt.ls.mcp.app
│   ├── build.properties
│   └── src/main/java/org/eclipse/jdt/ls/mcp/
│       ├── McpServerPlugin.java        # BundleActivator
│       ├── McpApplication.java         # Eclipse IApplication — MCP entry point
│       └── JdtlsMcpTools.java          # @Tool methods + MCP tool registration
├── org.eclipse.jdt.ls.mcp.product/    # Eclipse product packaging
│   ├── pom.xml
│   └── jdtls-mcp.product               # All jdtls bundles + org.eclipse.jdt.ls.mcp
└── test-workspace/hello-jdtls/        # Sample Maven project for manual testing
```

## How it works

1. **Target platform** (`org.eclipse.jdt.ls.mcp.target`) references the jdtls
   snapshot p2 repository, Eclipse 2025-12 release train, LSP4J 0.24.0, and
   the MCP Java SDK + langchain4j from Maven Central (wrapped as OSGi bundles
   by Tycho's `missingManifest="generate"` feature).

2. **Plugin bundle** (`org.eclipse.jdt.ls.mcp`) is a standard `eclipse-plugin`
   module built by Tycho.  It declares `Require-Bundle: org.eclipse.jdt.ls.core`
   and calls jdtls handler classes (`HoverHandler`, `NavigateToDefinitionHandler`,
   `ReferencesHandler`, `CompletionHandler`, `DocumentSymbolHandler`,
   `WorkspaceSymbolHandler`) directly.

3. **MCP Application** (`McpApplication`) registers under the extension point
   `org.eclipse.core.runtime.applications` as `org.eclipse.jdt.ls.mcp.app`.
   When selected as the Eclipse application, it initialises the jdtls workspace
   and starts an MCP server on stdio.

4. **Product** (`org.eclipse.jdt.ls.mcp.product`) packages all jdtls bundles
   together with the new MCP bundle into a distributable Eclipse product.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 21+    | Must be on `PATH` |
| Maven | 3.9+ | For building |

## Building

```bash
git clone https://github.com/sunix/jdtls-mcp.git
cd jdtls-mcp
mvn package
```

The built product lands at:

```
org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/
├── linux/gtk/x86_64/
├── linux/gtk/aarch64/
├── macosx/cocoa/x86_64/
├── macosx/cocoa/aarch64/
└── win32/win32/x86_64/
```

Each platform folder contains:

```
plugins/
  org.eclipse.equinox.launcher_<version>.jar
configuration/
  config.ini
```

> [!TIP]
> Set `PRODUCT_DIR` to your platform's folder to simplify the commands below.
>
> **Linux x86_64**
> ```bash
> export PRODUCT_DIR="$PWD/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/linux/gtk/x86_64"
> ```
> **macOS arm64 (Apple Silicon)**
> ```bash
> export PRODUCT_DIR="$PWD/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product/macosx/cocoa/aarch64"
> ```

## Running manually

```bash
java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
     -jar "$PRODUCT_DIR/plugins/org.eclipse.equinox.launcher_<version>.jar" \
     -configuration "$PRODUCT_DIR/configuration" \
     -data /path/to/your-java-workspace
```

## Available MCP tools

| Tool | Description |
|------|-------------|
| `java_hover` | Get hover information (Javadoc, type info) at a position |
| `java_definition` | Find the definition of a symbol |
| `java_references` | Find all references to a symbol |
| `java_completion` | Get code completion suggestions |
| `java_document_symbols` | List all symbols in a file |
| `java_workspace_symbols` | Search for symbols across the workspace |

All position-based tools use **0-based** line and character offsets (LSP convention).

---

## Testing with MCP clients

The repository includes a ready-to-use sample Java project at
`test-workspace/hello-jdtls/` — use it as the jdtls workspace when trying
any of the clients below.

```bash
export WORKSPACE="$PWD/test-workspace/hello-jdtls"
```

The commands in each section refer to `$PRODUCT_DIR` (set above) and assume the
jdtls-mcp product has been built.

> [!IMPORTANT]
> The `org.eclipse.equinox.launcher_*.jar` glob must be replaced with the
> actual jar filename in your build output, for example:
> ```bash
> # Find the exact name after building:
> ls "$PRODUCT_DIR/plugins/" | grep equinox.launcher
> # → org.eclipse.equinox.launcher_1.6.900.v20240613-2009.jar
> ```
> In all configuration files below, replace the glob with that exact filename.

---

### GitHub Copilot in VSCode

> **Requires:** VSCode ≥ 1.99 with the GitHub Copilot extension (MCP support is
> enabled by default; no feature flag needed).

1. **Add the MCP server** by creating (or editing) `.vscode/mcp.json` in your
   Java project folder:

   ```bash
   mkdir -p "$WORKSPACE/.vscode"
   cat > "$WORKSPACE/.vscode/mcp.json" << EOF
   {
     "servers": {
       "jdtls": {
         "type": "stdio",
         "command": "java",
         "args": [
           "-Declipse.application=org.eclipse.jdt.ls.mcp.app",
           "-jar", "$PRODUCT_DIR/plugins/org.eclipse.equinox.launcher_<version>.jar",
           "-configuration", "$PRODUCT_DIR/configuration",
           "-data", "$WORKSPACE"
         ]
       }
     }
   }
   EOF
   ```

2. **Open the project** in VSCode:

   ```bash
   code "$WORKSPACE"
   ```

3. **Start the MCP server** — VSCode will prompt you to start MCP servers
   defined in `.vscode/mcp.json` when you open Copilot Chat.  Click **Start**
   next to `jdtls`.

4. **Open Copilot Chat** (`Ctrl+Alt+I` / `Cmd+Alt+I`) and try these prompts:

   ```
   Use the java_hover tool to show me the Javadoc for the greet() method in
   file:///path/to/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java at line 34.

   Use java_document_symbols to list all symbols in
   file:///path/to/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java

   Use java_workspace_symbols to find all classes named Greeter.
   ```

---

### Claude Code

> **Requires:** [Claude Code](https://docs.anthropic.com/en/docs/claude-code)
> CLI installed (`npm install -g @anthropic-ai/claude-code` or follow the
> official install guide).

1. **Register the MCP server** (run once):

   ```bash
   claude mcp add jdtls -- java \
     "-Declipse.application=org.eclipse.jdt.ls.mcp.app" \
     "-jar" "$PRODUCT_DIR/plugins/org.eclipse.equinox.launcher_<version>.jar" \
     "-configuration" "$PRODUCT_DIR/configuration" \
     "-data" "$WORKSPACE"
   ```

   Verify it was registered:

   ```bash
   claude mcp list
   ```

2. **Open the project with Claude Code:**

   ```bash
   cd "$WORKSPACE"
   claude
   ```

3. **Try these prompts inside the Claude Code REPL:**

   ```
   Show me the Javadoc for the greet() method in
   src/main/java/com/example/Greeter.java using the jdtls MCP tools.

   Find all references to the `name` field in Greeter.java.

   List all symbols defined in Greeter.java.
   ```

   Claude Code will automatically call the `java_hover`, `java_references`, and
   `java_document_symbols` tools and display the results inline.

4. **Remove the server** when done:

   ```bash
   claude mcp remove jdtls
   ```

---

### GitHub Copilot CLI (`gh copilot`)

> **Requires:** [GitHub CLI](https://cli.github.com/) with the Copilot extension.
> ```bash
> gh extension install github/gh-copilot
> gh auth login          # if not already authenticated
> ```

The GitHub Copilot CLI (`gh copilot suggest` / `gh copilot explain`) uses MCP
servers configured in the GitHub Copilot for CLI settings file.

1. **Find or create the config file:**

   | OS | Path |
   |----|------|
   | Linux / macOS | `~/.config/gh-copilot/config.yaml` (may vary by version) |

   Add the MCP server configuration:

   ```bash
   mkdir -p ~/.config/gh-copilot
   cat >> ~/.config/gh-copilot/config.yaml << EOF
   mcp:
     servers:
       jdtls:
         command: java
         args:
           - "-Declipse.application=org.eclipse.jdt.ls.mcp.app"
           - "-jar"
           - "$PRODUCT_DIR/plugins/org.eclipse.equinox.launcher_<version>.jar"
           - "-configuration"
           - "$PRODUCT_DIR/configuration"
           - "-data"
           - "$WORKSPACE"
   EOF
   ```

2. **Ask Copilot CLI a question about the Java project:**

   ```bash
   gh copilot suggest "What public methods does the Greeter class have? Use the jdtls MCP tools to check."
   ```

   Or use `explain` mode:

   ```bash
   gh copilot explain "Use java_workspace_symbols to find all classes in the test-workspace"
   ```

> [!NOTE]
> MCP support in Copilot CLI is evolving. Check
> [`gh copilot --help`](https://cli.github.com/manual/gh_copilot) or the
> [GitHub Copilot CLI changelog](https://docs.github.com/en/copilot/github-copilot-in-the-cli/about-github-copilot-in-the-cli)
> for the latest configuration options.

---

## Contributing

> **TL;DR** — Java 17+, Maven 3.9+, then `mvn package`.
> All commits must follow [Conventional Commits](https://www.conventionalcommits.org/).

### Prerequisites

| Tool  | Version | Notes                           |
|-------|---------|---------------------------------|
| Java  | 17+     | Must be on `PATH`               |
| Maven | 3.9+    | Tycho wraps OSGi builds via Maven |

An internet connection is required on first build to resolve the Eclipse p2
target platform and Maven Central dependencies.

### IDE setup

**Eclipse IDE** (recommended for OSGi/plugin development):

1. Install [Eclipse IDE for Eclipse Committers](https://www.eclipse.org/downloads/packages/) (2024-12 or later).
2. Install **Tycho Project Configurators** via *Help → Install New Software* from the Eclipse release update site.
3. Import the project: *File → Import → Maven → Existing Maven Projects*, select the repo root.
4. Eclipse will automatically set up the target platform from `org.eclipse.jdt.ls.mcp.tp.target`.

**VS Code / IntelliJ** also work for editing Java source; just run `mvn package`
from the terminal for builds.

### Build

```bash
mvn package                       # full build (all platforms)
mvn package -DskipTests           # skip test execution for faster iteration
mvn package -pl org.eclipse.jdt.ls.mcp.product -am  # only the plugin + product
```

### Testing your changes locally

Two helper scripts are provided in `scripts/`:

| Script | Purpose |
|--------|---------|
| [`scripts/start-mcp-server.sh`](./scripts/start-mcp-server.sh) | Start the server against any workspace — reads MCP from stdin, writes responses to stdout |
| [`scripts/test-mcp.sh`](./scripts/test-mcp.sh) | Smoke-test all tools against `test-workspace/hello-jdtls` |

**Smoke-test all tools at once:**

```bash
# Run from the repo root after 'mvn package -DskipTests'
./scripts/test-mcp.sh
```

The script starts the server, sends the MCP handshake followed by one call for
every tool, and prints the raw JSON-RPC responses to stdout.  Startup takes
around 60 s on first run while Maven imports the project and the JDT index
warms up.

**Start the server manually against any workspace:**

```bash
# Against the bundled test project
./scripts/start-mcp-server.sh

# Against your own project (workspace dir, optional data/metadata dir)
./scripts/start-mcp-server.sh /path/to/my-java-project /tmp/jdtls-data
```

The server then reads MCP messages from stdin and writes responses to stdout.
See [`agent.md`](./agent.md) for a complete self-testing workflow with example
JSON-RPC messages.

#### Wire-protocol quick test (no MCP client required)

The `StdioServerTransportProvider` from the MCP Java SDK uses
**newline-delimited JSON** (NDJSON) — one JSON object per line — **not**
the `Content-Length` framing used by LSP.  You can therefore drive the server
entirely with shell here-docs or a file of JSON lines, or use the
`scripts/test-mcp.sh` script which handles this automatically.

Write MCP messages — one per line — and pipe them in:

```bash
cat > /tmp/mcp-input.txt << 'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
EOF

./scripts/start-mcp-server.sh 2>/dev/null < /tmp/mcp-input.txt
```

> [!TIP]
> The server maps the workspace Maven project on first start; this takes
> roughly 60 s.  Send the messages immediately — the `initialize` reply arrives as
> soon as the transport is ready, and tool responses follow once project import
> and JDT indexing finishes.

#### Example session — annotated server output

Below is a real session against `test-workspace/hello-jdtls/`.

**Input** (`/tmp/mcp-input.txt`) — three NDJSON lines:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

**Response line 1 — `initialize` reply:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "logging": {},
      "tools": { "listChanged": true }
    },
    "serverInfo": { "name": "jdtls-mcp", "version": "1.0.0" }
  }
}
```

**Response line 2 — `tools/list` reply** (formatted for readability):

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "java_hover",
        "description": "Get hover information (Javadoc, type info) for the Java symbol at a given position.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "uri":       { "type": "string",  "description": "Absolute file URI, e.g. file:///path/to/MyClass.java" },
            "line":      { "type": "integer", "description": "0-based line number" },
            "character": { "type": "integer", "description": "0-based character offset" }
          },
          "required": ["uri", "line", "character"]
        }
      },
      {
        "name": "java_definition",
        "description": "Find the definition of the Java symbol (class, method, field) at the given position.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "uri":       { "type": "string",  "description": "Absolute file URI, e.g. file:///path/to/MyClass.java" },
            "line":      { "type": "integer", "description": "0-based line number" },
            "character": { "type": "integer", "description": "0-based character offset" }
          },
          "required": ["uri", "line", "character"]
        }
      },
      {
        "name": "java_references",
        "description": "Find all references to the Java symbol at the given position.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "uri":                { "type": "string",  "description": "Absolute file URI, e.g. file:///path/to/MyClass.java" },
            "line":               { "type": "integer", "description": "0-based line number" },
            "character":          { "type": "integer", "description": "0-based character offset" },
            "includeDeclaration": { "type": "boolean", "description": "Whether to include the declaration itself" }
          },
          "required": ["uri", "line", "character", "includeDeclaration"]
        }
      },
      {
        "name": "java_completion",
        "description": "Get code completion suggestions at the given position in a Java source file.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "uri":       { "type": "string",  "description": "Absolute file URI, e.g. file:///path/to/MyClass.java" },
            "line":      { "type": "integer", "description": "0-based line number" },
            "character": { "type": "integer", "description": "0-based character offset" }
          },
          "required": ["uri", "line", "character"]
        }
      },
      {
        "name": "java_document_symbols",
        "description": "List all symbols (classes, methods, fields) defined in a Java source file.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "uri": { "type": "string", "description": "Absolute file URI, e.g. file:///path/to/MyClass.java" }
          },
          "required": ["uri"]
        }
      },
      {
        "name": "java_workspace_symbols",
        "description": "Search for Java symbols (classes, methods, fields) by name across the workspace.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query, e.g. a class name or method name prefix" }
          },
          "required": ["query"]
        }
      }
    ]
  }
}
```

#### Example tool calls

**`java_workspace_symbols`** — find all classes named `Greeter`:

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"java_workspace_symbols","arguments":{"query":"Greeter"}}}
```

**`java_document_symbols`** — list all symbols in a file:

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"java_document_symbols","arguments":{"uri":"file:///root/github/sunix/jdtls-mcp/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java"}}}
```

**`java_hover`** — get Javadoc at a specific position:

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"java_hover","arguments":{"uri":"file:///root/github/sunix/jdtls-mcp/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java","line":10,"character":18}}}
```

**`java_definition`** — jump to the definition of a symbol:

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"java_definition","arguments":{"uri":"file:///root/github/sunix/jdtls-mcp/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java","line":10,"character":18}}}
```

**`java_references`** — find all usages of a symbol:

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"java_references","arguments":{"uri":"file:///root/github/sunix/jdtls-mcp/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java","line":5,"character":13,"includeDeclaration":true}}}
```

#### Example chat prompts for LLM agents

These prompts work well with GitHub Copilot, Claude Code, or any MCP-aware
LLM when the `jdtls` server is connected:

```
List all the methods and fields defined in
file:///…/test-workspace/hello-jdtls/src/main/java/com/example/Greeter.java
using the java_document_symbols tool.

Show me the Javadoc for the greet() method in Greeter.java using java_hover.

Find all classes in the workspace that contain the word "Counter" using
java_workspace_symbols.

Find every place in the codebase where the `name` field of Greeter is
referenced, using java_references.

Jump to the definition of the Counter class from the call site in Greeter.java
using java_definition.
```

### Adding a new MCP tool

1. Add a method annotated with `@Tool` / `@P` in `JdtlsMcpTools.java`, delegating
   to the appropriate jdtls handler class (see `org.eclipse.jdt.ls.core.internal.handlers`).
2. Register it in `JdtlsMcpTools.registerTools()` using the existing
   `.toolCall(tool(…), this::mcpXxx)` pattern.
3. Test it against `test-workspace/hello-jdtls/`.
4. Commit with `feat(tools): add java_<toolname> MCP tool`.

### Commit conventions

This project uses **Conventional Commits**:

```
<type>(<optional scope>): <short description>
```

| Type       | When to use                                             |
|------------|---------------------------------------------------------|
| `feat`     | New feature or new MCP tool                             |
| `fix`      | Bug fix                                                 |
| `refactor` | Code change without behaviour change                    |
| `test`     | Adding or updating tests                                |
| `docs`     | Documentation only                                      |
| `build`    | Build config changes (pom.xml, target platform, …)      |
| `chore`    | Maintenance (CI, .gitignore, …)                         |

### Key source files

| File | Purpose |
|------|---------|
| `org.eclipse.jdt.ls.mcp/…/McpApplication.java` | Eclipse `IApplication` — initialises jdtls workspace, starts MCP stdio server |
| `org.eclipse.jdt.ls.mcp/…/JdtlsMcpTools.java` | All MCP tool implementations + registration |
| `org.eclipse.jdt.ls.mcp/…/McpServerPlugin.java` | OSGi `BundleActivator` |
| `org.eclipse.jdt.ls.mcp/plugin.xml` | Registers `org.eclipse.jdt.ls.mcp.app` Eclipse application |
| `org.eclipse.jdt.ls.mcp.target/…tp.target` | Target platform: jdtls p2 repo + MCP SDK + langchain4j |
| `org.eclipse.jdt.ls.mcp.product/jdtls-mcp.product` | Lists all OSGi bundles for the packaged product |
| `test-workspace/hello-jdtls/` | Sample Maven project for manual testing |

---

## Technology stack

| Library | Role |
|---------|------|
| [Tycho](https://github.com/eclipse-tycho/tycho) | OSGi / Eclipse plugin build system |
| [eclipse-jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) | Java compiler, handlers, project manager (via p2) |
| [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (`io.modelcontextprotocol.sdk:mcp-core`) | MCP server protocol + stdio transport |
| [langchain4j-core](https://github.com/langchain4j/langchain4j) | `@Tool` / `@P` annotations |
| [LSP4J 0.24.0](https://github.com/eclipse-lsp4j/lsp4j) | LSP types used by jdtls handlers |

## Licence

Apache 2.0
