#!/usr/bin/env bash
#
# Build Clele and deploy it to a server as a systemd service, served under /clele.
#
#   1. builds the jar locally with the /clele subpath baked into the frontend
#   2. ships the jar + systemd unit + env template to the server over ssh/scp
#   3. (re)starts the clele service
#
# Configure the target below or via environment variables, e.g.:
#   DEPLOY_HOST=myserver.example.com DEPLOY_USER=deploy ./deploy/deploy.sh
#
set -euo pipefail

# ---- configuration (override via env) --------------------------------------
DEPLOY_USER="${DEPLOY_USER:-jal}"                       # ssh login user (needs sudo on the server)
DEPLOY_HOST="${DEPLOY_HOST:-surly.qd.ax}"               # ssh host, e.g. myserver.example.com
SSH_PORT="${SSH_PORT:-22}"
DEPLOY_DIR="${DEPLOY_DIR:-/env/clele}"               # where the jar lives on the server
ENV_DIR="${ENV_DIR:-/etc/clele}"                     # where clele.env lives on the server
SERVICE_USER="${SERVICE_USER:-clele}"               # dedicated system user the service runs as
SERVICE_NAME="${SERVICE_NAME:-clele}"
JAVA_BIN="${JAVA_BIN:-/opt/java/21/bin/java}"        # must match ExecStart in clele.service
JAR="${JAR:-parts-0.0.1-SNAPSHOT.jar}"
BASE_PATH="${BASE_PATH:-/clele/}"                    # Vite base (trailing slash) = the subpath
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

# 1. Build the jar with the /clele subpath baked into the frontend bundle.
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
sudo install -m 644 /tmp/clele.service /etc/systemd/system/${SERVICE_NAME}.service
# Install the env template; never clobber a real, already-configured env file.
if [[ ! -f "${ENV_DIR}/clele.env" ]]; then
  sudo install -o root -g "${SERVICE_USER}" -m 640 /tmp/clele.env.example "${ENV_DIR}/clele.env"
  echo "NOTE: created ${ENV_DIR}/clele.env from the template — edit it and set DB_PASSWORD / ANTHROPIC_API_KEY."
fi
if grep -q 'change-me' "${ENV_DIR}/clele.env"; then
  echo "WARNING: ${ENV_DIR}/clele.env still contains 'change-me' — the app will not start correctly until you fix it."
fi
rm -f "/tmp/$JAR" /tmp/clele.service /tmp/clele.env.example
sudo systemctl daemon-reload
sudo systemctl enable --now ${SERVICE_NAME}
sudo systemctl restart ${SERVICE_NAME}
sleep 2
sudo systemctl --no-pager --full status ${SERVICE_NAME} || true
echo "==> Local health check (expect HTTP 200/302 or a redirect to login):"
curl -s -o /dev/null -w '  GET /clele/ -> %{http_code}\n' http://127.0.0.1:8080/clele/ || true
EOF

echo "==> Done. Remember: add the Apache snippet (deploy/clele-apache.conf), reload Apache,"
echo "    and change the bootstrap admin password (admin@clele.local / admin) after first login."
