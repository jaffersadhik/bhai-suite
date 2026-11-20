# Bhai Suite — Bhai Mobile Billing + MSchool in ONE project, ONE Tomcat, ONE JVM

```
pom.xml                      parent (Spring Boot 3.5.16, Java 25) — modules below
bhai-mobile-billing/         module 1  ->  bhai-mobile-billing.war   (MBill, database "bmb")
mschool/                     module 2  ->  mschool.war               (MSchool, database "mschool")
tomcat/server.xml            ONE Tomcat, two <Service>s:  :8080 = MBill (ROOT.war)   :8081 = MSchool (ROOT.war)
Dockerfile                   builds both WARs, runs them in tomcat:10.1-jre25-temurin-noble (one container)
docker-compose.yaml          mysql + one-shot "mschool-db" (creates DB + user) + app (both apps)
docker-compose.https.yaml    Caddy: DOMAIN -> :8080, SCHOOL_DOMAIN -> :8081
deploy/oracle/*.sh           your existing setup / update / rollback / backup / restore (now also backs up mschool)
```

## Build
    mvn -DskipTests package           # -> bhai-mobile-billing/target/bhai-mobile-billing.war, mschool/target/mschool.war
    mvn -pl mschool -am package       # only one module

## Deploy without Docker (your own Tomcat 10.1+, Java 17+; Java 25 for MBill as before)
Copy `tomcat/server.xml` into `$CATALINA_BASE/conf/`, then
`bhai-mobile-billing.war` -> `$CATALINA_BASE/webapps/ROOT.war`, `mschool.war` -> `$CATALINA_BASE/webapps-school/ROOT.war`
and export the environment variables below before starting Tomcat.

## Context path = "/" for both (nothing changes inside the apps)
Both WARs are deployed as ROOT in their **own Tomcat Service/port** (8080 and 8081), so every URL inside the apps
(`/api/...`, `/index.html`, `/s/<shop>`) stays exactly as it was. Phones/browsers of MBill keep using the same address.
If you prefer path-based addresses (`/mbill`, `/school`) put Caddy/Nginx in front and map the path to the ports — do not rename
the WARs, the front-ends call absolute `/api/...` URLs.

## Environment (each app has its own names, so the shared container environment never mixes them)
| app | variables |
|---|---|
| MBill (unchanged) | `DB_URL` `DB_USER` `DB_PASS` `BMB_ZONE` `TRANSLATE_GOOGLE_KEY` `BMB_BUILD` `BMB_CTL` `BMB_SELFTEST_PORT` (default 8080) |
| MSchool (new) | `MSCHOOL_DB_URL` `MSCHOOL_DB_USER` `MSCHOOL_DB_PASS` `MSCHOOL_ZONE` (standalone only: `MSCHOOL_PORT`). In Docker they are filled from `DB_USER` / `DB_PASS` — same credentials as MBill |

Your existing production `.env` keeps working without any edit (MSchool uses the same `DB_USER` / `DB_PASS`).

## Login security (captcha, IP block, "Your IP")
Shared module `suite-security` (packed into both WARs). Same behaviour in MBill and MSchool:
- **Captcha** on the login page: 5 characters (letters + digits, no look-alikes such as O/0 or I/1), not case sensitive, drawn as an image, new picture after every attempt; tap the picture or ↻ for another one. The answer is kept on the server and works once.
- **IP block**: more than `LOGIN_MAX_FAILS` (10) wrong user/password attempts from one IP **in one day** -> that IP is blocked (the 11th attempt is refused and the block is stored in the table `ip_block` of each app's own schema). Successful logins do not reset the count. IPv6 addresses are counted per /64.
- **Unblock**: MBill -> Admin -> **Blocked IPs** (System user only). MSchool -> platform admin -> Institutions -> **Blocked IPs**. If the System user's own IP got blocked: `bash deploy/oracle/unblock-ip.sh` (list) / `bash deploy/oracle/unblock-ip.sh <ip>`.
- **"Your IP"** is shown in the footer of MBill, MSchool and the website (and on the login pages).
- Settings (`.env`, see `docker-compose.yaml`): `LOGIN_CAPTCHA`, `LOGIN_MAX_FAILS`, `LOGIN_MAX_CAPTCHA_FAILS`, `IP_BLOCK_HOURS` (0 = until unblocked), `IP_BLOCK_EXEMPT`.
- **Real client IP**: `tomcat/server.xml` has a `RemoteIpValve` in both Tomcat hosts. It trusts `X-Forwarded-For` only when the TCP peer is a private / Docker address (= Caddy). Caddy overwrites the header, so a visitor cannot fake his IP. The apps read `request.getRemoteAddr()` only.
- Be aware: people behind one shared address (mobile-operator CGNAT, an office) share the counter - one person typing the wrong password 11 times can lock the others; use `IP_BLOCK_HOURS` (e.g. 24) or `IP_BLOCK_EXEMPT` for such addresses. A captcha stops casual guessing, not a determined attacker with a solving service - the IP block and strong passwords are the real protection.

## Moving production (MBill already running) to the suite
1. Copy this project over the old one (or `git pull`), keep your `.env`.
2. `bash deploy/oracle/update.sh` (backs up `bmb`, keeps the old image as `:prev`, rebuilds, restarts). MBill's data is untouched.
3. MBill: same address/port as before. MSchool: `http://<server>:8081` (set `SCHOOL_PORT=8081` in `.env` to open it) or
   put `SCHOOL_DOMAIN=school.example.com` in `.env` (DNS A record -> server) and Caddy serves it with HTTPS.
4. MSchool first login `admin / admin123` — change at once.

## One MySQL server, two schemas, one set of credentials
`bmb` (MBill) and `mschool` (MSchool) are two schemas on the same MySQL; both apps log in with the same `DB_USER` / `DB_PASS`.
The one-shot `mschool-db` service creates schema `mschool` and grants that user rights on it at every `docker compose up`
(manual alternative: `db/create-mschool-schema.sql`, run once as root).

## Backup — one job, one folder, one file per schema (deliberately)
`deploy/oracle/backup.sh` (same cron, same `MYSQL_BACKUP_DIR`, same retention, same rclone off-site copy) writes
`bmb-<date>.sql.gz` and `mschool-<date>.sql.gz`. Separate files are better here: MBill's "rollback with database" restores only `bmb`
and never touches school data; either app can be restored alone (`restore.sh <file>` picks the schema from the file name).

## Trade-offs of one JVM (be aware)
- One crash / OutOfMemory / restart stops **both** apps. `docker compose restart app` restarts both; MBill's rollback (`rollback.sh`) swaps the image for both.
- Heap and GC are shared; `JAVA_OPTS` in the Dockerfile (MaxRAMPercentage=75) applies to both.
- If MSchool cannot reach its database it fails to start by itself; MBill keeps running (Tomcat starts each WAR independently), but the container health check turns "unhealthy".
