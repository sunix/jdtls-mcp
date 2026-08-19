#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# start-mcp-server.sh  —  Launch the jdtls-mcp server against a workspace.
#
# Usage:
#   ./scripts/start-mcp-server.sh [/path/to/workspace] [/path/to/data-dir]
#
# Arguments:
#   workspace   Directory that contains the Java project(s) to analyse.
#               Defaults to test-workspace/hello-jdtls inside this repo.
#   data-dir    Eclipse workspace data directory (metadata, indexes, …).
#               Defaults to /tmp/jdtls-mcp-data.
#
# The server reads MCP JSON-RPC messages from stdin (one JSON object per
# line, no Content-Length framing) and writes responses to stdout.
# Startup takes ~60 s on first run while Maven / JDT index sources.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- Locate the product directory -----------------------------------------
OS="linux" ARCH="x86_64" WS="gtk"
case "$(uname -s)" in Darwin) OS="macosx"; WS="cocoa" ;; CYGWIN*|MINGW*) OS="win32"; WS="win32" ;; esac
case "$(uname -m)" in arm64|aarch64) ARCH="aarch64" ;; esac

# Support two layouts:
#  1. Release archive: plugins/ and configuration/ live directly under REPO_DIR
#  2. Development build: Tycho output under org.eclipse.jdt.ls.mcp.product/target/...
if [[ -d "$REPO_DIR/plugins" ]]; then
  PROD_DIR="$REPO_DIR"
else
  PROD_ROOT="$REPO_DIR/org.eclipse.jdt.ls.mcp.product/target/products/jdtls-mcp.product"
  PROD_DIR="$PROD_ROOT/$OS/$WS/$ARCH"
fi

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

# ---- Locate Java 21+ -------------------------------------------------------
# On GitHub-hosted runners JAVA_HOME_21_X64 points to the pre-installed
# Temurin 21 even when the default 'java' on PATH is an older version.
# Prefer it when available; fall back to whatever 'java' is on PATH.
JAVA_CMD="java"
for _jh in "${JAVA_HOME_21_X64:-}" "${JAVA_HOME_21_ARM64:-}" "${JAVA_HOME:-}"; do
  if [[ -n "$_jh" && -x "$_jh/bin/java" ]]; then
    JAVA_CMD="$_jh/bin/java"
    break
  fi
done
echo "  java      : $JAVA_CMD" >&2

# ---- Verify Java 21+ --------------------------------------------------------
# Without this check, a stale default `java` on PATH (with none of the
# JAVA_HOME* vars above set) fails deep inside the OSGi launcher with a much
# less helpful error than a clear version mismatch message.
JAVA_MAJOR="$("$JAVA_CMD" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 21 ]]; then
  echo "ERROR: jdtls-mcp requires Java 21+, found ${JAVA_MAJOR:-an unrecognised version} at $JAVA_CMD" >&2
  echo "       Set JAVA_HOME (or JAVA_HOME_21_X64 / JAVA_HOME_21_ARM64) to a Java 21+" >&2
  echo "       install and re-run — e.g. 'sdk use java 21' if using SDKMAN." >&2
  exit 1
fi

# ---- Arguments ------------------------------------------------------------
WORKSPACE="${1:-$REPO_DIR/test-workspace/hello-jdtls}"
DATA_DIR="${2:-/tmp/jdtls-mcp-data}"

echo "Starting jdtls-mcp server" >&2
echo "  workspace : $WORKSPACE" >&2
echo "  data dir  : $DATA_DIR" >&2
echo "  launcher  : $LAUNCHER" >&2
echo "" >&2

mkdir -p "$DATA_DIR"

exec "$JAVA_CMD" \
  -Declipse.application=org.eclipse.jdt.ls.mcp.app \
  -Dosgi.bundles.defaultStartLevel=4 \
  -Djdtls.workspace.root="$WORKSPACE" \
  -jar "$LAUNCHER" \
  -configuration "$PROD_DIR/configuration" \
  -data "$DATA_DIR"
