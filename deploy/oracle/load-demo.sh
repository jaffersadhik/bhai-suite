#!/usr/bin/env bash
# Load / reset the demo companies (Liba, Rasi, Seeds). Run again before every demo:
# "today's bills" are always made for the day you import.
#   bash deploy/oracle/load-demo.sh            -> all three
#   bash deploy/oracle/load-demo.sh seeds      -> only one (liba | rasi | seeds)
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
for d in ${1:-liba rasi seeds}; do
  f="db/demo/$d-demo.sql"
  [ -f "$f" ] || { echo "missing $f"; continue; }
  echo "loading $f ..."
  docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
    mysql -uroot --default-character-set=utf8mb4 --max_allowed_packet=64M bmb < "$f"
done
echo "done. shop links: https://${DOMAIN:-<domain>}/s/libademo | /s/rasidemo | /s/seedsdemo  (after the next restart each company also gets a name based address, see Admin -> Website)"
