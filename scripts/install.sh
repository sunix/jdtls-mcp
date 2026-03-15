#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# install.sh — Download the latest jdtls-mcp release and install it to a
#              stable, well-known cache location.
#
# Usage:
#   bash <(curl -fsSL https://raw.githubusercontent.com/sunix/jdtls-mcp/main/scripts/install.sh)
#   # or, if you have the repo checked out:
#   ./scripts/install.sh
#
# After running, the server is available at:
#   ~/.cache/jdtls-mcp/current/scripts/start-mcp-server.sh
#
# The "current" symlink always points to the latest installed version, so
# subsequent installs (e.g. in a copilot-setup-steps workflow) transparently
# upgrade to the latest release without changing the MCP config.
#
# All output goes to stderr so that stdout is kept clean when this script is
# used inside a pipeline.
# ---------------------------------------------------------------------------
set -euo pipefail

trap 'echo "ERROR: install.sh exited unexpectedly (line $LINENO, exit $?)" >&2' ERR

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
  echo "       jdtls-mcp-<version>-windows-x86_64.zip from the Releases page." >&2
  exit 1
fi

# ---- Resolve latest release tag --------------------------------------------
echo "Fetching latest release info for $GITHUB_REPO ..." >&2
RELEASE_INFO=$(curl -sSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest") || {
  echo "ERROR: curl failed to reach GitHub API (network unavailable?)" >&2
  exit 1
}
if printf '%s' "$RELEASE_INFO" | grep -q '"message"'; then
  echo "ERROR: GitHub API returned an error:" >&2
  printf '%s\n' "$RELEASE_INFO" >&2
  exit 1
fi
LATEST_TAG=$(printf '%s' "$RELEASE_INFO" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/') || true
if [[ -z "$LATEST_TAG" ]]; then
  echo "ERROR: Could not determine latest release tag from GitHub API." >&2
  printf '%s\n' "$RELEASE_INFO" >&2
  exit 1
fi
echo "Latest release: $LATEST_TAG" >&2

# ---- Cache directory -------------------------------------------------------
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/jdtls-mcp"
VERSIONED_DIR="$CACHE_BASE/$LATEST_TAG/jdtls-mcp"
CURRENT_LINK="$CACHE_BASE/current"

if [[ ! -d "$VERSIONED_DIR" ]]; then
  echo "Downloading jdtls-mcp $LATEST_TAG for $OS-$ARCH ..." >&2
  mkdir -p "$CACHE_BASE/$LATEST_TAG"

  ARCHIVE_NAME="jdtls-mcp-${LATEST_TAG}-${OS}-${ARCH}.tar.gz"
  DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${LATEST_TAG}/${ARCHIVE_NAME}"

  TMP_ARCHIVE=$(mktemp "/tmp/jdtls-mcp-XXXXXX.tar.gz")
  curl -fsSL "$DOWNLOAD_URL" -o "$TMP_ARCHIVE"
  tar -xzf "$TMP_ARCHIVE" -C "$CACHE_BASE/$LATEST_TAG"
  rm -f "$TMP_ARCHIVE"

  echo "Installed to $VERSIONED_DIR" >&2
else
  echo "Using cached installation at $VERSIONED_DIR" >&2
fi

# ---- Update the stable "current" symlink -----------------------------------
ln -sfn "$VERSIONED_DIR" "$CURRENT_LINK"
echo "Symlink updated: $CURRENT_LINK -> $VERSIONED_DIR" >&2
echo "Installation complete. Start with:" >&2
echo "  $CURRENT_LINK/scripts/start-mcp-server.sh [workspace]" >&2
