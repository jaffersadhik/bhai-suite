#!/usr/bin/env bash
# Restore a backup made by backup.sh:   bash deploy/oracle/restore.sh /opt/bmb/backup/bmb-2026-11-03_0230.sql.gz
# Files named bmb-*.sql.gz restore database bmb (Bhai Mobile Billing); mschool-*.sql.gz restore database mschool.
# REPLACES all current data in that database.
set -euo pipefail
F="${1:?usage: restore.sh <backup.sql.gz>}"
[ -f "$F" ] || { echo "no file $F"; exit 1; }
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
DB=bmb; case "$(basename "$F")" in mschool-*) DB=mschool;; esac
if [ "${RESTORE_YES:-}" != 1 ]; then
  read -r -p "All current data of database $DB will be replaced by $F. Type YES to continue: " ok
  [ "$ok" = YES ] || exit 1
fi
docker compose stop app
gzip -dc "$F" | docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 "$DB"
docker compose start app
echo "restored $F"
