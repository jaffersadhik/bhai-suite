#!/usr/bin/env bash
# Runs every minute from cron (installed by setup.sh). Carries out a rollback the admin asked for in the app.
set -uo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
CTL="${CTL_DIR:-$APP_DIR/ctl}"
REQ="$CTL/rollback.request"
[ -f "$REQ" ] || exit 0
mv "$REQ" "$CTL/rollback.processing" || exit 0
ARG=""; grep -q '"withDb":true' "$CTL/rollback.processing" && ARG="--db"
st(){ printf '{"state":"%s","at":"%s","message":"%s"}\n' "$1" "$(date '+%Y-%m-%d %H:%M')" "$2" > "$CTL/rollback.status"; chown 10001 "$CTL/rollback.status" 2>/dev/null || true; }
st running "Rollback நடக்கிறது"
if OUT="$(bash "$APP_DIR/deploy/oracle/rollback.sh" $ARG 2>&1)"; then
  st done "$(echo "$OUT" | tail -1 | tr -d '"\\')"
else
  st failed "$(echo "$OUT" | tail -2 | tr '\n' ' ' | tr -d '"\\')"
fi
cat "$CTL/rollback.processing" >> /var/log/bmb-rollback.log; echo "$OUT" >> /var/log/bmb-rollback.log
rm -f "$CTL/rollback.processing"
