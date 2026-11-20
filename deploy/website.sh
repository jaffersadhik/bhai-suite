#!/usr/bin/env bash
# ============================================================================
# Meeran Technologies website: build the image -> (optional) push to a registry -> run the container -> smoke test.
# Production safe: only the "website" container is created / replaced. mysql, app (MBill + MSchool) and caddy are NOT restarted.
#
#   bash deploy/website.sh                 build + run
#   bash deploy/website.sh --push          also "docker push" the image (set WEBSITE_IMAGE=<registry>/<name>:<tag> and "docker login" first)
#   bash deploy/website.sh --caddy         also re-create Caddy so a new WEBSITE_DOMAIN is served (HTTPS blips for ~2 s; only needed once)
#   bash deploy/website.sh --pull          do not build: pull WEBSITE_IMAGE from the registry and run it (server side of a push)
#
# .env: WEBSITE_PORT (default 127.0.0.1:8082), WEBSITE_DOMAIN, WEBSITE_IMAGE (default meeran-website:latest)
# ============================================================================
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"
[ -f .env ] && { set -a; . ./.env; set +a; }

PUSH=0; CADDY=0; PULL=0
for a in "$@"; do case "$a" in --push) PUSH=1;; --caddy) CADDY=1;; --pull) PULL=1;; *) echo "unknown option: $a"; exit 2;; esac; done

export WEBSITE_IMAGE="${WEBSITE_IMAGE:-meeran-website:latest}"
FILES=(-f docker-compose.yaml)
[ -n "${DOMAIN:-}" ] && FILES+=(-f docker-compose.https.yaml)
FILES+=(-f docker-compose.website.yaml)
DC=(docker compose "${FILES[@]}")

if [ "$PULL" = 1 ]; then
  echo "==> pulling $WEBSITE_IMAGE"
  "${DC[@]}" pull website
else
  echo "==> building $WEBSITE_IMAGE (Maven runs inside Docker, first build downloads dependencies)"
  docker build -f Dockerfile.website -t "$WEBSITE_IMAGE" .
fi
if [ "$PUSH" = 1 ]; then
  case "$WEBSITE_IMAGE" in */*) ;; *) echo "XX  --push needs WEBSITE_IMAGE with a registry name, e.g. ghcr.io/<you>/meeran-website:1.0.0"; exit 1;; esac
  echo "==> pushing $WEBSITE_IMAGE"
  docker push "$WEBSITE_IMAGE"
fi

echo "==> starting the website container (mysql / app are not touched)"
"${DC[@]}" up -d --no-deps --no-build website

if [ "$CADDY" = 1 ] && [ -n "${DOMAIN:-}" ]; then
  echo "==> re-creating Caddy so WEBSITE_DOMAIN=${WEBSITE_DOMAIN:-<unset>} is served"
  "${DC[@]}" up -d --no-deps caddy
fi

echo "==> smoke test"
PORT="${WEBSITE_PORT:-127.0.0.1:8082}"; HOSTPORT="${PORT#*:}"; case "$PORT" in *:*) H="${PORT%%:*}";; *) H=127.0.0.1;; esac
[ "$H" = 0.0.0.0 ] && H=127.0.0.1
ok=0
for i in $(seq 1 30); do
  if curl -fsS "http://$H:$HOSTPORT/api/myip" 2>/dev/null | grep -q '"ip"' && curl -fsS "http://$H:$HOSTPORT/" 2>/dev/null | grep -q 'Meeran Technologies'; then ok=1; break; fi
  sleep 2
done
if [ "$ok" = 1 ]; then echo "OK  website answers on http://$H:$HOSTPORT/  (container: $("${DC[@]}" ps -q website | head -c 12))"
else echo "XX  website did not answer in 60 s - logs:  ${DC[*]} logs --tail 50 website"; exit 1; fi
docker image prune -f >/dev/null || true
