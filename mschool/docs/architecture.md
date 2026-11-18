# MSchool — Design Document

Mobile-first administration software for schools / colleges / universities. Same stack and style as MBill (Bhai Mobile Billing): Spring Boot + plain JDBC + MySQL, single-file vanilla-JS Tamil UI.

## 1. Stack
- Spring Boot 3.5.7, Java 17 target, plain JDBC (`Db` interface / `JdbcDb`, rows = Map with lower-case keys, `tx()`); no JPA.
- MySQL 8 utf8mb4. `db/schema.sql` (CREATE TABLE IF NOT EXISTS) runs on every start; later ALTERs go in `Migrations`.
- `Core` wires services (no Spring types); `start()` runs migrations + `ensureAdmin()` (admin/admin123 — change immediately).
- Frontend: `static/index.html`, max-width 520px, bottom dock, bottom-sheet dialogs, PWA manifest + icon.
- Env: MSCHOOL_DB_URL, MSCHOOL_DB_USER, MSCHOOL_DB_PASS, MSCHOOL_ZONE (default Asia/Kolkata). Packaged as WAR; runs in the shared Tomcat of the Bhai Suite on :8081.

## 2. Auth and roles
- PBKDF2WithHmacSHA256 (120k iterations, salt); token in `X-Auth` header, 30-day tokens.
- `Ctx` = userId, type, instId, staffId, name, token. Institution id is never taken from the request: admin opens one via `/admin/open` (stored in auth_tokens.inst_id).
- Roles: admin (platform), principal (head), office (desk), teacher (own class-teacher sections only: attendance + promotion results). `head()` = admin|principal; `desk()` = head|office.
- Salary fields and payroll: head only. Concessions: head only.

## 3. Data model (19 tables)
institutions, academic_years, classes (next_class_id chain; null = final year), sections (room, class_teacher), enrollments (per year: class, section, roll_no, result pass/fail), students, staff (teacher|staff; basic/allowance/deduction), student_attendance (P/A/L), staff_attendance (P/A/H/L), fee_heads, fee_structure (year, class, head), fee_concessions, fee_receipts (+lines, cancel, per-institution sequence R-000001), payroll (draft/paid), rooms, room_maintenance, users, auth_tokens.
- Money = whole rupees (BIGINT).
- Templates: school (LKG–12), college 3-year, college 4-year.

## 4. Key rules
- **Promotion**: teachers/head mark pass/fail per section. `run` creates the new year; pass → next class (same section name, else first, else create "A"); fail → same class/section; pass in final class → student status graduated; optional default result for unmarked; optional copy of fee structure. Old enrollments are kept as history.
- **Fees**: receipt creation locks the student row (FOR UPDATE), rejects over-balance payment; receipts can be cancelled with a reason. Pending dues support all years (carried-over dues). Modes: Cash/UPI/Card/Cheque/Bank.
- **Attendance**: students P/A/L per section per day; staff P/A/H(half)/L. Unmarked staff days count as present.
- **Payroll**: LOP = absent + 0.5 × half-days; weekly off configurable (default Sunday); net = basic + allowance − deduction − LOP + bonus − other. Draft → paid; regenerate keeps bonus/deductions.
- **CSV import** of students is all-or-nothing validation.

## 5. API (all under /api)
login, me, logout, password, data, dashboard, users, admin/overview, admin/institution, institution, admin/open, year, class, section, setup/template, rooms + maintenance, students (+import, status), staff (+status), attendance (sheet, day, month, staff, staff/month), fees (head, structure, student, concession, receipt, receipts, cancel, pending, report), payroll (list, generate, update, pay, payall, delete), promotion (summary, sheet, results, run).

## 6. UI
Views: home dashboard, students, staff, attendance (students/staff/report), fees (collect/pending/report/setup), payroll, rooms, promotion, setup, users, admin institutions. Global click/change/input delegation with registries A / C / I / LOAD / VIEW / AFTER.

## 7. Verification status (honest)
- Verified: backend logic via SQLite harness with real controller + core (e2e all checks pass); SQL syntax/columns checked for MySQL with sqlglot; Java compiled with Spring annotation stubs; UI walked in Chrome (390px) with no JS errors.
- NOT verified: real Maven build, Spring wiring, real MySQL runtime. First build may need small fixes.

## 8. Run
Bhai Suite: `docker compose up -d --build` → http://localhost:8081 (MBill on :8080). See SUITE.md.