#!/usr/bin/env bash
# Emergency unblock of a login-blocked IP (when no System / platform-admin user can log in any more because their own IP was blocked).
#   bash deploy/oracle/unblock-ip.sh                 -> list the IPs that are blocked now (MBill + MSchool)
#   bash deploy/oracle/unblock-ip.sh 203.0.113.9     -> unblock this IP in both applications and reset its failure counters of today
# Normal case: a System user (MBill) / the platform admin (MSchool) unblocks from  Admin -> "Blocked IPs".
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$APP_DIR"
set -a; . ./.env; set +a
IP="${1:-}"
if [ -n "$IP" ] && ! printf '%s' "$IP" | grep -Eq '^[0-9a-fA-F:.%]{3,64}$'; then echo "not an IP address: $IP" >&2; exit 2; fi
sql() { docker compose exec -T -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 "$1" -e "$2" 2>/dev/null; }
for DB in bmb mschool; do
  if ! sql "$DB" "SELECT 1" >/dev/null 2>&1; then echo "[$DB] database not found - skipped"; continue; fi
  if [ -z "$IP" ]; then
    echo "== $DB: blocked IPs =="
    sql "$DB" "SELECT ip, blocked_at, fails, last_user FROM ip_block WHERE active = 1 ORDER BY blocked_at DESC" || echo "(no ip_block table yet - nothing was ever blocked)"
  else
    sql "$DB" "UPDATE ip_block SET active = 0, unblocked_at = NOW(), unblocked_by = 'unblock-ip.sh' WHERE ip = '$IP' AND active = 1; DELETE FROM ip_fail_day WHERE ip = '$IP' AND day = CURDATE(); SELECT ROW_COUNT() AS counters_reset;" \
      && echo "[$DB] $IP unblocked" || echo "[$DB] nothing to do (no table yet)"
  fi
done
