#!/usr/bin/env bash
# Deploy / update the suite WITH the new Purchase Order Management app. It is the 5th WAR in the SAME
# container (one Tomcat, one JVM) on port 8084, with its own database "po_mgmt" on the same MySQL.
# Existing production (MBill :8080, MSchool :8081, website :8082, mHotel :8083) is untouched — this only ADDS po-mgmt.
#
# Run from the repo root:   bash deploy/deploy-po-mgmt.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 1/3  checking .env"
[ -f .env ] || { echo "    .env not found. cp env.example .env and set the passwords first." >&2; exit 1; }
grep -q '^PO_PORT='                   .env || echo 'PO_PORT=127.0.0.1:8084'       >> .env
grep -q '^POMGMT_BUDGET_ENFORCEMENT=' .env || echo 'POMGMT_BUDGET_ENFORCEMENT=warn' >> .env
echo "    ok"

echo "==> 2/3  build + (re)start the one container (adds po-mgmt.war + creates database po_mgmt)"
docker compose up -d --build

echo "==> 3/3  health"
sleep 4
docker compose ps
cat <<'EOF'

Done. Purchase Order Management is live:
  - direct (if PO_PORT=8084):     http://<server>:8084/
  - localhost only (default):     http://127.0.0.1:8084/   (publish via Caddy + PO_DOMAIN for HTTPS)
  first login:  admin / admin1234   (change it in More -> password)
  demo users:   exec1 / mgr1 / appr1  (password demo1234)

Budget rule: POMGMT_BUDGET_ENFORCEMENT in .env = off | warn (default) | strict
EOF
