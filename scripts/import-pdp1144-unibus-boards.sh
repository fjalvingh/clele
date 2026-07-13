#!/usr/bin/env bash
# Imports the Unibus board list from https://etc.to/pdp-1144/unibus-board-list/index.html
# into Clele: creates a catalog part per owned board and adds its quantity as stock at a
# single location. Rows marked "Want" on the source page (not yet owned) are skipped entirely.
#
# Usage:
#   CLELE_EMAIL=you@example.com CLELE_PASSWORD=secret ./import-pdp1144-unibus-boards.sh
#
# Optional env vars:
#   CLELE_BASE_URL   default http://localhost:8080
#   LOCATION_NAME    default "France Lab" (must already exist, case-sensitive exact match)

set -euo pipefail

BASE_URL="${CLELE_BASE_URL:-http://localhost:8080}"
LOCATION_NAME="${LOCATION_NAME:-France Lab}"
EMAIL="${CLELE_EMAIL:?Set CLELE_EMAIL}"
PASSWORD="${CLELE_PASSWORD:?Set CLELE_PASSWORD}"

COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

api() {
  local method="$1" path="$2" data="${3:-}"
  if [[ -n "$data" ]]; then
    curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" "$BASE_URL/api$path" \
      -H 'Content-Type: application/json' -d "$data"
  else
    curl -sS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X "$method" "$BASE_URL/api$path"
  fi
}

echo "Logging in as $EMAIL..."
login_resp="$(api POST /auth/login "$(jq -n --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')")"
if ! jq -e '.id' >/dev/null 2>&1 <<<"$login_resp"; then
  echo "Login failed: $login_resp" >&2
  exit 1
fi

echo "Resolving location \"$LOCATION_NAME\"..."
location_id="$(api GET /locations/mine | jq -r --arg n "$LOCATION_NAME" '.[] | select(.name == $n) | .id' | head -1)"
if [[ -z "$location_id" ]]; then
  echo "Location \"$LOCATION_NAME\" not found among your locations. Create it first." >&2
  exit 1
fi
echo "Using location id $location_id"

# board | number | description | manufacturer | categoryId | quantity
# "Want" rows from the source page (KE44-A, KE44-A/2, DL11-W, TC12) are omitted — not yet owned.
boards='
PF11-F|M7093|11/44 Floating Point|DEC|1411|2
KD11-Z|M7094|11/44 Data Path module|DEC|1411|2
KD11-Z|M7095|11/44 Control module|DEC|1411|2
KD11-Z|M7096|11/44 Multifunction module|DEC|1411|2
KK11-B|M7097|11/44 4KWord cache module|DEC|1411|2
KD11-Z|M7098|11/44 UNIBUS Interface|DEC|1411|2
DHU11-M|M3105|16 line async NUX with DMA|DEC|1413|2
DELUA|M7521|Ethernet interface|DEC|1413|1
RL11|M7762|RL02 Disk controller. Both repaired.|DEC|1413|2
DZ11-A|M7819|8-line double buffered async rs232/EIA with modem control|DEC|1413|2
RX211|M8256|RX02 floppy disk interface, repaired|DEC|1413|1
MS11-PB|M8743|512KB ECC RAM (AA)|DEC|1412|4
H317-E||RS232/EIA Distribution panel (16x)|DEC|1416|1
CR-11/CM-11|M8291|Card reader controller (punch card reader)|DEC|1413|2
'

while IFS='|' read -r board number description manufacturer categoryId quantity; do
  [[ -z "$board" ]] && continue
  part_number="${number:-$board}"
  full_description="$board — $description"

  echo "Creating part $part_number ($full_description)..."
  part_resp="$(api POST /parts "$(jq -n \
    --arg partNumber "$part_number" \
    --arg description "$full_description" \
    --arg manufacturer "$manufacturer" \
    --argjson categoryId "$categoryId" \
    '{partNumber:$partNumber, description:$description, manufacturer:$manufacturer, categoryId:$categoryId}')")"
  part_id="$(jq -r '.id // empty' <<<"$part_resp")"
  if [[ -z "$part_id" ]]; then
    echo "  Failed to create part: $part_resp" >&2
    continue
  fi

  if [[ -n "$quantity" ]]; then
    echo "  Adding stock: qty=$quantity at location $location_id"
    stock_resp="$(api POST /stock/add "$(jq -n \
      --argjson partId "$part_id" \
      --argjson locationId "$location_id" \
      --argjson quantity "$quantity" \
      '{partId:$partId, locationId:$locationId, quantity:$quantity}')")"
    if ! jq -e '.id' >/dev/null 2>&1 <<<"$stock_resp"; then
      echo "  Failed to add stock: $stock_resp" >&2
    fi
  else
    echo "  No quantity (Want) — catalog part only, no stock entry."
  fi
done <<<"$boards"

echo "Done."
