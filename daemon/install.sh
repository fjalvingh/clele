#!/usr/bin/env bash
# Installs clele-print-daemon as a systemd service on Ubuntu.
#
# Usage: sudo ./install.sh --backend-url https://your-clele-server
#
# Expects the already-built "clele-print-daemon" binary in the same directory as this script
# (build it with: cd daemon && go build -o clele-print-daemon ./cmd/clele-print-daemon).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_URL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend-url)
      BACKEND_URL="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$BACKEND_URL" ]]; then
  echo "Usage: sudo ./install.sh --backend-url https://your-clele-server" >&2
  exit 1
fi

if [[ $EUID -ne 0 ]]; then
  echo "This installer must be run as root (sudo ./install.sh ...)" >&2
  exit 1
fi

if [[ ! -f "$SCRIPT_DIR/clele-print-daemon" ]]; then
  echo "clele-print-daemon binary not found next to install.sh — build it first with:" >&2
  echo "  cd daemon && go build -o clele-print-daemon ./cmd/clele-print-daemon" >&2
  exit 1
fi

echo "Installing binary to /usr/local/bin/clele-print-daemon"
install -m 0755 "$SCRIPT_DIR/clele-print-daemon" /usr/local/bin/clele-print-daemon

if ! id -u clele-print >/dev/null 2>&1; then
  echo "Creating system user clele-print"
  useradd --system --no-create-home --shell /usr/sbin/nologin clele-print
fi

mkdir -p /etc/clele-print-daemon

if [[ ! -f /etc/clele-print-daemon/config.json ]]; then
  echo "Registering with $BACKEND_URL"
  /usr/local/bin/clele-print-daemon register --backend-url "$BACKEND_URL"
else
  echo "Config already exists at /etc/clele-print-daemon/config.json — skipping registration"
fi

chown -R clele-print:clele-print /etc/clele-print-daemon
chmod 700 /etc/clele-print-daemon

echo "Installing systemd unit"
install -m 0644 "$SCRIPT_DIR/clele-print-daemon.service" /etc/systemd/system/clele-print-daemon.service
systemctl daemon-reload
systemctl enable --now clele-print-daemon

echo "Done. Claim this daemon from the Clele Settings page, then set its printer IP there."
echo "Check status with: systemctl status clele-print-daemon"
