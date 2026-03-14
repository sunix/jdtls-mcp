#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# download-and-start.sh — Download the latest jdtls-mcp release and start
#                          the MCP server against a Java workspace.
#
# This script is designed for zero-configuration use with MCP clients (e.g.
# GitHub Copilot coding agent) that need to obtain and launch the server
# automatically without a manual installation step.
#
# Usage:
#   ./scripts/download-and-start.sh [workspace] [data-dir]
#
# Arguments:
#   workspace   Java project directory to analyse.
#               Defaults to $GITHUB_WORKSPACE, then $PWD.
#   data-dir    Eclipse workspace metadata directory (indexes, caches, …).
#               Defaults to /tmp/jdtls-mcp-data.
#
# The downloaded binary is cached at ~/.cache/jdtls-mcp/<version>/ so
# subsequent invocations skip the download entirely.
#
# Progress and diagnostics go to stderr; MCP JSON-RPC traffic uses stdout.
# ---------------------------------------------------------------------------
set -euo pipefail

# Trap any unexpected exit and print the failing line number so failures are
# visible in MCP-client verbose logs even when nothing else is printed.
trap 'echo "ERROR: download-and-start.sh exited unexpectedly (line $LINENO, exit $?)" >&2' ERR

GITHUB_REPO="sunix/jdtls-mcp"

# ---- Detect platform -------------------------------------------------------
OS="linux"; ARCH="x86_64"
case "$(uname -s)" in
  Darwin)               OS="macos" ;;
  CYGWIN*|MINGW*|MSYS*) OS="windows" ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ARCH="aarch64" ;;
esac

if [[ "$OS" == "windows" ]]; then
  echo "ERROR: This script requires bash (Linux / macOS). On Windows, download" >&2
  echo "       jdtls-mcp-<version>-windows-x86_64.zip from the Releases page and" >&2
  echo "       run the server manually with 'java -jar ...'." >&2
  exit 1
fi

# ---- Resolve latest release tag --------------------------------------------
echo "Fetching latest release info for $GITHUB_REPO ..." >&2
# Use -sSL (without -f) so HTTP errors don't cause a silent non-zero exit;
# we inspect the response body ourselves for better error messages.
RELEASE_INFO=$(curl -sSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest") || {
  echo "ERROR: curl failed to reach GitHub API (network unavailable?)" >&2
  exit 1
}
# Guard against rate-limit or other API error responses.
if printf '%s' "$RELEASE_INFO" | grep -q '"message"'; then
  echo "ERROR: GitHub API returned an error:" >&2
  printf '%s\n' "$RELEASE_INFO" >&2
  exit 1
fi
# Extract the tag; the || true prevents pipefail from firing on grep no-match.
LATEST_TAG=$(printf '%s' "$RELEASE_INFO" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/') || true
if [[ -z "$LATEST_TAG" ]]; then
  echo "ERROR: Could not determine latest release tag from GitHub API." >&2
  echo "       API response was:" >&2
  printf '%s\n' "$RELEASE_INFO" >&2
  exit 1
fi
echo "Latest release: $LATEST_TAG" >&2

# ---- Cache directory -------------------------------------------------------
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/jdtls-mcp"
INSTALL_DIR="$CACHE_BASE/$LATEST_TAG/jdtls-mcp"

if [[ ! -d "$INSTALL_DIR" ]]; then
  echo "Downloading jdtls-mcp $LATEST_TAG for $OS-$ARCH ..." >&2
  mkdir -p "$CACHE_BASE/$LATEST_TAG"

  ARCHIVE_NAME="jdtls-mcp-${LATEST_TAG}-${OS}-${ARCH}.tar.gz"
  DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${LATEST_TAG}/${ARCHIVE_NAME}"

  TMP_ARCHIVE=$(mktemp "/tmp/jdtls-mcp-XXXXXX.tar.gz")
  curl -fsSL "$DOWNLOAD_URL" -o "$TMP_ARCHIVE"
  tar -xzf "$TMP_ARCHIVE" -C "$CACHE_BASE/$LATEST_TAG"
  rm -f "$TMP_ARCHIVE"

  echo "Installed to $INSTALL_DIR" >&2
else
  echo "Using cached installation at $INSTALL_DIR" >&2
fi

# ---- Resolve workspace and data directory ----------------------------------
# Default workspace: $GITHUB_WORKSPACE (set in GitHub Copilot agent / Actions),
# then fall back to $PWD (useful for local testing).
WORKSPACE="${1:-${GITHUB_WORKSPACE:-$PWD}}"
DATA_DIR="${2:-/tmp/jdtls-mcp-data}"

# ---- Delegate to the bundled start script ----------------------------------
exec "$INSTALL_DIR/scripts/start-mcp-server.sh" "$WORKSPACE" "$DATA_DIR"
