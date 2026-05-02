#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Dvergr + OpenClaw development helper
#
# Commands:
#   build        Build Docker images (OpenClaw base + Java layer)
#   jar          Rebuild dvergr-mcp uberjar
#   up           Start gateway (builds jar first if missing)
#   down         Stop everything
#   restart      Restart gateway (picks up new jar + config)
#   test [msg]   Run an agent task (default: list dvergr tools)
#   shell        Interactive shell inside the container
#   exec <cmd>   Run a command inside the running gateway container
#   logs         Tail gateway logs
#   config       Edit openclaw.json5 (prints path)
#   status       Show container status and config summary
#   reset        Stop, remove volumes, rebuild everything
#
# Environment:
#   OPENCLAW_DIR       Path to openclaw source (default: ../openclaw)
#   FIREWORKS_API_KEY  Required for LLM API calls
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DVERGR_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OPENCLAW_DIR="${OPENCLAW_DIR:-$(cd "$DVERGR_DIR/../openclaw" 2>/dev/null && pwd || echo "")}"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"
CONFIG_DIR="$SCRIPT_DIR/config"
ENV_FILE="$SCRIPT_DIR/.env"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

ensure_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "No .env file found. Creating one..."

    if [[ -z "${FIREWORKS_API_KEY:-}" ]]; then
      echo "ERROR: FIREWORKS_API_KEY not set" >&2
      echo "  export FIREWORKS_API_KEY=your-key-here" >&2
      exit 1
    fi

    local token="${OPENCLAW_GATEWAY_TOKEN:-$(openssl rand -hex 32)}"
    cat > "$ENV_FILE" <<EOF
OPENCLAW_GATEWAY_TOKEN=$token
FIREWORKS_API_KEY=$FIREWORKS_API_KEY
OPENCLAW_GATEWAY_PORT=${OPENCLAW_GATEWAY_PORT:-18789}
DVERGR_MCP_JAR=$DVERGR_DIR/target/dvergr-mcp.jar
EOF
    echo "Created $ENV_FILE"
    echo "Gateway token: $token"
  fi
}

ensure_config() {
  if [[ ! -f "$CONFIG_DIR/openclaw.json5" ]]; then
    mkdir -p "$CONFIG_DIR"
    cp "$SCRIPT_DIR/openclaw.json5" "$CONFIG_DIR/openclaw.json5"
    echo "Initialized config at $CONFIG_DIR/openclaw.json5"
  fi
}

ensure_dirs() {
  mkdir -p "$SCRIPT_DIR/workspace" "$SCRIPT_DIR/data" "$CONFIG_DIR"
}

dc() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

cmd_build() {
  if [[ -z "$OPENCLAW_DIR" || ! -d "$OPENCLAW_DIR" ]]; then
    echo "ERROR: OpenClaw source not found." >&2
    echo "Set OPENCLAW_DIR or place openclaw repo at $DVERGR_DIR/../openclaw" >&2
    exit 1
  fi

  echo "==> Building OpenClaw base image..."
  docker build -t openclaw:local \
    --build-arg OPENCLAW_DOCKER_APT_PACKAGES="openjdk-21-jre-headless" \
    -f "$OPENCLAW_DIR/Dockerfile" "$OPENCLAW_DIR"

  echo ""
  echo "==> Built openclaw:local (with Java 21 JRE)"
  echo ""
  echo "Tagging as dvergr-openclaw:dev..."
  docker tag openclaw:local dvergr-openclaw:dev
}

cmd_jar() {
  echo "==> Building dvergr-mcp uberjar..."
  (cd "$DVERGR_DIR" && clj -T:build uber)
  echo "Built: $DVERGR_DIR/target/dvergr-mcp.jar"
}

