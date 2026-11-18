#!/usr/bin/env bash
# Roll back to the previous build (the image kept by update.sh as bhai-mobile-billing:prev).
#   bash deploy/oracle/rollback.sh            app only (data stays)
#   bash deploy/oracle/rollback.sh --db       also restore the database backup taken just before the deploy
# Normally started by ctl-watch.sh when the admin presses "Rollback" in 🧪 Post-deploy Tests.
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
CTL="${CTL_DIR:-$APP_DIR/ctl}"
IMG=bhai-mobile-billing
WITH_DB=0; [ "${1:-}" = "--db" ] && WITH_DB=1
docker image inspect "$IMG:prev" >/dev/null 2>&1 || { echo "no previous image ($IMG:prev)"; exit 1; }
CUR="$(sed -n 's/.*"current":"\([^"]*\)".*/\1/p' "$CTL/build.json" 2>/dev/null || true)"
PREV="$(sed -n 's/.*"previous":"\([^"]*\)".*/\1/p' "$CTL/build.json" 2>/dev/null || true)"
BACKUP="$(sed -n 's/.*"backup":"\([^"]*\)".*/\1/p' "$CTL/build.json" 2>/dev/null || true)"

sudo "$APP_DIR/deploy/oracle/backup.sh" || true                      # safety copy of the data as it is now
docker tag "$IMG:1.0.0" "$IMG:rolled-back" 2>/dev/null || true       # the bad build is kept for inspection
docker tag "$IMG:prev" "$IMG:1.0.0"
if [ "$WITH_DB" = 1 ]; then
  [ -n "$BACKUP" ] && [ -f "$BACKUP" ] || { echo "pre-deploy backup not found: $BACKUP"; exit 1; }
  RESTORE_YES=1 bash "$APP_DIR/deploy/oracle/restore.sh" "$BACKUP"
fi
docker compose -f docker-compose.yaml -f docker-compose.https.yaml up -d --no-build app
cat > "$CTL/build.json" <<EOT
{"current":"$PREV","previous":"","rolledBackFrom":"$CUR","deployedAt":"$(date '+%Y-%m-%d %H:%M')","backup":""}
EOT
echo "rolled back: $CUR -> $PREV (database restored: $WITH_DB)"
