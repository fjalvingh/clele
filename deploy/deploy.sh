#!/usr/bin/env bash
#
# Build Clele and deploy it to a server as a systemd service, served at the host root.
#
#   1. builds the jar locally
#   2. ships the jar + systemd unit + env template to the server over ssh/scp
#   3. (re)starts the clele service
#
# Configure the target below or via environment variables, e.g.:
#   DEPLOY_HOST=myserver.example.com DEPLOY_USER=deploy ./deploy/deploy.sh
#
set -euo pipefail

# ---- configuration (override via env) --------------------------------------
DEPLOY_USER="${DEPLOY_USER:-jal}"                       # ssh login user (needs sudo on the server)
DEPLOY_HOST="${DEPLOY_HOST:-pigalle.qd.ax}"               # ssh host, e.g. myserver.example.com
SSH_PORT="${SSH_PORT:-22}"
DEPLOY_DIR="${DEPLOY_DIR:-/env/clele}"               # where the jar lives on the server
ENV_DIR="${ENV_DIR:-/etc/clele}"                     # where clele.env lives on the server
SERVICE_USER="${SERVICE_USER:-clele}"               # dedicated system user the service runs as
SERVICE_NAME="${SERVICE_NAME:-clele}"
JAVA_BIN="${JAVA_BIN:-/opt/java/21/bin/java}"        # must match ExecStart in clele.service
JAR="${JAR:-parts-0.0.1-SNAPSHOT.jar}"
BASE_PATH="${BASE_PATH:-/}"                          # Vite base (trailing slash) = the subpath ('/' = root)
# ----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "$DEPLOY_HOST" || -z "$DEPLOY_USER" ]]; then
  echo "ERROR: set DEPLOY_HOST and DEPLOY_USER (env vars or edit the top of this script)." >&2
  echo "  e.g. DEPLOY_HOST=myserver.example.com DEPLOY_USER=deploy $0" >&2
  exit 2
fi

TARGET="${DEPLOY_USER}@${DEPLOY_HOST}"
SSH="ssh -p ${SSH_PORT} ${TARGET}"
echo "==> Deploying to ${TARGET} (dir ${DEPLOY_DIR}, service ${SERVICE_NAME})"

# 1. Build the jar (VITE_BASE bakes the subpath, if any, into the frontend bundle).
echo "==> Building (VITE_BASE=${BASE_PATH}) ..."
( cd "$REPO_DIR/backend" && VITE_BASE="$BASE_PATH" mvn21 clean package )

JAR_PATH="$REPO_DIR/backend/target/$JAR"
[[ -f "$JAR_PATH" ]] || { echo "ERROR: built jar not found at $JAR_PATH" >&2; exit 3; }

# 3. Ship the artifacts to a staging dir, then move into place with sudo.
echo "==> Uploading artifacts ..."
scp -P "$SSH_PORT" "$JAR_PATH"                  "$TARGET:/tmp/$JAR"
scp -P "$SSH_PORT" "$SCRIPT_DIR/clele.env.example" "$TARGET:/tmp/clele.env.example"

echo "==> Installing & restarting ..."
$SSH "sudo bash -s" <<EOF
set -euo pipefail
sudo install -o "${SERVICE_USER}" -g "${SERVICE_USER}" -m 644 "/tmp/$JAR" "${DEPLOY_DIR}/$JAR"
sudo systemctl daemon-reload
sudo systemctl restart ${SERVICE_NAME}
sleep 2
sudo systemctl --no-pager --full status ${SERVICE_NAME} || true

# The port comes from the service's own EnvironmentFile — the one place that decides it — so this
# check cannot drift from what the app actually binds. Unset there means the app default (8080).
# tr -cd keeps only digits, so a quoted value ("8084"), stray spaces or a CRLF line ending all
# still yield a usable port.
PORT=\$(sudo sed -n 's/^SERVER_PORT=//p' "${ENV_DIR}/clele.env" 2>/dev/null | tail -1 | tr -cd '0-9')
PORT=\${PORT:-8080}

# Poll rather than probe once: a cold start takes ~12s, so the old single check 2s after restart
# reported a connection failure on a perfectly healthy deploy.
echo "==> Local health check on port \${PORT} (expect 200, or a redirect to login):"
code=000
for _ in \$(seq 1 30); do
  code=\$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:\${PORT}/" || true)
  if [ "\$code" != "000" ]; then break; fi
  sleep 2
done

if [ "\$code" = "000" ]; then
  echo "  GET / -> no answer after 60s — the app is not serving" >&2
  exit 1
fi
echo "  GET / -> \$code"
EOF
echo "Done"

