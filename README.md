# Bhai Mobile Billing — architecture notes (for future development)

> Project layout changed: this module now lives in `bhai-mobile-billing/` inside the multi-module "Bhai Suite" (see SUITE.md) and is packaged as a WAR for Tomcat. Package `com.bhai.billing`, DB access and everything below are unchanged.

Updated: 2026-11-14 (language switcher, quick add-branch, per-company website address `/s/<name>`, WhatsApp order number, Dry Fruit Palace master-data template, CSV import)

## Stack
- Spring Boot 3.5.x, Java 25 (JDK 25; Docker uses eclipse-temurin 25), MySQL 8.0.16+ (utf8mb4). WAR (also runnable as an executable war), Docker on AWS EC2. Developer works on Windows (D:\hotel\bhai-mobile-billing-springboot\bhai-mobile-billing).
- Package `com.bhai.billing`. **No JPA** — plain JDBC through `core/Db` (`query`, `one`, `update`, `insert`, `batch`, `tx`); `JdbcDb` implementation. Rows come back as `Map<String,Object>` with **lower-case** column labels.
- `config/AppConfig` builds `BillingService(new JdbcDb(ds), ZoneId bmb.zone)`; startup runner calls `service.migrate()` then creates admin/admin123 on empty DB.
- `web/ApiController` — `/api/*`, session token in `X-Auth` header, `run()` maps `ApiException` (status + Tamil message) and `DbException` to `{ "error": ... }`.
- `src/main/resources/db/schema.sql` runs on every start (CREATE TABLE IF NOT EXISTS) — it never alters existing tables. **Column changes for existing DBs go in `BillingService.migrate()`** (`hasColumn` / `hasIndex` checks, then ALTER). MySQL 8 has no `ADD COLUMN IF NOT EXISTS`.
- Production MySQL runs with ONLY_FULL_GROUP_BY — keep GROUP BY queries strict.
- `application.properties` has `spring.web.resources.cache.cachecontrol.no-cache=true` so phones reload index.html after updates.

## Roles (users.user_type)
| type | rights |
|---|---|
| admin | everything; no company of its own; opens a company with `POST /admin/open`; bills go to the company's first branch |
| owner | company-wide reports only (`requireBilling()` refuses data / catalog / bills / stock with 403); branch filter in report |
| sales | billing, catalog, bill history + report of **own branch** (was `customer`; API still maps `customer` → `sales`) |
| stock | sales + stock of own branch |
Helpers in BillingService: `Ctx.admin()/owner()/stock()`, `requireCompany`, `requireBilling`, `admin(auth)`, `userBranch(c,cid)`, `stockBranch(...)`.
Changing a user's type/company/branch deletes their tokens (new rights at next login).

## Multi-company + branch rule
- non-admin → `users.company_id` (+ `users.branch_id` for sales/stock); admin → company opened (stored in `auth_tokens.company_id`).
- Always `String cid = requireCompany(ctx(auth))` (or `requireBilling`) and filter every query by `company_id`. Never accept company id from the browser for company data.
- Every company has ≥1 branch; `ensureBranches(cid)` creates "Main" and backfills users/bills. Called from migrate() and saveCompany/saveUser.
- Branch filter in SQL: `bf(br)` appends ` AND b.branch_id = ?` as the LAST condition; `args(br, ...)` appends the param.

## Tables
companies, branches(company_id, id, name, sort_order), users(admin|owner|sales|stock, company_id, branch_id), auth_tokens, item_groups, items, item_sizes(company_id, item_id, size_label, rate, barcode NULL unique per company, sort_order), bills(id, company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total), bill_lines(company_id, bill_id, line_no, item_id NULL, item_name, item_name_en, size_label, qty, rate, amount), stock(company_id, branch_id, item_id, size_label, qty INT may be negative, updated_at, updated_by).
Indexes: `idx_bills_company_date`, `idx_bills_branch_date (company_id, branch_id, created_at)`. No discount / payment-mode columns yet.
stock has **no FK to item_sizes** on purpose (catalog save deletes and re-inserts sizes); FK to branches cascades.

