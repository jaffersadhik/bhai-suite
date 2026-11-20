#!/usr/bin/env bash
# ============================================================================
# Bhai Mobile Billing - one-time setup on an Oracle Cloud "Always Free" ARM VM
# (VM.Standard.A1.Flex, Ubuntu 22.04 / 24.04). Also works on any Ubuntu VM.
#
#   cd ~/bhai-mobile-billing
#   sudo bash deploy/oracle/setup.sh billing.example.com
#
#   arg 1  DOMAIN  - domain / sub-domain whose DNS A record points to this VM's public IP
#                    (e.g. bhaibilling.duckdns.org or billing.yourname.in)
#
# What it does (safe to run again):
#   1. time zone Asia/Kolkata, 2 GB swap, basic packages
#   2. Docker Engine + compose plugin (arm64 / amd64)
#   3. opens ports 80 + 443 in the VM firewall (Oracle Ubuntu images block them)
#   4. /opt/bmb/mysql-data + /opt/bmb/backup, .env with random passwords (kept if it exists)
#   5. builds and starts MySQL + app + Caddy (automatic HTTPS) - the app port 8080 is NOT public
#   6. nightly MySQL backup at 02:30 (keeps 14 days, optional copy to Google Drive with rclone)
# ============================================================================
set -euo pipefail

DOMAIN="${1:-${DOMAIN:-}}"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_USER="${SUDO_USER:-ubuntu}"
DATA=/opt/bmb

