#!/usr/bin/env bash
# Stage a quiescent store backup for the Android app's one-time import.
# This does not install an APK or stop the existing store.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 SNAPSHOT_DIRECTORY" >&2
  exit 2
fi

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
snapshot_dir=$(cd "$1" && pwd)
package=dev.dwhipstock.pos_client
staging="/sdcard/Android/data/$package/files/migration"

for required in pos.db photos receipts bills; do
  [[ -e "$snapshot_dir/$required" ]] || { echo "Missing $required" >&2; exit 1; }
done
[[ -f "$repo_dir/.env.edge" ]] || { echo "Missing .env.edge" >&2; exit 1; }

# The cloud key is never printed or placed in a tracked source file.
set -a
source "$repo_dir/.env.edge"
set +a
[[ -n "${CLOUD_SYNC_API_KEY:-}" ]] || { echo "Missing cloud key" >&2; exit 1; }

install_id=$(sqlite3 "$snapshot_dir/pos.db" "SELECT value FROM sync_state WHERE key='install_id'")
[[ "$install_id" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "Invalid store identity" >&2; exit 1; }
[[ $(sqlite3 "$snapshot_dir/pos.db" 'PRAGMA integrity_check') == ok ]] || {
  echo "Database integrity check failed" >&2
  exit 1
}

umask 077
trap 'rm -f "$snapshot_dir/store-cloud.properties"' EXIT
printf 'store.installId=%s\ncloud.url=%s\ncloud.apiKey=%s\nportal.url=%s\n' \
  "$install_id" \
  'https://copperlantern-manager.lostmindllc.com' \
  "$CLOUD_SYNC_API_KEY" \
  'https://copperlantern-manager.lostmindllc.com' \
  > "$snapshot_dir/store-cloud.properties"
(
  cd "$snapshot_dir"
  zip -qr store-media.zip photos receipts bills
)

adb shell mkdir -p "$staging"
adb push "$snapshot_dir/pos.db" "$staging/pos.db"
adb push "$snapshot_dir/store-media.zip" "$staging/store-media.zip"
adb push "$snapshot_dir/store-cloud.properties" "$staging/store-cloud.properties"
echo "Staged validated store backup for one-time import. Store identity: $install_id"