## Catalog
- `PUT /catalog` replaces the whole catalog (delete groups → cascade → re-insert) with a `version` check (409 on conflict).
- JSON: cats[{id,ta,en,emoji,color}], items[{id,cat,ta,en,sizes:[{size,rate,code?}]}]. `code` = barcode; `cleanCatalog()` validates (printable ASCII 3–40) and refuses duplicates in the company.
- Frontend must preserve `code` whenever it rebuilds sizes (e.g. saveItemEdit).

## Bills and stock
- `POST /bills` lines may carry `id` (catalog item). Server keeps it only if (item, size) exists in the company catalog, stores `bill_lines.item_id`, and reduces `stock` of the user's branch (`INSERT … ON DUPLICATE KEY UPDATE qty = qty - ?`). Custom lines never touch stock.
- `PUT /stock {item, size, qty}` sets, `{item, size, add}` adds/subtracts; whole numbers, |v| ≤ 999999, negative allowed; admin passes `branch`.

## Frontend (`static/index.html`, single file)
- Hash routing via `view()`: none, home, cat, admin, setup, nocompany, report, stock. Owner is always on `report` (catalog not loaded).
- Role helpers: `isAdmin/isOwner/isStock/canEdit/canStock`, `TYPE_LABEL`, `myBranch` (from session.branch).
- `renderAll()` → `renderBrand/renderBar/renderView/renderDock`. Dialogs use the bottom sheet (`showSheet/closeSheet`, `#sheetBody`).
- One global click listener dispatching on button `id` and `data-*` attributes. `api(path, method, body)` helper. CSS tokens: `--leaf`, `--gold`, `--line`, etc. Note `.bar` is the app header class — don't reuse it.
- Cart lives in localStorage per company (`cart` keyed `itemId|size`, `customs` for custom items, which can carry `code`).
- Report state: `repData, repDate, repBranch`; `branchSalesHtml`, `userSalesHtml`, `reportText` (WhatsApp/copy).
- Stock: `stockData{branch, branchName, branches?, map}`, `loadStock`, `renderStock` (filters all/neg/zero/low, search, 📷), `openStockEdit` (Set / ± modes), `stockScan`.
- Admin: user form types sales/stock/owner/admin + branch select (`fillUBranches`); company card ➕ கிளை சேர் (`data-addbranch`, opens the branch sheet with the name box focused) and 🏬 கிளைகள் → `openBranches` (rename / delete / add shop or warehouse).
- Barcode: `byCode` map built in `buildCatalog()`. `openScanner({title,hint,done,onCode,onClose})` full-screen camera (`#scan`); `onCode` returns true to keep scanning. Native `BarcodeDetector` first, else lazy-loads `vendor/zxing.min.js` (ZXing 0.21.3, Apache-2.0). `scanToCart(code)` adds 1 piece; unknown → `openUnknownCode`. USB/Bluetooth scanners via keyboard-wedge listener. Camera needs HTTPS (`docker-compose.https.yaml`, Caddy).

## Languages (i18n) — `static/i18n.js` + `static/i18n/*.json`
- Source text in the app is Tamil. `i18n.js` (loaded by `index.html` and `shop.html`) swaps every Tamil phrase for the selected language and shows **English as a small line under it** (selected language is bold). The user picks the language with the 🌐 button on the login screen or in the ☰ menu (`mLang`); the choice is kept in `localStorage` (`bmb_lang`, `bmb_en2` = small English line on/off).
- Packs: `i18n/languages.json` (list + coverage %), `i18n/en.json` (Tamil → English, complete), `i18n/hi|te|kn|ml.json` (Tamil → translation, screens only partly translated; anything missing falls back to English). Tamil is the source, so it has no pack.
- How it works: a `MutationObserver` translates text nodes (wrapped in `<x-i18n data-src="Tamil">`), `placeholder / aria-label / title`, `<option>`, `wa.me` links; `confirm / alert / prompt`, `navigator.share`, clipboard and `window.open(wa.me…)` are wrapped; `I18N.html(str)` translates PDF / print HTML; `I18N.srcText(el)` gives the original Tamil of an element (copy buttons). Server error messages (Tamil) go through the same toast path, so no Java change is needed.
- Item / group names and customer data are NOT translated (they are user data).
- New screen text: just write Tamil in the code as before, then run `python3 i18n-tools/check_i18n.py` → lists phrases without English; add them to `i18n/en.json` (and other packs). It also writes `i18n-tools/missing-<code>.tsv` (work list for translators) and refreshes coverage.
- New language: add an entry to `i18n/languages.json`, create `i18n/<code>.json`, add the Noto Sans font of its script to the Google Fonts link / `--body` in `index.html`.