say()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mXX  %s\033[0m\n' "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo:  sudo bash deploy/oracle/setup.sh <domain>"
[ -n "$DOMAIN" ] || die "domain missing:  sudo bash deploy/oracle/setup.sh billing.example.com"
[ -f "$APP_DIR/docker-compose.yaml" ] || die "docker-compose.yaml not found in $APP_DIR"
echo "$DOMAIN" | grep -Eq '^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' || die "domain looks wrong: $DOMAIN"

say "1/6  time zone, swap, packages"
timedatectl set-timezone Asia/Kolkata || true
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -y -q
apt-get install -y -q ca-certificates curl gzip cron iptables-persistent netfilter-persistent dnsutils >/dev/null

say "2/6  Docker"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker
usermod -aG docker "$RUN_USER" || true
docker compose version >/dev/null || die "docker compose plugin missing"

say "3/6  firewall: allow 80 + 443"
# Oracle images end INPUT with a REJECT rule: insert our rules just before it (or append if there is none)
allow() {
  iptables -C INPUT "$@" -j ACCEPT 2>/dev/null && return
  local n; n=$(iptables -L INPUT --line-numbers -n | awk '$2=="REJECT"{print $1; exit}')
  if [ -n "$n" ]; then iptables -I INPUT "$n" "$@" -j ACCEPT; else iptables -A INPUT "$@" -j ACCEPT; fi
}
allow -p tcp -m state --state NEW --dport 80
allow -p tcp -m state --state NEW --dport 443
allow -p udp --dport 443
netfilter-persistent save >/dev/null
warn "Also open TCP 80 and 443 in Oracle Console -> VCN -> Security List -> Ingress Rules (source 0.0.0.0/0)"

say "4/6  folders + .env"
mkdir -p "$DATA/mysql-data" "$DATA/backup" "$DATA/ctl"
chown 10001 "$DATA/ctl"      # app container user: writes rollback requests here
ENV="$APP_DIR/.env"
if [ ! -f "$ENV" ]; then
  rnd() { tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }
  cat > "$ENV" <<EOT
MYSQL_ROOT_PASSWORD=$(rnd)
DB_USER=bmb
DB_PASS=$(rnd)
MYSQL_DATA_DIR=$DATA/mysql-data
MYSQL_BACKUP_DIR=$DATA/backup
# app only on localhost - the public goes through Caddy (HTTPS)
APP_PORT=127.0.0.1:8080
SCHOOL_PORT=127.0.0.1:8081
WEBSITE_PORT=127.0.0.1:8082
DOMAIN=$DOMAIN
# SCHOOL_DOMAIN=school.example.com   (MSchool address - DNS A record -> this VM, then re-run docker compose up -d)
# WEBSITE_DOMAIN=www.example.in      (company website address - DNS A record -> this VM, then re-run docker compose up -d)
CTL_DIR=$DATA/ctl
EOT
  chmod 600 "$ENV"; chown "$RUN_USER": "$ENV"
  say ".env created with random passwords (kept in $ENV - back it up!)"
else
  grep -q '^DOMAIN=' "$ENV" && sed -i "s|^DOMAIN=.*|DOMAIN=$DOMAIN|" "$ENV" || echo "DOMAIN=$DOMAIN" >> "$ENV"
  grep -q '^APP_PORT=' "$ENV" && sed -i 's|^APP_PORT=.*|APP_PORT=127.0.0.1:8080|' "$ENV" || echo "APP_PORT=127.0.0.1:8080" >> "$ENV"
  grep -q '^CTL_DIR=' "$ENV" || echo "CTL_DIR=$DATA/ctl" >> "$ENV"
  say ".env exists - kept passwords, set DOMAIN=$DOMAIN"
fi

MYIP=$(curl -fsS4 --max-time 5 https://ifconfig.me 2>/dev/null || true)
DNSIP=$(dig +short A "$DOMAIN" | tail -1 || true)
if [ -n "$MYIP" ] && [ "$MYIP" != "$DNSIP" ]; then
  warn "DNS: $DOMAIN -> '${DNSIP:-none}', this VM -> $MYIP. HTTPS will work only after the A record points to $MYIP."
fi

say "5/6  build + start (first build on ARM takes 5-10 minutes)"
cd "$APP_DIR"
export BUILD_ID="$(date +%Y%m%d-%H%M)"
docker compose -f docker-compose.yaml -f docker-compose.https.yaml up -d --build
[ -f "$DATA/ctl/build.json" ] || echo "{\"current\":\"$BUILD_ID\",\"previous\":\"\",\"deployedAt\":\"$(date '+%Y-%m-%d %H:%M')\",\"backup\":\"\"}" > "$DATA/ctl/build.json"
chown 10001 "$DATA/ctl/build.json"
docker image prune -f >/dev/null || true

say "6/6  nightly backup 02:30 + rollback watcher"
chmod +x "$APP_DIR"/deploy/oracle/*.sh
cat > /etc/cron.d/bmb-backup <<EOT
# Bhai Mobile Billing - MySQL backup every night 02:30 (India time)
30 2 * * * root $APP_DIR/deploy/oracle/backup.sh >> /var/log/bmb-backup.log 2>&1
EOT
chmod 644 /etc/cron.d/bmb-backup
cat > /etc/cron.d/bmb-ctl <<EOT
# MBill - carries out a rollback requested by the admin in the app (🧪 Post-deploy Tests)
* * * * * root $APP_DIR/deploy/oracle/ctl-watch.sh >> /var/log/bmb-rollback.log 2>&1
EOT
chmod 644 /etc/cron.d/bmb-ctl
systemctl restart cron

say "waiting for the app"
for i in $(seq 1 60); do
  curl -fsS http://127.0.0.1:8080/api/ping >/dev/null 2>&1 && break
  sleep 5
done
if curl -fsS http://127.0.0.1:8080/api/ping >/dev/null 2>&1; then
  say "DONE  ->  https://$DOMAIN/      (first login: admin / admin1234 - change it at once)"
  say "MSchool runs in the same container on :8081 (first login admin / admin123) - set SCHOOL_DOMAIN in .env for its https address"
  say "Company website runs in the same container on :8082 - set WEBSITE_DOMAIN in .env for its https address"
  echo "      demo data:  bash deploy/oracle/load-demo.sh      backup now:  sudo deploy/oracle/backup.sh"
else
  warn "app not answering yet - check:  docker compose logs -f app"
fi