cmd_up() {
  ensure_env
  ensure_config
  ensure_dirs

  # Build jar if missing
  if [[ ! -f "$DVERGR_DIR/target/dvergr-mcp.jar" ]]; then
    echo "dvergr-mcp.jar not found, building..."
    cmd_jar
  fi

  echo "==> Starting OpenClaw gateway..."
  dc up -d openclaw-gateway
  echo ""
  echo "Gateway running on port $(grep OPENCLAW_GATEWAY_PORT "$ENV_FILE" | cut -d= -f2)"
  echo "Logs: $0 logs"
}

cmd_down() {
  ensure_env
  dc down
}

cmd_restart() {
  ensure_env
  dc restart openclaw-gateway
  echo "Restarted. New jar and config changes picked up."
}

cmd_test() {
  ensure_env
  ensure_config
  ensure_dirs
  local msg="${1:-Use the dvergr runtime_list tool to list available runtimes}"
  echo "==> Running agent task: $msg"
  dc run --rm openclaw-cli agent "$msg"
}

cmd_shell() {
  ensure_env
  echo "==> Opening shell in gateway container..."
  dc exec openclaw-gateway /bin/bash || \
    dc run --rm --entrypoint /bin/bash openclaw-cli
}

cmd_exec() {
  ensure_env
  dc exec openclaw-gateway "$@"
}

cmd_logs() {
  ensure_env
  dc logs -f openclaw-gateway
}

cmd_config() {
  ensure_config
  echo "Config file: $CONFIG_DIR/openclaw.json5"
  echo ""
  echo "Edit it, then run: $0 restart"
}

cmd_status() {
  ensure_env
  echo "=== Dvergr + OpenClaw Dev Status ==="
  echo ""
  echo "Config:    $CONFIG_DIR/openclaw.json5"
  echo "Workspace: $SCRIPT_DIR/workspace/"
  echo "Data:      $SCRIPT_DIR/data/"
  echo "Jar:       $DVERGR_DIR/target/dvergr-mcp.jar"
  if [[ -f "$DVERGR_DIR/target/dvergr-mcp.jar" ]]; then
    echo "  Size: $(du -h "$DVERGR_DIR/target/dvergr-mcp.jar" | cut -f1)"
    echo "  Modified: $(stat -c %y "$DVERGR_DIR/target/dvergr-mcp.jar" 2>/dev/null | cut -d. -f1)"
  else
    echo "  (not built yet - run: $0 jar)"
  fi
  echo ""
  dc ps 2>/dev/null || echo "(containers not running)"
}

cmd_reset() {
  echo "==> Stopping containers and removing data..."
  ensure_env
  dc down -v 2>/dev/null || true
  rm -rf "$SCRIPT_DIR/workspace" "$SCRIPT_DIR/data" "$CONFIG_DIR"
  rm -f "$ENV_FILE"
  echo "Reset complete. Run '$0 build && $0 up' to start fresh."
}

# ---------------------------------------------------------------------------
# Dispatch
# ---------------------------------------------------------------------------

case "${1:-help}" in
  build)   cmd_build ;;
  jar)     cmd_jar ;;
  up)      cmd_up ;;
  down)    cmd_down ;;
  restart) cmd_restart ;;
  test)    shift; cmd_test "$@" ;;
  shell)   cmd_shell ;;
  exec)    shift; cmd_exec "$@" ;;
  logs)    cmd_logs ;;
  config)  cmd_config ;;
  status)  cmd_status ;;
  reset)   cmd_reset ;;
  help|*)
    echo "Usage: $0 <command>"
    echo ""
    echo "Commands:"
    echo "  build        Build Docker images (first time)"
    echo "  jar          Rebuild dvergr-mcp uberjar"
    echo "  up           Start gateway"
    echo "  down         Stop everything"
    echo "  restart      Restart gateway (pick up jar/config changes)"
    echo "  test [msg]   Run an agent task"
    echo "  shell        Shell into container"
    echo "  exec <cmd>   Run command in gateway container"
    echo "  logs         Tail gateway logs"
    echo "  config       Show config file path"
    echo "  status       Show dev environment status"
    echo "  reset        Full reset (removes all data)"
    ;;
esac