## API list
- Session: `POST /login {user, pass}`, `GET /me` (includes `branch` for sales/stock), `POST /logout`, `POST /password`
- Company data: `GET /data`, `PUT /catalog`, `GET /bills` (last 100 + today; own branch for sales/stock), `POST /bills`
- Report: `GET /report?date=yyyy-MM-dd&branch=` — scope, branches, today/day/month summaries, growth, projection, trend, hours, top items, sizes, branchSales, userSales, customers, day's bills
- Stock: `GET /stock?branch=`, `PUT /stock`
- Admin: `GET /admin/overview` (companies incl. branches, users incl. branchName), `GET /admin/sales?date=`, `POST /admin/company`, `DELETE /admin/company/{id}`, `POST /admin/user {…, type, companyId, branchId}`, `DELETE /admin/user/{id}`, `POST /admin/open`, `POST /admin/branch {companyId, id?, name}`, `DELETE /admin/branch/{companyId}/{id}` (refused for last branch, branch with users or bills)

## Build / deploy notes
`mvn -DskipTests package` → `target/bhai-mobile-billing.jar`. Run with `DB_URL`, `DB_USER`, `DB_PASS`, `PORT`, `BMB_ZONE`.
Docker: `docker compose up -d --build` (Dockerfile builds from src — without `--build` the old image keeps running). HTTPS: add `-f docker-compose.https.yaml` with `DOMAIN` in .env.
Patches on Windows: extracting the full zip over the folder is simpler than `git apply` (CRLF / already-applied conflicts).

## Ideas noted for later
- Discount and payment mode (cash / UPI / card) columns on bills → would plug into the report.
- Date-range / Excel export of the report.
- Printing barcode labels for items that have no barcode.
- Stock movement log (who changed what, bill-wise reductions) and stock transfer between branches.

