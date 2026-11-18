# Oracle Cloud "Always Free" — Bhai Mobile Billing deploy (₹0 / month)

Server: Oracle ARM VM (Always Free: up to 2 OCPU + 12 GB RAM in total).
Stack on the VM: Docker → MySQL 8.4 + the app + Caddy (automatic HTTPS). Only ports 80/443 are public.

## 1. Oracle account + VM (one time, ~20 min)
1. Sign up at cloud.oracle.com (card is only for verification). Home region: **India South (Hyderabad)** or **India West (Mumbai)**.
2. Compute → Instances → **Create instance**
   * Image: **Canonical Ubuntu 24.04** (or 22.04)
   * Shape: **Ampere → VM.Standard.A1.Flex**, **2 OCPU, 12 GB** (stay inside the free limit)
   * Networking: keep "Assign a public IPv4 address"
   * SSH keys: "Generate a key pair" → **download the private key** (keep it safe)
   * Boot volume: default (≈47 GB, free up to 200 GB in total)
   * "Out of capacity" error → try another Availability Domain or try again later.
3. Instance page → Subnet → Security List → **Add Ingress Rules**:
   source `0.0.0.0/0`, TCP, destination port **80**; again for **443**.
4. Note the instance's **Public IP**.

## 2. Domain → IP
* Free: duckdns.org → login → add sub-domain (e.g. `bhaibilling`) → set "current ip" to the Public IP → `bhaibilling.duckdns.org`
* Or your own `.in` domain → DNS → **A record** `billing` → Public IP.

## 3. Copy the app and run the setup
From your PC (Windows PowerShell or Linux):
```bash
scp -i oracle.key bhai-mobile-billing.zip ubuntu@<PUBLIC-IP>:~
ssh -i oracle.key ubuntu@<PUBLIC-IP>
```
On the VM:
```bash
sudo apt-get install -y unzip && unzip -q bhai-mobile-billing.zip && cd bhai-mobile-billing
sudo bash deploy/oracle/setup.sh bhaibilling.duckdns.org
```
First build on ARM takes 5–10 minutes. At the end: `https://bhaibilling.duckdns.org/` → login **admin / admin1234** → change the password.
`.env` (random DB passwords) is created in the project folder — **copy it somewhere safe**.

Log out and in again once (so `ubuntu` can run docker without sudo).

## 4. Demo data
```bash
bash deploy/oracle/load-demo.sh          # Liba + Rasi + Seeds (run again before every demo: "today" stays today)
bash deploy/oracle/load-demo.sh seeds    # only one
```
Shop links: `https://<domain>/shop.html?s=libademo` · `rasidemo` · `seedsdemo`

## 5. Daily use
| Task | Command |
|---|---|
| New version | copy new files over the folder (keep `.env`), then `bash deploy/oracle/update.sh` (takes a backup first) |
| Rollback (bad build) | Admin → 🧪 Post-deploy Tests → ↩ Rollback (done by cron within a minute), or `bash deploy/oracle/rollback.sh [--db]` |
| Backup now | `sudo deploy/oracle/backup.sh` → `/opt/bmb/backup/bmb-<date>.sql.gz` (also every night 02:30, kept 14 days) |
| Restore | `bash deploy/oracle/restore.sh /opt/bmb/backup/bmb-….sql.gz` |
| Logs | `docker compose logs -f app` · backup log `/var/log/bmb-backup.log` |
| Status | `docker compose -f docker-compose.yaml -f docker-compose.https.yaml ps` |

Off-site copy of backups (free Google Drive 15 GB):
```bash
sudo apt-get install -y rclone && rclone config      # new remote "gdrive", type drive (headless: follow the link on your PC)
echo 'RCLONE_REMOTE=gdrive:bmb-backup' >> .env
```
(run `rclone config` as root — `sudo rclone config` — because the nightly backup runs as root)

## Notes
* Oracle may reclaim an Always Free VM that stays idle for a long time. Keep it in use (demos, a real customer).
  Upgrading the account to Pay-As-You-Go (still ₹0 if you stay inside the free limits) avoids idle reclaim.
* The app port 8080 is bound to 127.0.0.1 — only Caddy (HTTPS) is public. MySQL is never exposed.
* Same scripts work on any Ubuntu VM (AWS, a local PC with a public IP …).

## Post-deploy tests + rollback
* Every start of the app (= every `update.sh`) runs live tests: database tables, pages/scripts over HTTP, every role of every
  company calling the read APIs (permission rules + "same status as the previous build"), money/stock ledger checks and
  "record counts did not go down". Results: login as **admin** → **🧪 Post-deploy Tests**. A failure shows as a red ALERT on the admin home.
* `update.sh` keeps the running image as `bhai-mobile-billing:prev` and records the pre-deploy DB backup in `/opt/bmb/ctl/build.json`.
* **Rollback** button: app goes back to the previous build (data kept). Tick "Database-ஐயும்…" to also restore the pre-deploy backup
  (bills made after the deploy are lost; a safety backup of the current data is taken first).
* Existing servers: run `sudo bash deploy/oracle/setup.sh <domain>` once more to create `/opt/bmb/ctl` and the watcher cron.
