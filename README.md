# jdtls-mcp

> Expose the Eclipse JDT Language Server to LLM agents via the Model Context Protocol (MCP)

## Overview

`jdtls-mcp` bridges two open protocols:

| Protocol | Purpose |
|----------|---------|
| [Language Server Protocol (LSP)](https://microsoft.github.io/language-server-protocol/) | Java language intelligence from [eclipse-jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) |
| [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) | Standardised tool interface for LLM agents |

```
┌──────────────────────┐          ┌───────────────────────┐          ┌─────────────┐
│  LLM Agent / MCP     │          │   jdtls-mcp-server    │          │   jdtls     │
│  Client              │◄─ MCP ──►│  (this project)       │◄─ LSP ──►│  (Eclipse   │
│  (Claude, GPT, etc.) │  stdio   │  langchain4j + MCP SDK│  stdio   │   App)      │
└──────────────────────┘          └───────────────────────┘          └─────────────┘
```

LLM agents can call Java language tools such as hover, go-to-definition, find-references, diagnostics, and more — all backed by a real Java compiler.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 17+ | Must be on `PATH` |
| [jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls/releases) | latest | Download and extract |
| Maven | 3.9+ | For building this project |

## Building

```bash
git clone https://github.com/sunix/jdtls-mcp.git
cd jdtls-mcp
mvn package -DskipTests
```

The fat jar is produced at `jdtls-mcp-server/target/jdtls-mcp-server-1.0-SNAPSHOT.jar`.

## Running

```bash
java -jar jdtls-mcp-server/target/jdtls-mcp-server-1.0-SNAPSHOT.jar \
  --jdtls-home /path/to/jdtls \
  --workspace  /path/to/your-java-project
```

### Configuration via environment variables

```bash
export JDTLS_HOME=/path/to/jdtls
export JDTLS_WORKSPACE=/path/to/your-java-project
java -jar jdtls-mcp-server/target/jdtls-mcp-server-1.0-SNAPSHOT.jar
```

### Download jdtls

```bash
# Example: download jdtls milestone release
JDTLS_VERSION=1.40.0
curl -L "https://download.eclipse.org/jdtls/milestones/${JDTLS_VERSION}/jdt-language-server-${JDTLS_VERSION}-202412191447.tar.gz" \
  | tar -xz -C /opt/jdtls
export JDTLS_HOME=/opt/jdtls
```

## MCP Client Configuration

Add this server to your MCP client configuration (e.g. Claude Desktop `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jdtls": {
      "command": "java",
      "args": [
        "-jar", "/path/to/jdtls-mcp-server-1.0-SNAPSHOT.jar",
        "--jdtls-home", "/path/to/jdtls",
        "--workspace",  "/path/to/your-java-project"
      ]
    }
  }
}
```

## Available MCP Tools

| Tool | Description |
|------|-------------|
| `java_open_file` | Open a Java source file for analysis |
| `java_update_file` | Update the content of an already-open file |
| `java_hover` | Get hover information (Javadoc, type info) at a position |
| `java_definition` | Find the definition of a symbol |
| `java_references` | Find all references to a symbol |
| `java_diagnostics` | Get compiler errors and warnings for a file |
| `java_completion` | Get code completion suggestions |
| `java_document_symbols` | List all symbols in a file |
| `java_workspace_symbols` | Search for symbols across the workspace |

### Position parameters

All position-based tools use **0-based** line and character offsets, matching the LSP convention.

## Architecture

```
jdtls-mcp/
├── pom.xml                        # Parent Maven POM
└── jdtls-mcp-server/
    ├── pom.xml
    └── src/main/java/io/github/sunix/jdtls/mcp/
        ├── JdtlsMcpServer.java         # Entry point; wires MCP server + jdtls
        ├── JdtlsProcess.java           # Manages jdtls subprocess lifecycle
        ├── JdtlsLanguageClient.java    # LSP4J client; communicates with jdtls
        └── JavaLanguageServerTools.java # Tool definitions (@Tool) + MCP registration
```

### Technology stack

| Library | Role |
|---------|------|
| [langchain4j-core](https://github.com/langchain4j/langchain4j) | `@Tool` / `@P` annotations for tool definitions |
| [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (`io.modelcontextprotocol.sdk:mcp-core`) | MCP server protocol and stdio transport |
| [LSP4J](https://github.com/eclipse-lsp4j/lsp4j) | LSP types and launcher for jdtls communication |
| Logback | Logging to **stderr** (stdout is reserved for MCP messages) |

## Development

```bash
# Compile
mvn compile

# Run tests
mvn test

# Build fat jar
mvn package
```

## Licence

Apache 2.0