## Company website (online shop) + WhatsApp orders + master data
- **Every company has its own web address**: `https://<domain>/s/<shop address>` (e.g. `/s/dry-fruit-palace`). `companies.shop_slug` (unique, a-z 0-9 `-`) is made from the company name (`CatalogImport.slugBase`, `-2`, `-3` if taken) by `migrate()` for all existing companies and by `saveCompany` for new ones. Owner / admin can change it in **🔗 Online shop link** (`POST /shop-settings {slug, wa}`). `shopCompany(code)` resolves slug **or** the old `shop_code`, so old `shop.html?s=<code>` links still work.
- `web/ShopPageController` serves `static/shop.html` for `GET /s/{slug}` (adds `<base href="/">`; the page reads the slug from the URL path). Public API stays `/api/shop/{slug-or-code}` (+ `/orders`, `/orders/{token}`, `/photo/{id}`).
- Customers order from catalogue + live stock (existing logic: stock per shop / warehouse, max 5 open orders per mobile). The customer's mobile number is mandatory. After ordering, `shop.html` opens WhatsApp with the full order (name, mobile, address, items, total, status link) to `companies.shop_wa` (blank = company phone) — `https://wa.me/91<number>?text=...&raw=1` (`raw=1` = i18n.js keeps the text in Tamil, the shop's language). Browsers may block the automatic open, so the success screen has a **💬 WhatsApp-ல் ஆர்டரை அனுப்பு** button. Orders also appear in the app under 🛒 ஆர்டர்கள் (existing).
- Admin company form: *Online ஆர்டர் WhatsApp எண்* (`shopWa`). Company card: **🌐 Website** (opens the shop), **📦 Master data**.
- **Master data templates**: `static/templates/<id>.csv` + registry `TEMPLATES` in `BillingService` (id, title, path). Only `dryfruits` now (Dry Fruit Palace: 11 groups, 165 items, 502 sizes — nuts, dates, dry fruits, seeds, pulses, oils & ghee, rice & grains, spices, grocery, gift boxes, sweets; **sample prices**, cost = 84 %, wholesale = 95 % of rate). Regenerate with a script that writes the CSV; add a template = add a CSV + one registry row.
  - Load: company form (new company → *Master data* select), **user form** (new user → select; works for the user's company), company card **📦 Master data** (`POST /admin/company/{id}/template {template, replace, stock}`), `GET /admin/templates`. Default opening stock = 100 per size in every *shop* branch (not warehouses), only where no stock row exists; 0 = none. Loading twice never duplicates (items matched by name; ids are SHA-1 of group+name).
  - A template problem never undoes the company / user that was just saved: the response then has `templateError`.
- **CSV import** (☰ → 📥 ஐட்டம் Import; admin / stock): `POST /catalog/import {csv, replace}`; columns `group, group_en, emoji, color, item, item_en, size, rate, wholesale, cost, barcode, stock` (aliases in `CatalogImport.ALIAS`; comma / tab / semicolon; UTF-8). `stock` sets the stock at the user's branch. `replace` = admin only. The sample file is `static/templates/dryfruits.csv`. Logic is in `core/CatalogImport.java` (pure functions: parse → merge into the current catalog → normal `cleanCatalog()` validation → one transaction).


## Count / weight items, rate change, typed qty / weight, barcode typing
- **Item type**: `items.sold_by` = `count` (default) | `weight`; `items.wt_unit` = `kg` | `g` (weight items use exactly one of them). A weight item has ONE size (`1 kg` / `1 g`) and its rate is the price per that unit. Set in the new-item form (🔢 Count / ⚖️ Weight); the unit cannot be changed afterwards. Custom items can also be weight items.
- **Weight on the bill**: the cashier types the weight and picks kg / g (tile and bill sheet). A bill line stores `qty` = portions (1), `rate` = price of that weight, `size_label` = weight text (`250 g`), `bill_lines.wt_g` = grams of one portion, so amount / reports / invoices / profit / cancel keep working unchanged. `cost` per line = cost per unit × weight.
- **Stock of weight items is kept in GRAMS** (`stock.qty`), reduced by `qty × wt_g` on bill / invoice and restored on cancel. Stock screen shows kg/g; the stock report values it per kg/g and leaves it out of "pieces". Weight items are NOT shown on the online shop.
- **Rate change**: `companies.rate_change` (admin → company form → ✏️ checkbox, default OFF). ON: rate boxes appear on each bill line (count: per piece, weight: per kg/g). OFF: the server rejects a catalog line whose rate differs from the catalog (`addBill`; pending lines already accepted are not re-checked).
- **Typed quantity**: the `−  [n]  +` control is an input on tiles, bill lines and the custom form (max 999).
- **Custom item**: always asks the group (`📁`); no groups at all → "பொது (General)" default group (created when saved permanently). Weight custom: weight + kg/g + rate per unit.
- **Barcode typing**: home has a 🔢 box (Enter / திற) and the scanner overlay has its own box. A registered code (case-insensitive) opens its group, highlights the card and (count items) adds 1; weight items put the cursor in the weight box; unknown codes open the link / add dialog.
- **Barcode persistence**: a scanned / typed code linked to an item is saved by `PUT /catalog` → `item_sizes.barcode` (UNIQUE per company; duplicates are rejected by `cleanCatalog` with a message).
- Migration is automatic (`migrate()`): `items.sold_by`, `items.wt_unit`, `bill_lines.wt_g`, `companies.rate_change`.
- **CSV import of weight items**: columns `sold_by` (`count` default | `weight`) and `wt_unit` (`kg` | `g`). Weight row: size is ignored (becomes `1 kg` / `1 g`), `rate` = price per unit, `stock` is in that unit (stored as grams). An existing item's type / unit cannot be changed by CSV (row skipped with a message).

## Offline mode (PWA) + "order without stock"
- **Files**: `static/sw.js` (service worker: app shell cache, network-first for `index.html` with 4 s timeout, never touches `/api/*`), `static/manifest.json` + `static/icons/*.png` (Add to Home Screen), `static/offline.js` (IndexedDB `mbill-offline`: `kv` + `outbox`; object `OFF`). `index.html` loads `offline.js` before the main script and registers `sw.js` on load.
- **What works offline**: billing, cancel bill from the cart, cancel from an earlier bill (bills of the last 62 days cached by `GET /api/sync`), bill / cancel history, WhatsApp text + PDF (libs are precached). **Online only**: exchange credit, pending save, take-order, reports, stock and catalog edits, first login.
- **Queue-first**: `sendDoc()` in index.html tries the server (12 s timeout); on network failure the document goes to the outbox with a reserved number and the cart is cleared. `OFF.sync()` (online event, tab visible, 30 s timer, after login) pushes the outbox in order, then `GET /api/sync?cv=<catalogVersion>&days=62` pulls company, pay modes, catalog (only when the version changed), bills, cancels.
- **Idempotency**: every bill / cancel carries `clientId` (UNIQUE `company_id, client_id` on `bills`, `cancel_bills`) + `deviceId`, `clientAt` (device time, used only when 2 min – 45 days old), `offline`, `catalogVersion`, `reservedSeq`. A repeated POST returns the existing document (`duplicate:true`).
- **Numbers**: `POST /api/offline/reserve` takes blocks from `companies.bill_seq` / `cn_seq` into `bill_no_blocks` (≤100 bills / ≤40 cancels per call, ≤600 per device per day). The server honours `reservedSeq` only when it is inside a block of that device and unused. When the block is empty the phone uses `PREFIX-T0001` / `PREFIX-CT0001`; the real number arrives after sync and queued cancels' `refBill` is rewritten. Reserved numbers that are never used leave gaps (GST consecutive-serial rule: check with your accountant).
- **Server policy for offline documents**: pending-bill merge skipped, exchange ignored, rate-change check skipped when `catalogVersion` differs from the server's. Over-cancel / validation errors (4xx) show as ⚠ entries in 🔄 Sync / Offline (Retry / Delete / Copy); the phone never drops them silently.
- **Session**: `sess` cached in IndexedDB; token stays in localStorage. Logout keeps the outbox (only the same user/company syncs it). Background 401 shows the 🔑 chip instead of logging out.
- **Known limits**: lost response + retry can give a different final number (the phone updates its copy and toasts it); iOS Safari may evict storage if the app is unused for weeks (use Add to Home Screen); camera scan needs HTTPS; Google Fonts are not available offline (system Tamil font).
- **Company flag `companies.order_no_stock`** (Admin → company form → "ஸ்டாக் இல்லாவிட்டாலும் ஆர்டர் எடுக்க அனுமதி"): off = `shopOrder` and `takeOrder` refuse stock-less lines (409); on = accepted, lines flagged `short`. `shop()` exposes `company.orderNoStock`; shop.html then shows "முன்பதிவு" and does not cap quantity.
- **Tests**: `/home/claude/w4/test/t_backend.py` (26 API checks) and `t_offline_ui.py` (Playwright: offline reload, offline bill / cancel x2, sync, server-down, lost-response) — kept in the delivery zip under `test/`.

## View bill + Print (Bill history)
- **Bill history** (and the daily report's bill list) rows now have **👁 பில் பார்** (full bill: lines, size / weight, totals, exchange, payment mode) and **🖨 Print**.
- The view sheet has a paper-size picker: **58 mm thermal**, **80 mm thermal** (default) or **A4**; the choice is remembered on that device (`localStorage mb.pfmt`).
- Printing uses `window.print()` on a hidden `#printArea` (`body.pbill`), so it works with any printer the phone / PC can see (USB, Wi-Fi, Bluetooth print service, RawBT, PDF).
  Roll paper: the receipt is measured and `@page` gets the exact length (Chrome ignores `height:auto`), so the roll cuts right after the footer. In the print dialog set **Margins = None, Scale = 100 %**.
- Receipt text is Tamil only (same as the WhatsApp / PDF bill, not the bilingual on-screen text); emoji are stripped for thermal printers.
- Works offline too — waiting (⏳) bills can be viewed and printed before they sync.
- Code: `billView`, `billPrintHtml`, `printBill`, `PRINT_FMT` in `index.html`; test: `test/t_billview_ui.py`.

## Purchase Order — Count / Weight lines
- Each PO line is either **Count** (pieces) or **Weight** (kg or g). Catalog weight items are added "by weight"; items not in the catalog get a 🔢 Count / ⚖️ Weight switch.
- Weight lines are stored in **grams** (`po_lines.qty`, `received_qty`); `po_lines.wt_unit` (NULL = count) is the unit the cost price is quoted in (₹ per kg / per g). Amount = cost × grams ÷ 1000 (kg) or × grams (g).
- Receiving accepts kg or g per line; stock for weight items increases in grams (same as billing). Count lines are unchanged.
- Existing databases migrate automatically (`po_lines.wt_unit`). Tests: `test/t_po_weight_api.py`, `test/t_po_weight_ui.py`.

## Suppliers screen
- Menu → **🏭 Supplier விவரம்** (also a button on the Purchase Orders page): list of suppliers with PO count / total / due, search, details, **add / edit / delete** (delete only when the supplier has no POs). Owner = view only.
- Detail has **📥 New Purchase Order for this supplier**. The PO form already lets you pick an existing supplier or **➕ புது Supplier**.
- API: `GET /suppliers` (with stats), `POST /suppliers` (add / edit by `id`), `POST /suppliers/{id}/delete`. Test: `test/t_suppliers.py`.

## Clear browser data
Menu → **🧹 Browser டேட்டா அழி**: unregisters the service worker, deletes caches, IndexedDB (offline copy + queue), localStorage / sessionStorage, then reloads to the login screen. Server data is untouched. If bills / cancellations are still unsynced it warns that they will be lost. Test: `test/t_clear_data.py`.

## Authentication — access token (Bearer), sessions, idle logout
- **Method:** login (`POST /api/login`) returns an **access token** `{access_token, token_type:"Bearer", expires_in}`. Every call sends `Authorization: Bearer <access_token>` (RFC 6750; the old `X-Auth` header is still accepted). A 401 carries `WWW-Authenticate: Bearer`.
- The token is an opaque random 256-bit value; only its SHA-256 is stored (`auth_tokens`). It is *stateful* on purpose (not a JWT), so an admin can end a session instantly and the idle timeout works. 30 days max with "remember me", 12 h without.
- **Sessions per username:** `users.max_sessions` (default **2**, 0 = unlimited). A new login beyond the limit is refused (HTTP 409) until another session ends, idles out or an admin ends it. Set when creating / editing a user.
- **Idle logout:** `users.idle_minutes` (default **10**, 0 = never). The server rejects a token unused for longer (`auth_tokens.last_seen`, refreshed by every request, at most every 15 s). The app also logs out locally after that idle time and sends a keep-alive `POST /api/ping` (every 30 s at most) while the user taps / types / scrolls. Unsynced offline bills stay on the phone.
- **Admin:** Admin Panel → **🟢 இப்போது login ஆனவர்கள்** lists every live session (user, company, device, IP, login time, last activity) with ❌ end one / "End all" for a user. `GET /api/admin/sessions`, `POST /api/admin/sessions/kill {sid | userId}`. Lowering a user's session limit drops their oldest-used sessions.
- Existing users are migrated with the defaults (2 sessions, 10 min). Tests: `test/t_sessions.py`, `test/t_sessions_ui.py`.

## Default language = English, language remembered per user
- English is the default. The **login page always opens in English** (`showLogin()` calls `I18N.set("en")`); the old device-wide `bmb_lang` localStorage value is no longer used by the billing app (the public shop `shop.html` sets `window.I18N_PERSIST=true` and still remembers the visitor's language on the device).
- A user's chosen language is stored server-side in `users.lang` (`''` = English; migrated automatically). Choosing a language in the app calls `POST /api/me/lang {lang}` (via `window.onLangPick` hook from `i18n.js`); at the next login `enter()` applies `session.user.lang`. A language picked on the login page counts as that user's choice and is saved after a successful login. The cached offline session keeps `user.lang`.
- Source strings in the code are still Tamil; English comes from `i18n/en.json`. Tests: `test/t_pricelist.py` (API: lang), `test/t_lang_price_ui.py`.

## Price list CSV (bulk price change)
- Menu → **💲 Price list CSV** (admin / stock). **Download** builds the CSV in the browser from the loaded catalog: `item_id, group, group_en, item, item_en, size, sold_by, wt_unit, rate, wholesale, [cost], barcode` (cost column only for users who may see cost; UTF-8 with BOM so Excel shows Tamil). Edit only rate / wholesale / cost, then **Upload**: a preview lists every price that will change (old → new), unchanged rows and skipped rows with reasons; **Apply** saves.
- Server: `POST /api/catalog/import {csv, pricesOnly:true, preview?:true}` → `CatalogImport.mergePrices`. Matching: `item_id` first, else group + item + size (or unique item name). **Nothing is created or deleted**; names, barcodes, stock and groups are untouched; blank cells keep the current value; rate/wholesale must be >0, cost ≥0. Apply bumps `companies.catalog_version` (other phones refresh); preview changes nothing. Sales role is refused. Test: `test/t_pricelist.py`, `test/t_lang_price_ui.py`.

## Online translation of master data + Arabic (RTL)
- **English shows only English** (no second line, nothing in Tamil); every other language shows itself + a small English line (Tamil → Tamil + English; Arabic → Arabic + English). Arabic (`ar`) is right-to-left (`dir="rtl"`, `html.rtl` CSS, Noto Sans Arabic).
- Anything the language pack does not have — **item / group / other master data typed in Tamil**, new screen text, whole languages without a pack (Arabic) — is translated **online by the server**: `POST /api/translate {to:["en"], items:[{s:"<Tamil>", e:"<English hint>"}]}` (open endpoint for the login page / public shop; rate limited 120 calls/min per IP; max 150 texts of 300 chars; only Tamil texts and the app's own English labels `i18n/labels.json` are accepted — it is not an open translation proxy). Results are stored in table `translations` (md5 of source + language), so each text is translated online once for everybody; the browser also caches them (`localStorage bmb_dyn_<lang>`). English names a user typed (`item_en`, `group_en`) are used first (`I18N.learn`).
- Provider: free Google web endpoint by default (`translate.url`, needs internet from the server); for a paid/official one set `TRANSLATE_GOOGLE_KEY` (Google Cloud Translation v2). `TRANSLATE_URL` can point to any gtx-compatible endpoint (tests use `test/fake_translate.py`).
- Print / PDF documents wait up to 5 s for missing translations (`I18N.settle`) before rendering.
- Optional pre-built pack (instant first screen, works offline): `python3 i18n-tools/gen_pack.py ar http://localhost:8080`.
- Tests: `test/t_translate_api.py`, `test/t_lang_online_ui.py` (start `python3 test/fake_translate.py 8191`, run the app with `TRANSLATE_URL=http://127.0.0.1:8191/t`).

## Share a user's login on WhatsApp (admin)
- Admin → Users → each user card has **📲 WhatsApp**. The sheet asks for the WhatsApp number (pre-filled with the company phone; 10 digits get the country code 91, or type it with country code) and a password (🎲 makes a random one). The message contains the company details (name, owner, address, city, phone, email, GSTIN), the user (name, type, branch), **Username, Password and the login URL**. Buttons: send on WhatsApp / Copy.
- The server stores only a password hash, so an old password cannot be shown: a password typed here is first **set on the user** (`POST /admin/user`) and then sent; passwords set in this browser session (new user form, this sheet) are remembered in memory and pre-filled. Test: `test/t_share_user_ui.py`.

## Pre-order (முன்பதிவு) — online shop (overlay _15)
- Company setting **Pre-order** (`companies.order_no_stock`) is now **ON for every new company** (existing companies keep their value — tick it in Company settings). When ON, "Place Order" works even when stock is 0; short lines are saved with `short` and shown as 📦 in admin Orders/Order detail, in the customer's cart, success page and the WhatsApp order text.
- When OFF, an order above stock is refused with HTTP 409.
- Fix: weight items (fish, meat — `sold_by=weight`) can now be ordered from the shop in whole kg/g steps (earlier server answered "ஆர்டர் ஐட்டம் தவறு"); stock (kept in grams) is converted to kg for the check.
- Test: `test/t_preorder_ui.py`.

## Weight items in the online shop (overlay _16)
- Weight items (`sold_by=weight`) are ordered by **weight**: chips (250 g / 500 g / 1 kg / 2 kg), a number box with kg/g selector and ± steps; price shown live. Count items keep the + / − piece stepper. Cart, success page, WhatsApp text and admin order screens show "750 g" for weight lines.
- Order line for a weight item: `size="750 g"`, `qty=1`, `rate=amt`, `wt=grams`, `short=grams` (same shape as staff orders); "Cart-ல் ஏற்று" loads it into the billing cart as grams. Limits 10 g – 99 kg per line.
- Pre-order is switched ON once for all existing companies (migration checks the old column default); the cart now shows the server error inline (red text above the button) as well as the toast.
- Test: `test/t_weight_order_ui.py`.

## System user type (overlay _17)
- New user type **system** (🛡) next to admin / owner / sales / stock / warehouse. System = every Admin function + the only type that can create **Admin / System** users; it sees every company and user, Software Invoices (platform billing) and Post-deploy Tests / Rollback.
- **Admin** can no longer create / promote Admin or System users (HTTP 403). An admin sees and manages only the **companies and users he created** (`companies.created_by`, `users.created_by`): overview, open company, edit / delete, branches, pay modes, templates, sales, sessions, invoices of his companies. Another admin's companies and users are hidden (404).
- Migration: users.user_type gets `system`, `created_by` columns are added, and the oldest existing admin becomes the **System** user (other existing admins keep the Admin type and, having created nothing, see nothing until they create their own companies — System can edit them). At least one active System user must remain. A fresh install creates `admin / admin1234` as System.
- Tests: `test/t_system_user.py` (API, 21 checks), `test/t_system_ui.py`.

- Post-deploy tests (overlay _18): the admin-wide checks now use the **System** user (falling back to a plain admin), `/api/admin/invoices` is checked only with a System user, and "at least one active admin" means an active **System or Admin** — the system works with no plain admin at all.

- Migration fix (overlay _19): companies and company users that existed before the System type (no `created_by`) are assigned to the System user at start-up, so the System user sees all legacy companies (the first overlay left them ownerless, visible to nobody but a System user whose list was empty for admins).
- Religious greeting (bismillah) and the One-God salutation were removed from bills, receipts, WhatsApp texts, login/home screens and the shop page — the app is religion-neutral (shop page / WhatsApp greeting is now "வணக்கம்"). `companies.show_bismillah` column stays (always 0, never read); `migrate()` zeroes it.
