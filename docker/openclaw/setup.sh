#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Dvergr + OpenClaw integration test setup
#
# Prerequisites:
#   - Docker with compose plugin
#   - OpenClaw source at ../openclaw (sibling directory)
#   - FIREWORKS_API_KEY environment variable set
#
# Usage:
#   ./docker/openclaw/setup.sh          # Build images + inject config
#   ./docker/openclaw/setup.sh --reset  # Reset config volume and re-inject
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DVERGR_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OPENCLAW_DIR="${OPENCLAW_DIR:-$(cd "$DVERGR_DIR/../openclaw" && pwd)}"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

# Validate prerequisites
if ! docker compose version >/dev/null 2>&1; then
  echo "ERROR: Docker Compose not available" >&2
  exit 1
fi

if [[ ! -d "$OPENCLAW_DIR" ]]; then
  echo "ERROR: OpenClaw source not found at $OPENCLAW_DIR" >&2
  echo "Set OPENCLAW_DIR env var to point to openclaw repo" >&2
  exit 1
fi

if [[ -z "${FIREWORKS_API_KEY:-}" ]]; then
  echo "ERROR: FIREWORKS_API_KEY environment variable not set" >&2
  echo "Get a key from https://fireworks.ai" >&2
  exit 1
fi

# Generate gateway token if not set
if [[ -z "${OPENCLAW_GATEWAY_TOKEN:-}" ]]; then
  OPENCLAW_GATEWAY_TOKEN="$(openssl rand -hex 32)"
  echo "Generated gateway token: $OPENCLAW_GATEWAY_TOKEN"
fi
export OPENCLAW_GATEWAY_TOKEN
export FIREWORKS_API_KEY

# Write .env file for docker-compose
ENV_FILE="$SCRIPT_DIR/.env"
cat > "$ENV_FILE" <<EOF
OPENCLAW_GATEWAY_TOKEN=$OPENCLAW_GATEWAY_TOKEN
FIREWORKS_API_KEY=$FIREWORKS_API_KEY
OPENCLAW_GATEWAY_PORT=${OPENCLAW_GATEWAY_PORT:-18789}
DVERGR_MCP_JAR=${DVERGR_MCP_JAR:-$DVERGR_DIR/target/dvergr-mcp.jar}
EOF

echo "==> Step 1: Building OpenClaw base image"
docker build -t openclaw:local -f "$OPENCLAW_DIR/Dockerfile" "$OPENCLAW_DIR"

echo ""
echo "==> Step 2: Building dvergr-openclaw image (adds Java)"
docker build -t dvergr-openclaw:dev -f "$SCRIPT_DIR/Dockerfile" "$DVERGR_DIR"

echo ""
echo "==> Step 3: Injecting OpenClaw configuration"

# Reset config volume if requested
if [[ "${1:-}" == "--reset" ]]; then
  echo "Resetting openclaw config volume..."
  docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
fi

# Create config volume and inject openclaw.json5
# We use a temporary container to write into the named volume
docker compose -f "$COMPOSE_FILE" run --rm --no-deps \
  -v "$SCRIPT_DIR/openclaw.json5:/tmp/openclaw.json5:ro" \
  --entrypoint /bin/bash \
  openclaw-cli -c '
    mkdir -p /home/node/.openclaw
    cp /tmp/openclaw.json5 /home/node/.openclaw/openclaw.json5
    echo "Config injected successfully"
    ls -la /home/node/.openclaw/
  '

echo ""
echo "==> Setup complete!"
echo ""
echo "Environment:"
echo "  Gateway token: $OPENCLAW_GATEWAY_TOKEN"
echo "  Gateway port:  ${OPENCLAW_GATEWAY_PORT:-18789}"
echo "  Fireworks key: ${FIREWORKS_API_KEY:0:8}..."
echo ""
echo "Next steps:"
echo "  1. Build dvergr-mcp uberjar:"
echo "     cd $DVERGR_DIR && clj -T:build uber"
echo ""
echo "  2. Start OpenClaw gateway:"
echo "     docker compose -f $COMPOSE_FILE up -d openclaw-gateway"
echo ""
echo "  3. Check health:"
echo "     docker compose -f $COMPOSE_FILE exec openclaw-gateway \\"
echo "       node dist/index.js health --token \"$OPENCLAW_GATEWAY_TOKEN\""
echo ""
echo "  4. Send a test message (uses dvergr MCP tools):"
echo "     docker compose -f $COMPOSE_FILE run --rm openclaw-cli \\"
echo "       agent \"Use the dvergr runtime_list tool to list available runtimes\""
echo ""
echo "  5. View logs:"
echo "     docker compose -f $COMPOSE_FILE logs -f openclaw-gateway"
echo ""
echo "  6. Stop:"
echo "     docker compose -f $COMPOSE_FILE down"
