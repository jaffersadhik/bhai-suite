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
