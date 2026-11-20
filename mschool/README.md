# MSchool — பள்ளி / கல்லூரி / பல்கலைக்கழக மொபைல் நிர்வாக மென்பொருள்

Spring Boot 3.5 + MySQL 8 + single-file mobile web UI (installable PWA) in the **same look & feel as MBill** (green / gold, Mukta Malar + DM Sans). Languages like MBill: English (default), தமிழ், Hindi, Telugu, Kannada, Malayalam, Arabic - 🌐 button on the login page and in the bar / More; the choice is remembered on the device. Screen text is Tamil in the source; `static/i18n/en.json` holds the English, other languages are translated online (cached in table `translations`).

## Run
Part of the Bhai Suite: one Tomcat/JVM/container runs MSchool (port 8081) together with Bhai Mobile Billing (8080). See ../SUITE.md.

    docker compose up -d --build        # from the suite root;  http://<server>:8081  (set SCHOOL_PORT=8081 in .env to open it)
    mvn -pl mschool -am package         # -> mschool/target/mschool.war

Env: `MSCHOOL_DB_URL`, `MSCHOOL_DB_USER`, `MSCHOOL_DB_PASS`, `MSCHOOL_ZONE` (default Asia/Kolkata); standalone `java -jar` also `MSCHOOL_PORT` (8081).
First login: **admin / admin123** — change it immediately (More → கடவுச்சொல்).

## Flow
1. admin → "புதிய நிறுவனம்" (principal login is created with it) → open it.
2. Principal: முகப்பு → template (பள்ளி 1–12 / கல்லூரி 3 / 4 ஆண்டு) or Setup → வகுப்பு/பிரிவு (each class has an "next class" = promotion chain; none = final year → graduated).
3. Add teachers/staff (salary fields visible to principal only), students (or CSV import), fee heads + fee structure per class/year.
4. Daily: attendance (students: teacher of own section; staff: office/principal), fee collection with receipt/WhatsApp, pending list.
5. Month end: Payroll → generate (LOP from attendance) → pay.
6. Year end: Promotion → mark pass/fail per section → "ஆண்டு இறுதி உயர்வு" (pass → next class, fail → same class, final class pass → graduated; old records kept).

## Login security
Captcha on the login page, more than 10 wrong logins from one IP in a day -> IP blocked (platform admin: Institutions -> 🚫 Blocked IPs -> Unblock), footer shows "Your IP". Details and settings: ../SUITE.md "Login security".

## Roles
admin (platform) · principal · office (desk) · teacher (own class-teacher sections only: attendance + results).

## Notes
- Money is whole rupees. Unmarked staff days count as present; weekly off configurable (Setup → institution).
- CSV import is all-or-nothing (header row; see the import sheet in Students).
- Schema auto-creates on start (`db/schema.sql`); later changes go in `Migrations`.
