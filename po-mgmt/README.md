# Purchase Order Management (`po-mgmt`)

A **Centralized Purchase Order Management System** — the 5th WAR in the bhai-suite (one Tomcat, one JVM).
Mirrors the **mSchool / mHotel** architecture (Spring Boot + MySQL + HTML5, same green/gold look). Runs on
**port 8084**, database **po_mgmt**. Adding it does **not** touch MBill, MSchool, mHotel or the website.

Built to both requirement proposals (PO Management + Budget Control).

## Features
- **One purchase entry → separate branch-wise POs**, linked by a common reference (e.g. `PE-0002` →
  `10003/R1`, `10004/R2`, `10005/R3`). Same qty for all branches, or different per branch (qty factor).
- **Budget quantity control**: set approved budgets by item + branch + period; every PO is checked against the
  remaining balance; over-budget branches are **blocked** unless an authorised exception reason is supplied.
- **Masters**: 15 branches (R1–R15), suppliers **with multiple agencies**, items with a **size matrix**.
- **Approval workflow** (roles: purchase_executive, purchase_manager, approver, admin, management) + audit history.
- **Goods receiving** (ordered vs received, partial/completed).
- **Reports**: budget-vs-ordered, branch-wise, supplier-wise, item-wise, size-wise, rate comparison,
  ordered-vs-received, user-wise, approval, cancelled — plus a **dashboard**.

## First login
`admin / admin1234` (change it in More → password). Demo users: `exec1`, `mgr1`, `appr1` (password `demo1234`).

## Run (Docker Compose — adds it to the suite, prod untouched)
```bash
bash deploy/deploy-po-mgmt.sh        # or the same command you already use:
docker compose up -d --build
```
→ `http://<server>:8084/` (default localhost; set `PO_PORT=8084` in `.env` for direct access). Database
`po_mgmt` is created automatically and seeded with demo data on first start.

## Environment
| var | meaning | default |
|---|---|---|
| `POMGMT_DB_URL` / `POMGMT_DB_USER` / `POMGMT_DB_PASS` | MySQL for the `po_mgmt` schema | suite MySQL / `DB_USER` / `DB_PASS` |
| `POMGMT_ZONE` | time zone | Asia/Kolkata |
| `POMGMT_BUDGET_ENFORCEMENT` | `off` / `warn` (default) / `strict` | warn |
| `PO_PORT` | published port | 127.0.0.1:8084 |

## Notes
- Standalone (dev): `mvn -pl po-mgmt -am package` then `java -jar po-mgmt/target/po-mgmt.war` (set `POMGMT_DB_*`).
- Login uses a simple username/password + session token (not the shared suite-security captcha yet); it can be
  upgraded to suite-security later without changing the API.
- PDF print and WhatsApp/email share (spec Phase 2/3) are a presentation layer on top — the data, PO numbering
  and budget logic are all here.
