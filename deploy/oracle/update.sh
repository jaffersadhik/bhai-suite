#!/usr/bin/env bash
# Deploy a new version: copy the new project files over this folder (or git pull), then
#   bash deploy/oracle/update.sh
# 1. backup of the database (used by "rollback with database")
# 2. the running image is kept as  bhai-mobile-billing:prev  (used by rollback)
# 3. new build with a build id; on start-up the app runs its post-deploy tests (admin → 🧪 Post-deploy Tests)
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
CTL="${CTL_DIR:-$APP_DIR/ctl}"; mkdir -p "$CTL"
IMG=bhai-mobile-billing
[ -d .git ] && git pull --ff-only || true

BACKUP="$(sudo "$APP_DIR/deploy/oracle/backup.sh" | sed -n 's/.*backup ok: \([^ ]*\).*/\1/p' | tail -1)"
PREV="$(docker image inspect "$IMG:1.0.0" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^BMB_BUILD=//p' || true)"
if [ -n "$PREV" ] || docker image inspect "$IMG:1.0.0" >/dev/null 2>&1; then
  docker tag "$IMG:1.0.0" "$IMG:prev"
fi
export BUILD_ID="$(date +%Y%m%d-%H%M)$( [ -d .git ] && echo "-$(git rev-parse --short HEAD)" )"
docker compose -f docker-compose.yaml -f docker-compose.https.yaml up -d --build
cat > "$CTL/build.json" <<EOT
{"current":"$BUILD_ID","previous":"${PREV:-}","deployedAt":"$(date '+%Y-%m-%d %H:%M')","backup":"${BACKUP:-}"}
EOT
sudo chown 10001 "$CTL" "$CTL/build.json" 2>/dev/null || true
rm -f "$CTL/rollback.request"
docker image prune -f >/dev/null || true
echo "updated to build $BUILD_ID (previous: ${PREV:-none}) - post-deploy tests run in the app; logs: docker compose logs -f app"
