#!/usr/bin/env bash
# Deploy / update the suite WITH the new mHotel app. mHotel is the 4th WAR in the SAME container
# (one Tomcat, one JVM) on port 8083, with its own database "mhotel" on the same MySQL.
# Existing production (MBill :8080, MSchool :8081, website :8082) is untouched - this only ADDS mHotel.
#
# Run it from the repo root (where docker-compose.yaml is):   bash deploy/deploy-mhotel.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> 1/3  checking .env"
if [ ! -f .env ]; then
  echo "    .env not found. cp env.example .env and set the passwords first." >&2
  exit 1
fi
# make sure the new keys exist (added once; existing values are kept)
grep -q '^HOTEL_PORT='            .env || echo 'HOTEL_PORT=127.0.0.1:8083'  >> .env
grep -q '^MHOTEL_WEBHOOK_SECRET=' .env || echo 'MHOTEL_WEBHOOK_SECRET='      >> .env
echo "    ok (HOTEL_PORT / MHOTEL_WEBHOOK_SECRET present)"

echo "==> 2/3  build + (re)start the one container (adds mhotel.war + creates database mhotel)"
# The same command you already use. It rebuilds the image (now including mHotel), runs the mhotel-db
# one-shot that creates the 'mhotel' schema, and restarts the app. MBill/MSchool/website keep their data.
docker compose up -d --build

echo "==> 3/3  health"
sleep 4
docker compose ps
cat <<'EOF'

Done. mHotel is live:
  - direct (if HOTEL_PORT=8083):      http://<server>:8083/
  - localhost only (default):         http://127.0.0.1:8083/   (publish via Caddy + HOTEL_DOMAIN for HTTPS)
  first login:  admin / admin1234   (change it in More -> password)

Swiggy/Zomato webhook (optional): set MHOTEL_WEBHOOK_SECRET in .env, then
  POST http://<server>:8083/api/delivery/webhook
  header:  X-Webhook-Secret: <that secret>
  body:    {"partner":"swiggy","ref":"SW123","customer":"Ravi","phone":"98xxxxxxxx","items":[{"name":"Idli","qty":2,"price":40}]}
EOF
