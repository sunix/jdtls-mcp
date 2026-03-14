#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# test-mcp.sh  —  Smoke-test the jdtls-mcp server against hello-jdtls.
#
# Sends the MCP handshake followed by one call for each tool, then prints
# all server responses to stdout.  Runs the server in-process so no prior
# startup is needed.
#
# Usage:
#   ./scripts/test-mcp.sh [data-dir]
#
# Arguments:
#   data-dir   Eclipse workspace data directory for this test run.
#              Defaults to /tmp/jdtls-mcp-test  (wiped on each run).
#
# Requires: java (21+) on PATH, and the product already built with
#           'mvn package -DskipTests'.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- Locate product -------------------------------------------------------
OS="linux" ARCH="x86_64" WS="gtk"
case "$(uname -s)" in Darwin) OS="macosx"; WS="cocoa" ;; CYGWIN*|MINGW*) OS="win32"; WS="win32" ;; esac
case "$(uname -m)" in arm64|aarch64) ARCH="aarch64" ;; esac

PROD_ROOT="$REPO_DIR/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product"
PROD_DIR="$PROD_ROOT/$OS/$WS/$ARCH"

if [[ ! -d "$PROD_DIR" ]]; then
  echo "ERROR: Product directory not found: $PROD_DIR" >&2
  echo "       Run 'mvn package -DskipTests' first." >&2
  exit 1
fi

LAUNCHER="$(ls "$PROD_DIR/plugins/org.eclipse.equinox.launcher_"*.jar 2>/dev/null | head -1)"
if [[ -z "$LAUNCHER" ]]; then
  echo "ERROR: Launcher jar not found in $PROD_DIR/plugins/" >&2
  exit 1
fi

# ---- Paths ----------------------------------------------------------------
WORKSPACE="$REPO_DIR/test-workspace/hello-jdtls"
GREETER="file://$WORKSPACE/src/main/java/com/example/Greeter.java"
DATA_DIR="${1:-/tmp/jdtls-mcp-test}"

echo "=== jdtls-mcp smoke test ===" >&2
echo "workspace : $WORKSPACE" >&2
echo "data dir  : $DATA_DIR" >&2
echo "" >&2

rm -rf "$DATA_DIR" && mkdir -p "$DATA_DIR"

# ---- FIFO for stdin -------------------------------------------------------
# We use a named FIFO so we can keep stdin open while the server is running.
# All messages are written up-front; the trailing 'sleep' keeps the pipe
# open so the server does not see EOF before it has processed everything.
FIFO="$(mktemp -u /tmp/jdtls-mcp-test-XXXXXX.fifo)"
mkfifo "$FIFO"

# ---- MCP message sequence -------------------------------------------------
{
  # Handshake
  printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-mcp.sh","version":"1"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'

  # List available tools
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

  # java_workspace_symbols — find all types matching "Greeter"
  printf '%s\n' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"java_workspace_symbols","arguments":{"query":"Greeter"}}}'

  # java_document_symbols — list all symbols in Greeter.java
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"java_document_symbols\",\"arguments\":{\"uri\":\"$GREETER\"}}}"

  # java_hover — Javadoc for greet() at line 33:18
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"java_hover\",\"arguments\":{\"uri\":\"$GREETER\",\"line\":33,\"character\":18}}}"

  # java_definition — jump to definition of Greeter class at line 43:8
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"java_definition\",\"arguments\":{\"uri\":\"$GREETER\",\"line\":43,\"character\":8}}}"

  # java_references — all references to the 'name' field declared at line 17:25
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"java_references\",\"arguments\":{\"uri\":\"$GREETER\",\"line\":17,\"character\":25,\"includeDeclaration\":true}}}"

  # java_diagnostics — check for compilation errors in Greeter.java
  printf '%s\n' "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"java_diagnostics\",\"arguments\":{\"uri\":\"$GREETER\"}}}"

  # java_diagnostics — check for compilation errors across the whole workspace
  printf '%s\n' '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"java_diagnostics","arguments":{}}}'

  # Keep stdin open until server exits or timeout
  sleep 300
} > "$FIFO" &
WRITER_PID=$!

cleanup() {
  kill "$WRITER_PID" 2>/dev/null || true
  rm -f "$FIFO"
}
trap cleanup EXIT

# ---- Run server -----------------------------------------------------------
# stdout → test output, stderr → terminal (startup noise)
java \
  -Declipse.application=org.eclipse.jdt.ls.mcp.app \
  -Dosgi.bundles.defaultStartLevel=4 \
  -Djdtls.workspace.root="$WORKSPACE" \
  -jar "$LAUNCHER" \
  -configuration "$PROD_DIR/configuration" \
  -data "$DATA_DIR" \
  < "$FIFO"
