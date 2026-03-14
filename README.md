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

## Installation

### Option A — Download a release (recommended)

Download the archive for your platform from the
[**Releases page**](https://github.com/sunix/jdtls-mcp/releases/latest),
extract it, and you're ready to go — no build step required.

```bash
# Linux x86_64
curl -L https://github.com/sunix/jdtls-mcp/releases/latest/download/jdtls-mcp-linux-x86_64.tar.gz \
  | tar xz
cd jdtls-mcp

# macOS arm64 (Apple Silicon)
curl -L https://github.com/sunix/jdtls-mcp/releases/latest/download/jdtls-mcp-macos-aarch64.tar.gz \
  | tar xz
cd jdtls-mcp
```

The extracted directory contains `plugins/`, `configuration/`, and `scripts/`.

### Option B — Build from source

Requires **Maven 3.9+** in addition to Java 21.

```bash
git clone https://github.com/sunix/jdtls-mcp.git
cd jdtls-mcp
mvn package -DskipTests
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

## Running

Use the bundled `scripts/start-mcp-server.sh` (included in release archives
and in the repo):

```bash
# Point at your Java workspace
./scripts/start-mcp-server.sh /path/to/your-java-project

# Optional second argument: Eclipse metadata/index directory
./scripts/start-mcp-server.sh /path/to/your-java-project /tmp/jdtls-data
```

The script auto-detects OS and CPU architecture.  The server reads MCP messages
from stdin and writes JSON-RPC responses to stdout.

> [!NOTE]
> Startup takes **~60 s** on the first run while Maven imports the project and
> the JDT type-name index warms up.  Subsequent starts against the same data
> directory are faster.

## Available MCP tools

| Tool | Description |
|------|-------------|
| `java_hover` | Get hover information (Javadoc, type info) at a position |
| `java_definition` | Find the definition of a symbol |
| `java_references` | Find all references to a symbol |
| `java_completion` | Get code completion suggestions |
| `java_document_symbols` | List all symbols in a file |
| `java_workspace_symbols` | Search for symbols across the workspace |
| `java_diagnostics` | Get compilation errors and warnings for a file or workspace |

All position-based tools use **0-based** line and character offsets (LSP convention).

---

## GitHub Copilot Coding Agent (zero-install configuration)

You can enable `jdtls-mcp` for any Java repository on GitHub by pasting a
single JSON snippet into your **GitHub Copilot settings** → **Model Context
Protocol (MCP)**.  No manual download or installation is required — the
configuration uses a bootstrap script that auto-downloads the correct release
binary for the agent's platform and caches it for subsequent runs.

### One-time setup

1. Go to **GitHub Settings → Copilot → Model Context Protocol (MCP)** (or your
   organisation's equivalent settings page).

2. Paste the following JSON into the **MCP configuration** field and save:

   ```json
   {
     "mcpServers": {
       "jdtls": {
         "type": "stdio",
         "command": "bash",
         "args": [
           "-c",
           "f=$(mktemp /tmp/jdtls-mcp-bootstrap-XXXXXX.sh) && curl -fsSL https://raw.githubusercontent.com/sunix/jdtls-mcp/main/scripts/download-and-start.sh -o \"$f\" && chmod +x \"$f\" && exec \"$f\""
         ],
         "tools": [
           "java_hover",
           "java_definition",
           "java_references",
           "java_completion",
           "java_document_symbols",
           "java_workspace_symbols"
         ]
       }
     }
   }
   ```

   **What this does:**
   - Downloads `scripts/download-and-start.sh` from this repository to a
     temporary file (so stdin remains connected to the MCP client, not to `curl`).
   - The script auto-detects the agent OS and CPU architecture.
   - Downloads the latest `jdtls-mcp` release archive from GitHub and caches it
     at `~/.cache/jdtls-mcp/<version>/`.
   - Starts the MCP server against `$GITHUB_WORKSPACE` (the repository root
     automatically provided by the Copilot agent environment).

3. That's it — the next time you open a Copilot coding agent session on **any**
   Java repository, the `jdtls` MCP server will start automatically.

> [!NOTE]
> The first run downloads ~53 MB and takes **~60–90 s** while Maven imports the
> project and the JDT index warms up.  Subsequent runs in the same agent
> environment reuse the cached binary and are faster.

> [!TIP]
> The bootstrap command fetches `download-and-start.sh` from the `main` branch
> each time, which always gives you the latest script logic.  If you prefer to
> pin to a specific release, replace `main` in the URL with a tag such as `v1.0.0`.

---

## Testing with MCP clients

The repository includes a ready-to-use sample Java project at
`test-workspace/hello-jdtls/` — use it as the jdtls workspace when trying
any of the clients below.

```bash
export WORKSPACE="$PWD/test-workspace/hello-jdtls"
```

The examples below assume you have either downloaded a release archive and
extracted it to `~/jdtls-mcp/`, or cloned and built the repo.  Adjust
`JDTLS_MCP_DIR` to wherever `scripts/start-mcp-server.sh` lives:

```bash
# Downloaded release (example)
export JDTLS_MCP_DIR="$HOME/jdtls-mcp"

# Built from source
export JDTLS_MCP_DIR="$PWD"   # repo root
```

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
         "command": "$JDTLS_MCP_DIR/scripts/start-mcp-server.sh",
         "args": ["$WORKSPACE"]
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
   claude mcp add jdtls -- "$JDTLS_MCP_DIR/scripts/start-mcp-server.sh" "$WORKSPACE"
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
         command: "$JDTLS_MCP_DIR/scripts/start-mcp-server.sh"
         args:
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

> **TL;DR** — Java 21, Maven 3.9+, then `mvn package -DskipTests`.
> All commits must follow [Conventional Commits](https://www.conventionalcommits.org/).

### Prerequisites

| Tool  | Version | Notes                           |
|-------|---------|---------------------------------|
| Java  | 21+     | Must be on `PATH`               |
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

### Releasing

Releases are fully automated via the **Release** GitHub Actions workflow
(`.github/workflows/release.yml`). You never need to edit version numbers
manually or push a tag yourself.

**What the workflow does automatically:**
1. Runs `tycho-versions:set-version` to update all version strings atomically —
   `pom.xml` files, `MANIFEST.MF` (`Bundle-Version`), and `jdtls-mcp.product`.
2. Commits the version bump and creates the `v<version>` git tag.
3. Builds the product for all 5 platforms with Maven / Tycho.
4. Packages per-platform archives and publishes a GitHub Release with them.
5. Bumps versions to the next `-SNAPSHOT`, commits, and pushes everything
   (tag + both commits) back to `main`.

**To cut a release:**

1. Go to **Actions → Release → Run workflow** on GitHub.
2. Fill in **Release version** (e.g. `1.0.0`).
   Optionally fill in **Next development version** (e.g. `1.0.1-SNAPSHOT`).
   Leave it blank to auto-increment the patch segment.
3. Click **Run workflow** — that's it.

After the workflow completes:
- A GitHub Release tagged `v1.0.0` appears with the 5 platform archives.
- `main` has two new commits: the release version bump and the snapshot bump.

> **Branch protection note:** if `main` has push restrictions, grant the
> `github-actions[bot]` the **bypass** permission, or use a PAT stored as
> `GH_RELEASE_TOKEN` and reference it in the workflow's `token:` field
> instead of `secrets.GITHUB_TOKEN`.

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

## Related projects

### [sunix/java-lsp-mcp-server](https://github.com/sunix/java-lsp-mcp-server)

A parallel experiment tackling the same goal — exposing jdtls to AI agents via
MCP — but with a fundamentally different architecture.

| Aspect | **jdtls-mcp** (this project) | **java-lsp-mcp-server** |
|---|---|---|
| Architecture | *Embedded* — runs **as** an Eclipse OSGi product | *External controller* — runs jdtls as a managed subprocess |
| MCP transport | **stdio** (NDJSON, one JSON object per line) | **HTTP / SSE** |
| jdtls version | Fixed at build time via the Tycho target platform | Auto-downloaded at runtime (configurable URL or latest) |
| Distribution | Pre-built platform archives (~63 MB each) | Standard Quarkus JAR or GraalVM native binary |
| Workspace | Fixed at server startup (CLI argument) | Changed at runtime via `initializeWorkspace()` tool |
| Java required | Java 21 | Java 25 |
| Framework | Tycho / OSGi | [Quarkus](https://quarkus.io/) + [quarkus-mcp-server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) |
| LSP tools | hover, document symbols, references, workspace symbols, definition, **diagnostics** | document symbols, completions, diagnostics, format, definition |
| Lifecycle tools | None (transparent to the LLM) | `startJdtls`, `stopJdtls`, `checkJdtls`, `installJdtls`, `initializeWorkspace` |

**When to use which:**

- **jdtls-mcp** — zero operational overhead for the LLM agent; the server is
  ready when the process starts. Good fit for stdio-only MCP clients and
  fixed-workspace setups.
- **java-lsp-mcp-server** — better for HTTP-based MCP clients, multi-workspace
  scenarios, or environments where you want the agent to control the jdtls
  lifecycle directly. The auto-download removes the need to ship a pre-built
  product per platform.

### Improvements this project could borrow

Looking at what `java-lsp-mcp-server` has that this project currently lacks:

| Missing tool | LSP request | What it enables |
|---|---|---|
| `java_completions` | `textDocument/completion` | Let the agent request completions at a position — useful for code generation workflows |
| `java_format` | `textDocument/formatting` + `textDocument/didChange` | Normalise generated code before committing |

Both are available through the jdtls handlers already on the classpath
(`CompletionHandler`, `FormattingHandler`), so
they can be added to `JdtlsMcpTools.java` following the same pattern as the
existing tools.

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
