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
└── org.eclipse.jdt.ls.mcp.product/    # Eclipse product packaging
    ├── pom.xml
    └── jdtls-mcp.product               # All jdtls bundles + org.eclipse.jdt.ls.mcp
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

| Tool | Version |
|------|---------|
| Java | 17+     |
| Maven | 3.9+   |

## Building

```bash
git clone https://github.com/sunix/jdtls-mcp.git
cd jdtls-mcp
mvn package
```

## Running

```bash
java -Declipse.application=org.eclipse.jdt.ls.mcp.app \
     -jar plugins/org.eclipse.equinox.launcher_*.jar \
     -configuration config_linux \
     -data /path/to/your-java-workspace
```

## MCP client configuration

Add this to your MCP client (e.g. Claude Desktop `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jdtls": {
      "command": "java",
      "args": [
        "-Declipse.application=org.eclipse.jdt.ls.mcp.app",
        "-jar", "/path/to/jdtls-mcp/plugins/org.eclipse.equinox.launcher_1.x.x.jar",
        "-configuration", "/path/to/jdtls-mcp/config_linux",
        "-data", "/path/to/your-java-project"
      ]
    }
  }
}
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

Each tool method is also annotated with langchain4j `@Tool`/`@P` so it can be
called directly by a langchain4j agent without the MCP transport layer.

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
