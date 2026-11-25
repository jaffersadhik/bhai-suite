package com.bhai.billing.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * MBill (Mobile Bill) - all business rules.
 * Company separation: the company is ALWAYS resolved from the login session (never taken from the request),
 * and every company-data query is filtered by that company_id.
 */
public class BillingService {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");
    static final Set<String> USER_TYPES = Set.of("admin", "owner", "sales", "stock", "warehouse", "system");
    static final Pattern MOBILE = Pattern.compile("[6-9][0-9]{9}");
    static final Pattern GSTIN = Pattern.compile("[0-9]{2}[A-Z0-9]{13}");
    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{1,8}");
    static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** printable ASCII, no spaces - covers EAN/UPC/Code128/Code39/QR text used on garment tags */
    static final Pattern BARCODE = Pattern.compile("[\\x21-\\x7E]{1,40}");
    static final String DEFAULT_FOOTER = "நன்றி! மீண்டும் வருக ❤️";
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Db db;
    private final ZoneId zone;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();

    public BillingService(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    /** Session of the calling user. */
    static final class Ctx {
        final String key;
        final Map<String, Object> user;
        String activeCompany;

        Ctx(String key, Map<String, Object> user, String activeCompany) {
            this.key = key;
            this.user = user;
            this.activeCompany = activeCompany;
        }

        /** admin-level: admin and system both have every admin function */
        boolean admin() { return "admin".equals(type()) || "system".equals(type()); }

        /** system: the top user — sees every company and user, and is the only one who creates admin / system users */
        boolean system() { return "system".equals(type()); }

        String type() { return str(user.get("user_type")); }

        /** owner: reports of the whole company only (no billing) */
        boolean owner() { return "owner".equals(type()); }

        /** stock: everything sales can do + stock maintenance */
        boolean stock() { return "stock".equals(type()); }

        /** warehouse: receives purchase orders, keeps warehouse stock, sends stock to the shops (no billing) */
        boolean warehouse() { return "warehouse".equals(type()); }

        boolean sales() { return "sales".equals(type()); }

        /** cost price (அடக்க விலை) and profit: admin, owner, stock, warehouse — never sales staff */
        boolean seesCost() { return !"sales".equals(type()); }

        String uid() { return str(user.get("id")); }
    }

    // ================================================================ startup
    /** default password for the first admin and for new users created without one (demo phase) */
    static final String DEFAULT_PASSWORD = "admin1234";
    static final int DEFAULT_MAX_SESSIONS = 2;      // simultaneous logins per username
    static final int DEFAULT_IDLE_MINUTES = 10;     // auto-logout when unused

    /** First start: create admin / admin1234. */
    /**
     * Upgrades an existing database (schema.sql only creates missing tables, it never changes them).
     * Safe to run at every start: each step checks information_schema first.
     */
    public void migrate() {
        if (!hasColumn("companies", "shop_code")) {
            db.update("ALTER TABLE companies ADD COLUMN shop_code VARCHAR(16) NULL");
            db.update("ALTER TABLE companies ADD COLUMN shop_enabled TINYINT(1) NOT NULL DEFAULT 1");
            db.update("UPDATE companies SET bill_footer = REPLACE(bill_footer, '🙏', '❤️') WHERE bill_footer LIKE '%🙏%'");
        }
        for (Map<String, Object> r : db.query("SELECT id FROM companies WHERE shop_code IS NULL"))
            db.update("UPDATE companies SET shop_code = ? WHERE id = ?", hex(bytes(5)), str(r.get("id")));
        if (!hasIndex("companies", "uk_companies_shop")) db.update("ALTER TABLE companies ADD UNIQUE KEY uk_companies_shop (shop_code)");
        // every company gets its own web address /s/<shop_slug> (name based, editable) and an optional WhatsApp number for online orders
        if (!hasColumn("companies", "shop_slug")) {
            db.update("ALTER TABLE companies ADD COLUMN shop_slug VARCHAR(40) NULL");
            db.update("ALTER TABLE companies ADD COLUMN shop_wa CHAR(10) NULL");
        }
        for (Map<String, Object> r : db.query("SELECT id, name FROM companies WHERE shop_slug IS NULL ORDER BY created_at, id"))
            db.update("UPDATE companies SET shop_slug = ? WHERE id = ?", newSlug(str(r.get("name")), str(r.get("id"))), str(r.get("id")));
        if (!hasIndex("companies", "uk_companies_slug")) db.update("ALTER TABLE companies ADD UNIQUE KEY uk_companies_slug (shop_slug)");
        if (!hasColumn("bills", "exchange_amount")) {
            db.update("ALTER TABLE bills ADD COLUMN exchange_amount DECIMAL(12,2) NOT NULL DEFAULT 0");
            db.update("ALTER TABLE bills ADD COLUMN exchange_ref VARCHAR(200) NOT NULL DEFAULT ''");
        }
        if (!hasColumn("cancel_bills", "used_bill_no")) {
            db.update("ALTER TABLE cancel_bills ADD COLUMN used_bill_no VARCHAR(30) NOT NULL DEFAULT ''");
            db.update("ALTER TABLE cancel_bills ADD COLUMN used_at DATETIME NULL");
        }
        if (hasTable("shop_orders") && !hasColumn("shop_orders", "taken_by")) {
            db.update("ALTER TABLE shop_orders ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'online'");
            db.update("ALTER TABLE shop_orders ADD COLUMN taken_by VARCHAR(60) NOT NULL DEFAULT ''");
        }
        // supplier payments ledger: an opening row for POs that had "paid" before the ledger existed
        db.update("INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) "
                + "SELECT p.company_id, p.id, p.paid, DATE(p.created_at), 'Cash', 'opening', NOW(), p.created_by_name FROM purchase_orders p "
                + "WHERE p.paid > 0 AND NOT EXISTS (SELECT 1 FROM po_payments x WHERE x.po_id = p.id)");
        if (!hasColumn("item_sizes", "wrate")) db.update("ALTER TABLE item_sizes ADD COLUMN wrate DECIMAL(10,2) NULL AFTER rate");   // wholesale selling price
        if (!hasColumn("bills", "bill_kind")) {
            db.update("ALTER TABLE bills ADD COLUMN bill_kind VARCHAR(8) NOT NULL DEFAULT 'bill', ADD COLUMN party_id VARCHAR(20) NULL, "
                    + "ADD COLUMN due_date DATE NULL, ADD COLUMN taxable DECIMAL(12,2) NOT NULL DEFAULT 0, ADD COLUMN gst_pct DECIMAL(5,2) NOT NULL DEFAULT 0, "
                    + "ADD COLUMN gst_amount DECIMAL(12,2) NOT NULL DEFAULT 0, ADD COLUMN paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0, ADD COLUMN inv_notes VARCHAR(300) NOT NULL DEFAULT ''");
            db.update("ALTER TABLE bills ADD KEY idx_bills_party (company_id, party_id)");
        }
        db.update("UPDATE platform_invoices SET inv_no = CONCAT('MBILL-', SUBSTRING(inv_no, 5)) WHERE inv_no LIKE 'BMB-%'");   // renamed: BMB -> MBILL
        if (!hasColumn("companies", "order_seq")) db.update("ALTER TABLE companies ADD COLUMN order_seq BIGINT NOT NULL DEFAULT 0");
        if (!hasColumn("companies", "cn_seq")) db.update("ALTER TABLE companies ADD COLUMN cn_seq BIGINT NOT NULL DEFAULT 0");
        if (!hasColumn("platform_invoices", "cancel_count")) db.update("ALTER TABLE platform_invoices ADD COLUMN cancel_count INT NOT NULL DEFAULT 0 AFTER bill_count");
        if (!hasColumn("companies", "bill_fee")) db.update("ALTER TABLE companies ADD COLUMN bill_fee DECIMAL(8,2) NOT NULL DEFAULT 0.25");
        if (!hasColumn("bills", "platform_fee")) {
            db.update("ALTER TABLE bills ADD COLUMN platform_fee DECIMAL(8,2) NULL");
            db.update("UPDATE bills b JOIN companies c ON c.id = b.company_id SET b.platform_fee = c.bill_fee WHERE b.platform_fee IS NULL");
        }
        if (!hasColumn("item_sizes", "barcode"))
            db.update("ALTER TABLE item_sizes ADD COLUMN barcode VARCHAR(40) NULL AFTER rate");
        if (!hasColumn("item_sizes", "cost"))
            db.update("ALTER TABLE item_sizes ADD COLUMN cost DECIMAL(10,2) NULL AFTER rate");
        if (!hasIndex("item_sizes", "uk_sizes_barcode"))
            db.update("ALTER TABLE item_sizes ADD UNIQUE KEY uk_sizes_barcode (company_id, barcode)");

        // user types: customer -> sales, new owner / stock
        Map<String, Object> ut = db.one("SELECT column_type AS t FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'user_type'");
        if (ut != null && !str(ut.get("t")).contains("'sales'")) {
            if (db.one("SELECT 1 AS x FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 'users' AND constraint_name = 'chk_users_company'") != null)
                db.update("ALTER TABLE users DROP CONSTRAINT chk_users_company");
            db.update("ALTER TABLE users MODIFY user_type ENUM('admin','customer','owner','sales','stock') NOT NULL");
            db.update("UPDATE users SET user_type = 'sales' WHERE user_type = 'customer'");
            db.update("ALTER TABLE users MODIFY user_type ENUM('admin','owner','sales','stock') NOT NULL");
            db.update("ALTER TABLE users ADD CONSTRAINT chk_users_company CHECK ((user_type = 'admin' AND company_id IS NULL) OR (user_type <> 'admin' AND company_id IS NOT NULL))");
        }
        Map<String, Object> ut2 = db.one("SELECT column_type AS t FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'user_type'");
        if (ut2 != null && !str(ut2.get("t")).contains("'warehouse'"))
            db.update("ALTER TABLE users MODIFY user_type ENUM('admin','owner','sales','stock','warehouse') NOT NULL");
        Map<String, Object> ut3 = db.one("SELECT column_type AS t FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'user_type'");
        if (ut3 != null && !str(ut3.get("t")).contains("'system'")) {            // new user type: system
            if (db.one("SELECT 1 AS x FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 'users' AND constraint_name = 'chk_users_company'") != null)
                db.update("ALTER TABLE users DROP CONSTRAINT chk_users_company");
            db.update("ALTER TABLE users MODIFY user_type ENUM('admin','owner','sales','stock','warehouse','system') NOT NULL");
            db.update("ALTER TABLE users ADD CONSTRAINT chk_users_company CHECK ((user_type IN ('admin','system') AND company_id IS NULL) OR (user_type NOT IN ('admin','system') AND company_id IS NOT NULL))");
        }
        if (!hasColumn("users", "created_by")) db.update("ALTER TABLE users ADD COLUMN created_by VARCHAR(20) NULL");
        if (!hasColumn("companies", "created_by")) db.update("ALTER TABLE companies ADD COLUMN created_by VARCHAR(20) NULL");
        // the first (oldest) admin becomes the system user when there is none yet
        if (lng(db.one("SELECT COUNT(*) AS n FROM users WHERE user_type = 'system'").get("n")) == 0)
            db.update("UPDATE users SET user_type = 'system' WHERE id = (SELECT id FROM (SELECT id FROM users WHERE user_type = 'admin' ORDER BY created_at, id LIMIT 1) x)");
        {   // companies and company users created before the System type existed (no owner yet) belong to the System user, so it sees all of them
            Map<String, Object> sy = db.one("SELECT id FROM users WHERE user_type = 'system' ORDER BY active DESC, created_at, id LIMIT 1");
            if (sy != null) {
                db.update("UPDATE companies SET created_by = ? WHERE created_by IS NULL", str(sy.get("id")));
                db.update("UPDATE users SET created_by = ? WHERE created_by IS NULL AND user_type NOT IN ('admin', 'system')", str(sy.get("id")));
            }
        }
        if (!hasColumn("branches", "kind")) db.update("ALTER TABLE branches ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'shop' AFTER name");
        if (!hasColumn("companies", "tr_seq")) db.update("ALTER TABLE companies ADD COLUMN tr_seq BIGINT NOT NULL DEFAULT 0 AFTER bill_seq");
        if (!hasColumn("users", "branch_id")) db.update("ALTER TABLE users ADD COLUMN branch_id VARCHAR(20) NULL AFTER company_id");
        if (!hasColumn("bills", "branch_id")) db.update("ALTER TABLE bills ADD COLUMN branch_id VARCHAR(20) NULL AFTER company_id");
        if (!hasIndex("bills", "idx_bills_branch_date")) db.update("ALTER TABLE bills ADD KEY idx_bills_branch_date (company_id, branch_id, created_at)");
        if (!hasColumn("bill_lines", "item_id")) db.update("ALTER TABLE bill_lines ADD COLUMN item_id VARCHAR(40) NULL AFTER line_no");
        if (!hasColumn("companies", "po_seq")) db.update("ALTER TABLE companies ADD COLUMN po_seq BIGINT NOT NULL DEFAULT 0 AFTER bill_seq");
        if (!hasColumn("items", "sold_by")) db.update("ALTER TABLE items ADD COLUMN sold_by VARCHAR(6) NOT NULL DEFAULT 'count'");   // count | weight
        if (!hasColumn("items", "wt_unit")) db.update("ALTER TABLE items ADD COLUMN wt_unit VARCHAR(2) NOT NULL DEFAULT 'kg'");      // weight items: kg | g
        if (!hasColumn("users", "max_sessions")) db.update("ALTER TABLE users ADD COLUMN max_sessions TINYINT NOT NULL DEFAULT 2 AFTER must_change");
        if (!hasColumn("users", "idle_minutes")) db.update("ALTER TABLE users ADD COLUMN idle_minutes INT NOT NULL DEFAULT 10 AFTER max_sessions");
        if (!hasColumn("users", "lang")) db.update("ALTER TABLE users ADD COLUMN lang VARCHAR(5) NOT NULL DEFAULT '' AFTER idle_minutes");
        if (!hasColumn("auth_tokens", "last_seen")) db.update("ALTER TABLE auth_tokens ADD COLUMN last_seen DATETIME NULL");
        if (!hasColumn("auth_tokens", "ip")) db.update("ALTER TABLE auth_tokens ADD COLUMN ip VARCHAR(45) NULL");
        if (!hasColumn("auth_tokens", "device")) db.update("ALTER TABLE auth_tokens ADD COLUMN device VARCHAR(120) NULL");
        if (!hasColumn("po_lines", "wt_unit")) db.update("ALTER TABLE po_lines ADD COLUMN wt_unit VARCHAR(2) NULL");                 // PO weight lines: qty in grams, price per kg | g
        if (!hasColumn("purchase_orders", "assigned_user")) db.update("ALTER TABLE purchase_orders ADD COLUMN assigned_user VARCHAR(30) NOT NULL DEFAULT '' AFTER created_by_name");   // warehouse / stock user allocated to the PO
        if (!hasColumn("purchase_orders", "assigned_name")) db.update("ALTER TABLE purchase_orders ADD COLUMN assigned_name VARCHAR(60) NOT NULL DEFAULT '' AFTER assigned_user");
        if (!hasIndex("purchase_orders", "idx_po_assigned")) db.update("ALTER TABLE purchase_orders ADD KEY idx_po_assigned (company_id, assigned_user, status)");
        if (!hasColumn("bill_lines", "wt_g")) db.update("ALTER TABLE bill_lines ADD COLUMN wt_g INT NULL");                           // grams of one portion (weight lines)
        if (!hasColumn("companies", "rate_change")) db.update("ALTER TABLE companies ADD COLUMN rate_change TINYINT(1) NOT NULL DEFAULT 0");
        if (!hasColumn("bill_lines", "cost")) db.update("ALTER TABLE bill_lines ADD COLUMN cost DECIMAL(10,2) NULL AFTER rate");   // unit cost at billing time
        if (!hasColumn("bills", "pay_mode_id")) {
            db.update("ALTER TABLE bills ADD COLUMN pay_mode_id VARCHAR(20) NULL AFTER total, ADD COLUMN pay_name VARCHAR(30) NOT NULL DEFAULT '' AFTER pay_mode_id, "
                    + "ADD COLUMN pay_kind VARCHAR(8) NOT NULL DEFAULT '' AFTER pay_name");
        }
        // offline billing: a bill / cancel bill made on a device without internet is sent later. client_id (made on the device) makes sending it twice harmless.
        for (String tb : new String[]{"bills", "cancel_bills"}) {
            if (!hasColumn(tb, "client_id"))
                db.update("ALTER TABLE " + tb + " ADD COLUMN client_id VARCHAR(40) NULL, ADD COLUMN device_id VARCHAR(40) NULL, "
                        + "ADD COLUMN offline TINYINT(1) NOT NULL DEFAULT 0, ADD COLUMN synced_at DATETIME NULL");
            if (!hasIndex(tb, "uk_" + tb + "_client")) db.update("ALTER TABLE " + tb + " ADD UNIQUE KEY uk_" + tb + "_client (company_id, client_id)");
        }
        // company option: take online / staff orders even when the item has no stock (off = no order without stock)
        if (!hasColumn("companies", "order_no_stock")) db.update("ALTER TABLE companies ADD COLUMN order_no_stock TINYINT(1) NOT NULL DEFAULT 0");
        // pre-order is ON for every NEW company (existing companies keep their own choice): a customer can order an item that has no stock yet
        {   // one time: existing companies (default was 0) are switched to pre-order ON as well
            Map<String, Object> cd = db.one("SELECT COLUMN_DEFAULT AS d FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companies' AND COLUMN_NAME = 'order_no_stock'");
            if (cd != null && "0".equals(str(cd.get("d")))) db.update("UPDATE companies SET order_no_stock = 1");
        }
        db.update("ALTER TABLE companies ALTER COLUMN order_no_stock SET DEFAULT 1");
        // the religious greeting on bills was removed: switch the old flag off everywhere (column kept, never read)
        if (hasColumn("companies", "show_bismillah")) { db.update("ALTER TABLE companies ALTER COLUMN show_bismillah SET DEFAULT 0"); db.update("UPDATE companies SET show_bismillah = 0 WHERE show_bismillah <> 0"); }
        ensureBranches(null);
        ensurePayModes(null);
    }

    private boolean hasColumn(String table, String col) {
        return db.one("SELECT 1 AS x FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", table, col) != null;
    }

    private boolean hasIndex(String table, String idx) {
        return db.one("SELECT 1 AS x FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? LIMIT 1", table, idx) != null;
    }

    /**
     * Every company has at least one branch ("Main"); sales / stock users and bills without a branch go to the
     * company's first branch. cid = one company, or null for all companies.
     */
    private void ensureBranches(String cid) {
        List<Map<String, Object>> cs = cid == null ? db.query("SELECT id FROM companies") : db.query("SELECT id FROM companies WHERE id = ?", cid);
        for (Map<String, Object> co : cs) {
            String id = str(co.get("id"));
            if (db.one("SELECT 1 AS x FROM branches WHERE company_id = ? LIMIT 1", id) == null)
                db.update("INSERT INTO branches (company_id, id, name, sort_order, created_at) VALUES (?, ?, ?, 0, ?)", id, newId("b"), "Main", now());
            String first = firstBranch(id);
            db.update("UPDATE users SET branch_id = ? WHERE company_id = ? AND user_type IN ('sales','stock') AND (branch_id IS NULL OR branch_id NOT IN (SELECT id FROM branches WHERE company_id = ? AND kind = 'shop'))", first, id, id);
            db.update("UPDATE bills SET branch_id = ? WHERE company_id = ? AND branch_id IS NULL", first, id);
        }
    }

    static final Set<String> PAY_KINDS = Set.of("cash", "card", "upi", "other");

    /** Every company has payment modes (default Cash, Card, UPI); bills saved before payment modes count as Cash. */
    private void ensurePayModes(String cid) {
        List<Map<String, Object>> cs = cid == null ? db.query("SELECT id FROM companies") : db.query("SELECT id FROM companies WHERE id = ?", cid);
        for (Map<String, Object> co : cs) {
            String id = str(co.get("id"));
            if (db.one("SELECT 1 AS x FROM pay_modes WHERE company_id = ? LIMIT 1", id) == null) {
                String[][] def = {{"Cash", "cash"}, {"Card", "card"}, {"UPI", "upi"}};
                for (int i = 0; i < def.length; i++)
                    db.update("INSERT INTO pay_modes (company_id, id, name, kind, sort_order, active) VALUES (?, ?, ?, ?, ?, 1)", id, newId("p"), def[i][0], def[i][1], i);
            }
            Map<String, Object> cash = db.one("SELECT id, name FROM pay_modes WHERE company_id = ? AND kind = 'cash' ORDER BY sort_order LIMIT 1", id);
            if (cash != null)
                db.update("UPDATE bills SET pay_mode_id = ?, pay_name = ?, pay_kind = 'cash' WHERE company_id = ? AND pay_name = ''", str(cash.get("id")), str(cash.get("name")), id);
        }
    }

    private List<Object> payModeList(String cid, boolean activeOnly) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, name, kind, active FROM pay_modes WHERE company_id = ?" + (activeOnly ? " AND active = 1" : "")
                + " ORDER BY sort_order, id", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(r.get("id")));
            n.put("name", str(r.get("name")));
            n.put("kind", str(r.get("kind")));
            n.put("active", bool(r.get("active")));
            out.add(n);
        }
        return out;
    }

    private String firstBranch(String cid) {
        Map<String, Object> r = db.one("SELECT id FROM branches WHERE company_id = ? ORDER BY kind = 'warehouse', sort_order, created_at, id LIMIT 1", cid);   // a shop first
        return r == null ? null : str(r.get("id"));
    }

    private Map<String, Object> branchRow(String cid, String bid) {
        return blank(bid) ? null : db.one("SELECT * FROM branches WHERE company_id = ? AND id = ?", cid, bid);
    }

    /** all locations: shops first, then warehouses */
    private List<Object> branchList(String cid) { return branchList(cid, null); }

    /** kind = "shop" | "warehouse" | null (all) */
    private List<Object> branchList(String cid, String kind) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, name, kind FROM branches WHERE company_id = ?" + (kind == null ? "" : " AND kind = ?")
                + " ORDER BY kind = 'warehouse', sort_order, created_at, id", kind == null ? new Object[]{cid} : new Object[]{cid, kind})) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(r.get("id")));
            n.put("name", str(r.get("name")));
            n.put("kind", str(r.get("kind")));
            out.add(n);
        }
        return out;
    }

    private boolean isWarehouse(String cid, String bid) {
        Map<String, Object> b = branchRow(cid, bid);
        return b != null && "warehouse".equals(str(b.get("kind")));
    }

    /** Branch where this user's bills and stock go: own branch for sales / stock, the first branch for admin. */
    private String userBranch(Ctx c, String cid) {
        if (!c.admin()) {
            String b = str(c.user.get("branch_id"));
            if (branchRow(cid, b) != null) return b;
        }
        return firstBranch(cid);
    }

    /** owner may only read reports */
    private String requireBilling(Ctx c) {
        if (c.owner()) throw new ApiException(403, "Owner கணக்கில் அறிக்கை மட்டுமே பார்க்கலாம் (Report only)");
        return requireCompany(c);
    }

    /** bills / pending: shops only (never owner or warehouse) */
    private String requireSelling(Ctx c) {
        if (c.warehouse()) throw new ApiException(403, "Warehouse-ல் பில் போட முடியாது (No billing in warehouse)");
        return requireBilling(c);
    }

    public boolean ensureDefaultAdmin() {
        Map<String, Object> r = db.one("SELECT COUNT(*) AS n FROM users");
        if (r != null && lng(r.get("n")) > 0) return false;
        byte[] salt = bytes(16);
        LocalDateTime now = now();
        db.update("INSERT INTO users (id, username, full_name, user_type, company_id, pass_salt, pass_hash, active, must_change, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'system', NULL, ?, ?, 1, 1, ?, ?)",
                newId("u"), "admin", "Super Admin", b64(salt), hash(DEFAULT_PASSWORD, salt), now, now);
        return true;
    }

    // ================================================================ auth
    public Map<String, Object> login(Map<String, Object> in) { return login(in, "", ""); }

    /** Access-token login: returns {access_token, token_type: "Bearer", expires_in}. One token = one session. */
    public Map<String, Object> login(Map<String, Object> in, String ip, String userAgent) {
        String u = clip(in.get("user"), 40).toLowerCase(Locale.ROOT);
        String p = rawStr(in.get("pass"));
        if (u.isEmpty() || p.isEmpty()) throw new ApiException(400, "பயனர் பெயர், கடவுச்சொல் தேவை");
        long nowMs = System.currentTimeMillis();
        long[] f = fails.get(u);
        if (f != null && f[1] > nowMs)
            throw new ApiException(429, "பல முறை தவறு. " + ((f[1] - nowMs) / 1000 + 1) + " விநாடி கழித்து முயற்சிக்கவும்");
        Map<String, Object> user = db.one("SELECT * FROM users WHERE username = ?", u);
        boolean good = user != null ? checkPassword(user, p) : dummyCheck(p);
        if (!good) {
            if (fails.size() > 10000) fails.clear();
            long[] g = fails.computeIfAbsent(u, k -> new long[2]);
            synchronized (g) {
                g[0]++;
                if (g[0] >= 5) { g[0] = 0; g[1] = nowMs + 60000; }
            }
            throw new ApiException(401, "தவறான பயனர் பெயர் அல்லது கடவுச்சொல்");
        }
        fails.remove(u);
        if (!bool(user.get("active"))) throw new ApiException(403, "இந்த கணக்கு நிறுத்தப்பட்டுள்ளது. Admin-ஐ தொடர்பு கொள்ளவும்");
        boolean remember = !(in.get("remember") instanceof Boolean) || (Boolean) in.get("remember");
        String raw = hex(bytes(32));
        LocalDateTime now = now();
        db.update("DELETE FROM auth_tokens WHERE expires_at < ?", now);
        purgeIdleTokens(now);
        long max = lng(user.get("max_sessions"));
        if (max > 0) {
            long live = lng(db.one("SELECT COUNT(*) AS n FROM auth_tokens WHERE user_id = ?", user.get("id")).get("n"));
            if (live >= max)
                throw new ApiException(409, "இந்த பயனருக்கு அதிகபட்சம் " + max + " session மட்டுமே அனுமதி — ஏற்கனவே " + live
                        + " இடங்களில் login உள்ளது. வேறு device-ல் logout செய்யவும் அல்லது Admin-ஐ தொடர்பு கொள்ளவும்");
        }
        LocalDateTime exp = remember ? now.plusDays(30) : now.plusHours(12);
        db.update("INSERT INTO auth_tokens (token_hash, user_id, company_id, expires_at, created_at, last_seen, ip, device) VALUES (?, ?, NULL, ?, ?, ?, ?, ?)",
                sha(raw), user.get("id"), exp, now, now, clip(ip, 45), clip(deviceName(userAgent), 120));
        Map<String, Object> out = session(new Ctx(sha(raw), user, null));
        out.put("token", raw);                                   // old clients
        out.put("access_token", raw);
        out.put("token_type", "Bearer");
        out.put("expires_in", java.time.Duration.between(now, exp).getSeconds());
        return out;
    }

    /** sessions of users with an idle limit that have been silent for longer than that limit */
    private void purgeIdleTokens(LocalDateTime now) {
        db.update("DELETE t FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE u.idle_minutes > 0 "
                + "AND COALESCE(t.last_seen, t.created_at) < DATE_SUB(?, INTERVAL u.idle_minutes MINUTE)", now);
    }

    static String deviceName(String ua) {
        String a = ua == null ? "" : ua;
        String os = a.contains("Android") ? "Android" : a.contains("iPhone") ? "iPhone" : a.contains("iPad") ? "iPad" : a.contains("Windows") ? "Windows"
                : a.contains("Mac OS") ? "Mac" : a.contains("Linux") ? "Linux" : "";
        String br = a.contains("Edg/") ? "Edge" : a.contains("OPR/") ? "Opera" : a.contains("Firefox/") ? "Firefox" : a.contains("Chrome/") || a.contains("CriOS") ? "Chrome"
                : a.contains("Safari/") ? "Safari" : a.isEmpty() ? "" : "Browser";
        return (br + (os.isEmpty() || br.isEmpty() ? "" : " · ") + os).trim();
    }

    Ctx ctx(String raw) {
        if (blank(raw)) throw new ApiException(401, "Login தேவை");
        String key = sha(raw.trim());
        Map<String, Object> r = db.one("SELECT t.company_id AS active_company, t.expires_at AS token_expires, t.created_at AS tok_created, t.last_seen AS tok_seen, u.* "
                + "FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?", key);
        LocalDateTime now = now();
        if (r == null || ldt(r.get("token_expires")).isBefore(now) || !bool(r.get("active"))) {
            if (r != null) db.update("DELETE FROM auth_tokens WHERE token_hash = ?", key);
            throw new ApiException(401, "Session முடிந்தது. மீண்டும் login செய்யவும்");
        }
        LocalDateTime seen = r.get("tok_seen") != null ? ldt(r.get("tok_seen")) : ldt(r.get("tok_created"));
        long idle = lng(r.get("idle_minutes"));
        if (idle > 0 && seen.plusMinutes(idle).isBefore(now)) {
            db.update("DELETE FROM auth_tokens WHERE token_hash = ?", key);
            throw new ApiException(401, "நீண்ட நேரம் பயன்படுத்தாததால் (" + idle + " நிமிடம்) தானாக logout ஆனது. மீண்டும் login செய்யவும்");
        }
        if (seen.plusSeconds(15).isBefore(now)) db.update("UPDATE auth_tokens SET last_seen = ? WHERE token_hash = ?", now, key);   // keep-alive (at most every 15 s)
        return new Ctx(key, r, nz(r.get("active_company")));
    }

    /** remembers the user's language; it is applied again at their next login (the login page itself is always English) */
    public Map<String, Object> setLang(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String l = str(in.get("lang")).trim().toLowerCase(Locale.ROOT);
        if (!l.matches("[a-z]{2,3}")) throw new ApiException(400, "Invalid language");
        db.update("UPDATE users SET lang = ? WHERE id = ?", l, c.uid());
        return obj("lang", l);
    }

    /** keep-alive from the app while the user is working (touches last_seen through ctx) */
    public Map<String, Object> ping(String auth) {
        Ctx c = ctx(auth);
        return obj("idleMinutes", lng(c.user.get("idle_minutes")));
    }

    // ---------------------------------------------------------------- admin: who is logged in
    public Map<String, Object> adminSessions(String auth) {
        Ctx c = admin(auth);
        LocalDateTime now = now();
        purgeIdleTokens(now);
        db.update("DELETE FROM auth_tokens WHERE expires_at < ?", now);
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT t.token_hash, t.created_at, t.last_seen, t.expires_at, t.ip, t.device, u.id AS uid, u.username, u.full_name, u.user_type, u.max_sessions, u.idle_minutes, "
                + "co.name AS company_name FROM auth_tokens t JOIN users u ON u.id = t.user_id LEFT JOIN companies co ON co.id = u.company_id "
                + "WHERE (? = 1 OR u.created_by = ? OR u.id = ?) ORDER BY COALESCE(t.last_seen, t.created_at) DESC", c.system() ? 1 : 0, c.uid(), c.uid())) {
            Map<String, Object> n = new LinkedHashMap<>();
            String h = str(r.get("token_hash"));
            LocalDateTime seen = r.get("last_seen") != null ? ldt(r.get("last_seen")) : ldt(r.get("created_at"));
            n.put("sid", h.substring(0, 16));
            n.put("current", h.equals(c.key));
            n.put("userId", str(r.get("uid")));
            n.put("user", str(r.get("username")));
            n.put("name", str(r.get("full_name")));
            n.put("type", str(r.get("user_type")));
            n.put("company", str(r.get("company_name")));
            n.put("loginAt", iso(ldt(r.get("created_at"))));
            n.put("lastSeen", iso(seen));
            n.put("idleSec", Math.max(0, java.time.Duration.between(seen, now).getSeconds()));
            n.put("idleMinutes", lng(r.get("idle_minutes")));
            n.put("maxSessions", lng(r.get("max_sessions")));
            n.put("ip", str(r.get("ip")));
            n.put("device", str(r.get("device")));
            out.add(n);
        }
        return obj("sessions", out);
    }

    /** admin ends one session (sid) or every session of a user (userId); the admin's own current session stays */
    public Map<String, Object> adminKillSession(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String sid = str(in.get("sid")), uid = str(in.get("userId"));
        int n;
        if (!sid.isEmpty()) {
            if (sid.length() != 16) throw new ApiException(400, "Session அடையாளம் தவறு");
            Map<String, Object> t = db.one("SELECT token_hash FROM auth_tokens WHERE LEFT(token_hash, 16) = ?", sid);
            if (t == null) throw new ApiException(404, "Session ஏற்கனவே முடிந்துவிட்டது");
            if (!c.system()) { Map<String, Object> tu = db.one("SELECT u.created_by FROM auth_tokens t JOIN users u ON u.id = t.user_id WHERE t.token_hash = ?", t.get("token_hash"));
                if (tu == null || !c.uid().equals(str(tu.get("created_by")))) throw new ApiException(404, "Session ஏற்கனவே முடிந்துவிட்டது"); }
            if (str(t.get("token_hash")).equals(c.key)) throw new ApiException(400, "இது உங்கள் சொந்த session — Logout பயன்படுத்தவும்");
            n = db.update("DELETE FROM auth_tokens WHERE token_hash = ?", t.get("token_hash"));
        } else if (!uid.isEmpty()) {
            if (!c.system() && db.one("SELECT 1 AS x FROM users WHERE id = ? AND created_by = ?", uid, c.uid()) == null) throw new ApiException(404, "User இல்லை");
            n = db.update("DELETE FROM auth_tokens WHERE user_id = ? AND token_hash <> ?", uid, c.key);
        } else throw new ApiException(400, "Session / User தேர்வு செய்யவும்");
        Map<String, Object> o = adminSessions(auth);
        o.put("killed", n);
        return o;
    }

    public Map<String, Object> me(String auth) { return session(ctx(auth)); }

    public Map<String, Object> logout(String auth) {
        Ctx c = ctx(auth);
        db.update("DELETE FROM auth_tokens WHERE token_hash = ?", c.key);
        return obj("ok", true);
    }

    public Map<String, Object> password(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String old = rawStr(in.get("old")), nw = rawStr(in.get("new"));
        if (!checkPassword(c.user, old)) throw new ApiException(400, "பழைய கடவுச்சொல் தவறு");
        validPassword(nw);
        byte[] salt = bytes(16);
        db.update("UPDATE users SET pass_salt = ?, pass_hash = ?, must_change = 0, updated_at = ? WHERE id = ?",
                b64(salt), hash(nw, salt), now(), c.uid());
        db.update("DELETE FROM auth_tokens WHERE user_id = ? AND token_hash <> ?", c.uid(), c.key);
        return obj("ok", true);
    }

    private Map<String, Object> session(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("user", pubUser(c.user));
        Map<String, Object> co = companyOf(c);
        o.put("company", co == null ? null : pubCompany(co));
        o.put("defaultPassword", bool(c.user.get("must_change")));
        o.put("idleMinutes", lng(c.user.get("idle_minutes")));
        if (co != null && !c.admin() && !c.owner()) {
            Map<String, Object> br = branchRow(str(co.get("id")), userBranch(c, str(co.get("id"))));
            o.put("branch", br == null ? null : obj("name", str(br.get("name"))));
        }
        return o;
    }

    /** customer: own company. admin: the company opened with admin/open. */
    private Map<String, Object> companyOf(Ctx c) {
        String cid = c.admin() ? nz(c.activeCompany) : str(c.user.get("company_id"));
        if (blank(cid)) return null;
        return db.one("SELECT * FROM companies WHERE id = ? AND archived = 0", cid);
    }

    private String requireCompany(Ctx c) {
        Map<String, Object> co = companyOf(c);
        if (co == null) throw new ApiException(403, "கம்பெனி இணைக்கப்படவில்லை (No company)");
        return str(co.get("id"));
    }

    // ================================================================ company data (groups / items)
    public Map<String, Object> data(String auth) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", cid)));
        o.put("catalog", loadCatalog(db, cid, c.seesCost()));
        o.put("payModes", payModeList(cid, true));
        return o;
    }

    private Map<String, Object> loadCatalog(Db d, String cid, boolean withCost) {
        Map<String, Object> co = d.one("SELECT catalog_version, catalog_setup FROM companies WHERE id = ?", cid);
        List<Object> cats = new ArrayList<>(), items = new ArrayList<>();
        for (Map<String, Object> g : d.query("SELECT * FROM item_groups WHERE company_id = ? ORDER BY sort_order, id", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(g.get("id")));
            n.put("ta", str(g.get("name_ta")));
            n.put("en", str(g.get("name_en")));
            n.put("emoji", str(g.get("emoji")));
            n.put("color", str(g.get("color")));
            cats.add(n);
        }
        Map<String, List<Object>> sizes = new LinkedHashMap<>();
        for (Map<String, Object> z : d.query("SELECT item_id, size_label, rate, wrate, cost, barcode FROM item_sizes WHERE company_id = ? ORDER BY item_id, sort_order", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("size", str(z.get("size_label")));
            n.put("rate", dbl(z.get("rate")));
            if (!blank(str(z.get("barcode")))) n.put("code", str(z.get("barcode")));
            if (withCost && z.get("cost") != null) n.put("cost", dbl(z.get("cost")));
            if (z.get("wrate") != null) n.put("wrate", dbl(z.get("wrate")));
            sizes.computeIfAbsent(str(z.get("item_id")), k -> new ArrayList<>()).add(n);
        }
        Map<String, Object> photos = new HashMap<>();
        for (Map<String, Object> ph : d.query("SELECT item_id, UNIX_TIMESTAMP(updated_at) AS v FROM item_photos WHERE company_id = ?", cid))
            photos.put(str(ph.get("item_id")), lng(ph.get("v")));
        for (Map<String, Object> x : d.query("SELECT * FROM items WHERE company_id = ? ORDER BY sort_order, id", cid)) {
            List<Object> sz = sizes.get(str(x.get("id")));
            if (sz == null) continue;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(x.get("id")));
            n.put("cat", str(x.get("group_id")));
            n.put("ta", str(x.get("name_ta")));
            n.put("en", str(x.get("name_en")));
            if ("weight".equals(str(x.get("sold_by")))) { n.put("by", "weight"); n.put("unit", "g".equals(str(x.get("wt_unit"))) ? "g" : "kg"); }
            n.put("sizes", sz);
            Object pv = photos.get(str(x.get("id")));
            if (pv != null) n.put("photo", pv);
            items.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", lng(co.get("catalog_version")));
        out.put("setup", bool(co.get("catalog_setup")));
        out.put("cats", cats);
        out.put("items", items);
        return out;
    }

    /** Saves the full list of groups + items of the caller's company (optimistic version check). */
    public Map<String, Object> putCatalog(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        long version = lng(in.get("version"));
        CleanCatalog clean = cleanCatalog(in);
        Map<String, Object> cat = db.tx(t -> {
            Map<String, Object> co = t.one("SELECT catalog_version FROM companies WHERE id = ? FOR UPDATE", cid);
            if (co == null || lng(co.get("catalog_version")) != version)
                throw new ApiException(409, "வேறு மொபைலில் மாற்றம் நடந்துள்ளது");
            if (!c.seesCost()) {
                // sales staff never see cost: whatever they send is ignored, the stored cost of each item + size is kept
                Map<String, Object> keep = new HashMap<>();
                for (Map<String, Object> z : t.query("SELECT item_id, size_label, cost FROM item_sizes WHERE company_id = ? AND cost IS NOT NULL", cid))
                    keep.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), z.get("cost"));
                for (Object[] z : clean.sizes) z[5] = keep.get(z[0] + "|" + z[1]);
            }
            if (c.sales()) {
                // sales staff cannot change rates: an existing item + size keeps its stored rate (new items / sizes take what they send)
                Map<String, Object> rates = new HashMap<>();
                for (Map<String, Object> z : t.query("SELECT item_id, size_label, rate FROM item_sizes WHERE company_id = ?", cid))
                    rates.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), z.get("rate"));
                for (Object[] z : clean.sizes) { Object r = rates.get(z[0] + "|" + z[1]); if (r != null) z[2] = r; }
                // ... and the wholesale price too
                Map<String, Object> wr = new HashMap<>();
                Set<String> have = new HashSet<>();
                for (Map<String, Object> z : t.query("SELECT item_id, size_label, wrate FROM item_sizes WHERE company_id = ?", cid)) {
                    have.add(str(z.get("item_id")) + "|" + str(z.get("size_label")));
                    wr.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), z.get("wrate"));
                }
                for (Object[] z : clean.sizes) if (have.contains(z[0] + "|" + z[1])) z[6] = wr.get(z[0] + "|" + z[1]);
            }
            t.update("DELETE FROM item_groups WHERE company_id = ?", cid);   // cascades to items and item_sizes
            t.batch("INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", clean.groups(cid));
            t.batch("INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order, sold_by, wt_unit) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.items(cid));
            t.batch("INSERT INTO item_sizes (company_id, item_id, size_label, rate, sort_order, barcode, cost, wrate) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.sizes(cid));
            t.update("UPDATE companies SET catalog_version = ?, catalog_setup = 1, updated_at = ? WHERE id = ?", version + 1, now(), cid);
            return loadCatalog(t, cid, c.seesCost());
        });
        return obj("catalog", cat);
    }

    static final class CleanCatalog {
        final List<Object[]> cats = new ArrayList<>(), items = new ArrayList<>(), sizes = new ArrayList<>();

        List<Object[]> groups(String cid) { return withCompany(cats, cid); }

        List<Object[]> items(String cid) { return withCompany(items, cid); }

        List<Object[]> sizes(String cid) { return withCompany(sizes, cid); }

        private static List<Object[]> withCompany(List<Object[]> rows, String cid) {
            List<Object[]> out = new ArrayList<>();
            for (Object[] r : rows) {
                Object[] n = new Object[r.length + 1];
                n[0] = cid;
                System.arraycopy(r, 0, n, 1, r.length);
                out.add(n);
            }
            return out;
        }
    }

    private CleanCatalog cleanCatalog(Map<String, Object> in) {
        CleanCatalog out = new CleanCatalog();
        Set<String> gids = new HashSet<>(), iids = new HashSet<>();
        Map<String, String> codes = new HashMap<>();   // barcode -> "item (size)", must be unique in the company
        for (Object o : list(in.get("cats"))) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> g = map(o);
            String id = str(g.get("id")), ta = clip(g.get("ta"), 30);
            if (!ID.matcher(id).matches() || gids.contains(id) || ta.isEmpty()) continue;
            if (out.cats.size() >= 100) throw new ApiException(400, "அதிகபட்சம் 100 குரூப்புகள்");
            gids.add(id);
            String em = clip(g.get("emoji"), 16), col = str(g.get("color"));
            out.cats.add(new Object[]{id, ta, clip(g.get("en"), 30), em.isEmpty() ? "🛍️" : em,
                    COLOR.matcher(col).matches() ? col : "#e8eef0", out.cats.size()});
        }
        for (Object o : list(in.get("items"))) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> x = map(o);
            String id = str(x.get("id")), gid = str(x.get("cat")), ta = clip(x.get("ta"), 60);
            if (!ID.matcher(id).matches() || iids.contains(id) || !gids.contains(gid) || ta.isEmpty()) continue;
            List<Object[]> sz = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object zo : list(x.get("sizes"))) {
                if (!(zo instanceof Map)) continue;
                Map<String, Object> z = map(zo);
                String label = clip(z.get("size"), 12);
                double r = round2(dbl(z.get("rate")));
                if (label.isEmpty() || label.indexOf('|') >= 0 || seen.contains(label) || !(r > 0 && r <= 999999)) continue;
                if (sz.size() >= 20) break;
                seen.add(label);
                String code = str(z.get("code")).trim();
                if (code.isEmpty()) code = null;
                else {
                    if (!BARCODE.matcher(code).matches())
                        throw new ApiException(400, "தவறான barcode: " + clip(code, 40) + " (" + ta + " " + label + ")");
                    String owner = codes.putIfAbsent(code, ta + " (" + label + ")");
                    if (owner != null)
                        throw new ApiException(400, "Barcode " + code + " ஏற்கனவே " + owner + "-க்கு உள்ளது");
                }
                // cost (அடக்க விலை) is optional: blank = not known
                Object co = z.get("cost");
                BigDecimal cost = null;
                if (co != null && !str(co).trim().isEmpty()) {
                    double raw = co instanceof Number ? ((Number) co).doubleValue() : dbl(str(co).replace(",", "").trim());
                    if (Double.isNaN(raw) || Double.isInfinite(raw)) throw new ApiException(400, "அடக்க விலை தவறு: " + ta + " (" + label + ")");
                    double cv = round2(raw);
                    if (!(cv >= 0 && cv <= 999999)) throw new ApiException(400, "அடக்க விலை தவறு: " + ta + " (" + label + ")");
                    cost = BigDecimal.valueOf(cv);
                }
                // wholesale selling price is optional: blank = same as the retail rate
                Object wo = z.get("wrate");
                BigDecimal wrate = null;
                if (wo != null && !str(wo).trim().isEmpty()) {
                    double wv = round2(wo instanceof Number ? ((Number) wo).doubleValue() : dbl(str(wo).replace(",", "").trim()));
                    if (Double.isNaN(wv) || !(wv > 0 && wv <= 999999)) throw new ApiException(400, "Wholesale விலை தவறு: " + ta + " (" + label + ")");
                    wrate = BigDecimal.valueOf(wv);
                }
                sz.add(new Object[]{id, label, BigDecimal.valueOf(r), sz.size(), code, cost, wrate});
            }
            if (sz.isEmpty()) continue;
            // weight item: one size only = the price of 1 kg (or 1 g)
            boolean byWt = "weight".equals(str(x.get("by")));
            String unit = "g".equals(str(x.get("unit"))) ? "g" : "kg";
            if (byWt) {
                Object[] f = sz.get(0);
                f[1] = "1 " + unit;
                f[3] = 0;
                sz.clear();
                sz.add(f);
            }
            if (out.items.size() >= 3000) throw new ApiException(400, "அதிகபட்சம் 3000 ஐட்டங்கள்");
            iids.add(id);
            out.items.add(new Object[]{id, gid, ta, clip(x.get("en"), 60), out.items.size(), byWt ? "weight" : "count", unit});
            out.sizes.addAll(sz);
        }
        return out;
    }

    // ================================================================ bills
    /** what a bill line is linked to: catalog item + size pairs, weight items (stock in grams) and cost prices */
    static final class Link {
        final Set<String> known = new HashSet<>();
        final Map<String, Object> costOf = new HashMap<>(), rateOf = new HashMap<>();
        final Map<String, String> wtSize = new HashMap<>();      // weight item -> its only size label ("1 kg" / "1 g")
        final Map<String, Integer> unitG = new HashMap<>();      // weight item -> grams in one unit (1000 / 1)

        boolean linked(String iid, Map<String, Object> l) {
            if (iid == null) return false;
            if (wtSize.containsKey(iid)) return l.get("wt") != null;
            return known.contains(iid + "|" + l.get("size"));
        }
        boolean weight(String iid) { return iid != null && wtSize.containsKey(iid); }
        String stockSize(String iid, Map<String, Object> l) { return weight(iid) ? wtSize.get(iid) : (String) l.get("size"); }
        long stockQty(String iid, Map<String, Object> l) { return weight(iid) ? (Long) l.get("qty") * (Long) l.get("wt") : (Long) l.get("qty"); }
        /** cost of ONE portion (weight line) or one piece */
        Object cost(String iid, Map<String, Object> l) {
            Object c = costOf.get(iid + "|" + stockSize(iid, l));
            if (c == null || !weight(iid)) return c;
            return BigDecimal.valueOf(round2(dbl(c) * (Long) l.get("wt") / unitG.get(iid)));
        }
        Double catalogRate(String iid, Map<String, Object> l) {
            Object r = rateOf.get(iid + "|" + stockSize(iid, l));
            if (r == null) return null;
            return weight(iid) ? round2(dbl(r) * (Long) l.get("wt") / unitG.get(iid)) : dbl(r);
        }
    }

    private static Link link(Db d, String cid) {
        Link k = new Link();
        for (Map<String, Object> z : d.query("SELECT z.item_id, z.size_label, z.cost, z.rate, i.sold_by, i.wt_unit FROM item_sizes z "
                + "JOIN items i ON i.company_id = z.company_id AND i.id = z.item_id WHERE z.company_id = ?", cid)) {
            String iid = str(z.get("item_id")), key = iid + "|" + str(z.get("size_label"));
            k.known.add(key);
            k.rateOf.put(key, z.get("rate"));
            if (z.get("cost") != null) k.costOf.put(key, z.get("cost"));
            if ("weight".equals(str(z.get("sold_by")))) { k.wtSize.put(iid, str(z.get("size_label"))); k.unitG.put(iid, "g".equals(str(z.get("wt_unit"))) ? 1 : 1000); }
        }
        return k;
    }

    /** validated bill lines (catalog lines keep their item id; custom lines have none) */
    private static List<Map<String, Object>> parseLines(List<Object> raw) {
        if (raw.size() > 300) throw new ApiException(400, "அதிக ஐட்டங்கள்");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map)) throw new ApiException(400, "பில் வரி தவறு");
            Map<String, Object> l = map(o);
            String name = clip(l.get("name"), 60);
            double rate = round2(dbl(l.get("rate"))), q = dbl(l.get("qty"));
            if (name.isEmpty() || !(rate > 0 && rate <= 999999) || !(q >= 1 && q <= 999) || q != Math.floor(q))
                throw new ApiException(400, "பில் வரி தவறு: " + name);
            long qty = (long) q;
            Map<String, Object> n = new LinkedHashMap<>();
            String itemId = str(l.get("id"));
            n.put("id", ID.matcher(itemId).matches() ? itemId : null);
            n.put("name", name);
            n.put("en", clip(l.get("en"), 60));
            n.put("size", clip(l.get("size"), 12));
            n.put("qty", qty);
            n.put("rate", rate);
            n.put("amt", round2(rate * qty));
            double w = dbl(l.get("wt"));                       // weight line: grams of one portion
            if (w >= 1 && w <= 9_999_999 && w == Math.floor(w)) n.put("wt", (long) w);
            lines.add(n);
        }
        return lines;
    }

    /** same item + size + rate (or same custom name + size + rate) become one line with the quantities added */
    private static List<Map<String, Object>> mergeLines(List<Map<String, Object>> in) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> l : in) {
            String k = (l.get("id") != null ? "i:" + l.get("id") : "c:" + str(l.get("name")).toLowerCase(Locale.ROOT)) + "|" + l.get("size") + "|" + l.get("rate") + "|" + l.get("wt");
            Map<String, Object> e = m.get(k);
            if (e == null) m.put(k, new LinkedHashMap<>(l));
            else {
                long q = Math.min(999, (Long) e.get("qty") + (Long) l.get("qty"));
                e.put("qty", q);
                e.put("amt", round2((Double) e.get("rate") * q));
            }
        }
        return new ArrayList<>(m.values());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static List<Object> jsonList(Object s) {
        try { return JSON.readValue(str(s), List.class); } catch (Exception e) { return new ArrayList<>(); }
    }

    private static String toJson(Object o) {
        try { return JSON.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- offline helpers
    static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_-]{8,40}");

    /** id made on the device for one bill / cancel bill: the same id sent again returns the document already saved */
    private static String clientId(Object o) {
        String s = str(o).trim();
        if (s.isEmpty()) return "";
        if (!CLIENT_ID.matcher(s).matches()) throw new ApiException(400, "client id தவறு");
        return s;
    }

    /**
     * Time the document was made on the device (offline billing). Used only when it is clearly earlier than now
     * (more than 2 minutes) and not older than 45 days; otherwise the server time is used.
     */
    LocalDateTime clientTime(Object o, LocalDateTime now) {
        String s = str(o).trim();
        if (s.isEmpty()) return now;
        try {
            LocalDateTime t = java.time.OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
            if (t.isAfter(now.minusMinutes(2))) return now;             // online / clock a little off: server time
            if (t.isBefore(now.minusDays(45))) return now;
            return t;
        } catch (DateTimeParseException e) {
            return now;
        }
    }

    /** is this number inside a block reserved for this device? */
    private static boolean reserved(Db t, String cid, String device, String kind, long seq) {
        return seq > 0 && !device.isEmpty() && t.one("SELECT 1 AS x FROM bill_no_blocks WHERE company_id = ? AND device_id = ? AND kind = ? AND from_seq <= ? AND to_seq >= ? LIMIT 1",
                cid, device, kind, seq, seq) != null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> billByClient(String cid, String clientId) {
        Map<String, Object> r = db.one("SELECT * FROM bills WHERE company_id = ? AND client_id = ?", cid, clientId);
        if (r == null) return null;
        Map<String, Object> b = (Map<String, Object>) billList(cid, new ArrayList<>(List.of(r))).get(0);
        b.put("mergedPending", 0);
        b.put("clientId", clientId);
        b.put("duplicate", true);
        return obj("bill", b);
    }

    /**
     * New bill. {customer, phone, payMode, exchange, lines}. Offline billing adds: clientId (once only), deviceId, clientAt (ISO time on the device),
     * offline (made without internet), reservedSeq (a bill number reserved for this device before), catalogVersion (the price list the device used).
     */
    public Map<String, Object> addBill(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        final String clientId = clientId(in.get("clientId"));
        if (!clientId.isEmpty()) {
            Map<String, Object> dup = billByClient(cid, clientId);
            if (dup != null) return dup;
        }
        try {
            return addBillNew(c, cid, clientId, in);
        } catch (DbException e) {
            if (!clientId.isEmpty() && e.isConstraintViolation()) {      // two sends of the same bill at the same moment: the other one won
                Map<String, Object> dup = billByClient(cid, clientId);
                if (dup != null) return dup;
            }
            throw e;
        }
    }

    private Map<String, Object> addBillNew(Ctx c, String cid, String clientId, Map<String, Object> in) {
        final boolean offline = bool(in.get("offline"));
        final String deviceId = clientId.isEmpty() ? "" : clip(in.get("deviceId"), 40);
        String branch = userBranch(c, cid);
        String payId = str(in.get("payMode"));
        Map<String, Object> pay = blank(payId) ? null : db.one("SELECT id, name, kind FROM pay_modes WHERE company_id = ? AND id = ? AND active = 1", cid, payId);
        if (!blank(payId) && pay == null) throw new ApiException(400, "பணம் செலுத்தும் முறை தவறு (Payment mode)");
        if (pay == null) pay = db.one("SELECT id, name, kind FROM pay_modes WHERE company_id = ? AND active = 1 ORDER BY sort_order, id LIMIT 1", cid);
        final String payModeId = pay == null ? null : str(pay.get("id")), payName = pay == null ? "Cash" : str(pay.get("name")), payKind = pay == null ? "cash" : str(pay.get("kind"));
        String custIn = clip(in.get("customer"), 60);
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!phone.isEmpty() && !MOBILE.matcher(phone).matches()) throw new ApiException(400, "மொபைல் எண் தவறு");
        List<Map<String, Object>> newLines = parseLines(list(in.get("lines")));
        LocalDateTime at = clientTime(in.get("clientAt"), now());
        LocalDateTime dayStart = at.toLocalDate().atStartOfDay();
        return db.tx(t -> {
            // today's pending bills of this mobile number (any salesman, this branch) join this bill - not a bill made offline (the customer already has its total)
            List<Map<String, Object>> pend = (phone.isEmpty() || offline) ? new ArrayList<>() : t.query(
                    "SELECT * FROM pending_bills WHERE company_id = ? AND branch_id = ? AND phone = ? AND status = 'open' AND created_at >= ? ORDER BY id FOR UPDATE",
                    cid, branch, phone, dayStart);
            List<Map<String, Object>> all = new ArrayList<>();
            String cust = custIn;
            for (Map<String, Object> p : pend) {
                all.addAll(parseLines(jsonList(p.get("lines_json"))));
                if (cust.isEmpty()) cust = str(p.get("customer_name"));
            }
            all.addAll(newLines);
            if (all.isEmpty()) throw new ApiException(400, "பில்லில் ஐட்டம் இல்லை");
            Map<String, Object> rcRow = t.one("SELECT rate_change, catalog_version FROM companies WHERE id = ?", cid);
            // a bill made offline with an older price list is not refused for a rate that has changed since (the customer already paid it)
            boolean rateCheck = !bool(rcRow.get("rate_change")) && (!offline || lng(in.get("catalogVersion")) == lng(rcRow.get("catalog_version")));
            if (rateCheck) {
                Link rk = link(t, cid);                           // rate change is off for this company: catalog lines are billed at the catalog rate
                for (Map<String, Object> l : newLines) {
                    String iid = (String) l.get("id");
                    if (iid == null || !rk.linked(iid, l)) continue;
                    Double cr = rk.catalogRate(iid, l);
                    if (cr != null && Math.abs(cr - (Double) l.get("rate")) > 0.01)
                        throw new ApiException(400, "இந்த கம்பெனிக்கு ரேட் மாற்றம் அனுமதி இல்லை: " + l.get("name"));
                }
            }
            List<Map<String, Object>> lines = mergeLines(all);
            if (lines.size() > 300) throw new ApiException(400, "அதிக ஐட்டங்கள்");
            double total = 0;
            long pieces = 0;
            for (Map<String, Object> l : lines) { total += (Double) l.get("amt"); pieces += (Long) l.get("qty"); }
            double billTotal = round2(total);

            // exchange: unused cancel bills (ரத்து பில்) of this customer are taken off this bill; the rest is paid
            List<Long> exIds = new ArrayList<>();
            if (!offline) for (Object o : list(in.get("exchange"))) exIds.add(lng(o));
            double exAmt = 0;
            List<String> exNos = new ArrayList<>();
            for (Long xid : exIds) {
                Map<String, Object> x = t.one("SELECT * FROM cancel_bills WHERE company_id = ? AND id = ? FOR UPDATE", cid, xid);
                if (x == null) throw new ApiException(404, "Exchange ரத்து பில் இல்லை");
                if (!str(x.get("used_bill_no")).isEmpty())
                    throw new ApiException(409, str(x.get("cn_no")) + " ஏற்கனவே " + str(x.get("used_bill_no")) + "-ல் exchange செய்யப்பட்டது");
                if (!str(x.get("customer_phone")).isEmpty() && !str(x.get("customer_phone")).equals(phone))
                    throw new ApiException(400, str(x.get("cn_no")) + " வேறு மொபைல் எண்ணுக்கானது");
                exAmt += dbl(x.get("total"));
                exNos.add(str(x.get("cn_no")));
            }
            exAmt = round2(exAmt);
            if (exAmt > billTotal + 0.001)
                throw new ApiException(400, "புதிய பில் தொகை (" + billTotal + ") exchange தொகையை (" + exAmt + ") விட குறைவு — மேலும் ஐட்டம் சேர்க்கவும்");
            final double exchange = exAmt;
            final String exRef = String.join(", ", exNos);
            final boolean nilBalance = !exIds.isEmpty() && Math.abs(billTotal - exAmt) < 0.005;

            Map<String, Object> co = t.one("SELECT bill_seq, bill_prefix FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq;
            long rs = lng(in.get("reservedSeq"));
            if (reserved(t, cid, deviceId, "bill", rs) && t.one("SELECT 1 AS x FROM bills WHERE company_id = ? AND bill_seq = ?", cid, rs) == null) {
                seq = rs;                                          // the number the device already printed on the bill
            } else {
                seq = lng(co.get("bill_seq")) + 1;
                t.update("UPDATE companies SET bill_seq = ? WHERE id = ?", seq, cid);
            }
            String prefix = str(co.get("bill_prefix"));
            if (prefix.isEmpty()) prefix = "INV";
            String no = prefix + "-" + String.format("%04d", seq);
            long billId = t.insert("INSERT INTO bills (company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total, pay_mode_id, pay_name, pay_kind, platform_fee, exchange_amount, exchange_ref, client_id, device_id, offline, synced_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT bill_fee FROM companies WHERE id = ?), ?, ?, ?, ?, ?, ?)",
                    cid, branch, seq, no, at, cust, phone, str(c.user.get("username")), str(c.user.get("full_name")), pieces, BigDecimal.valueOf(billTotal),
                    nilBalance ? null : payModeId, nilBalance ? "Exchange" : payName, nilBalance ? "exchange" : payKind, cid, BigDecimal.valueOf(exchange), exRef,
                    clientId.isEmpty() ? null : clientId, clientId.isEmpty() || deviceId.isEmpty() ? null : deviceId, offline ? 1 : 0, clientId.isEmpty() ? null : now());
            for (Long xid : exIds) t.update("UPDATE cancel_bills SET used_bill_no = ?, used_at = ? WHERE company_id = ? AND id = ?", no, at, cid, xid);
            // only real catalog item + size pairs are linked (and reduce stock); anything else is a custom line
            Link lk = link(t, cid);                                // cost price now = the bill keeps it (later changes don't alter old profit)
            List<Object[]> rows = new ArrayList<>(), stockRows = new ArrayList<>();
            int ln = 1;
            for (Map<String, Object> l : lines) {
                String iid = (String) l.get("id");
                if (iid != null && !lk.linked(iid, l)) { iid = null; l.put("id", null); }
                rows.add(new Object[]{cid, billId, ln++, iid, l.get("name"), l.get("en"), l.get("size"), l.get("qty"),
                        BigDecimal.valueOf((Double) l.get("rate")), iid == null ? null : lk.cost(iid, l), BigDecimal.valueOf((Double) l.get("amt")), l.get("wt")});
                if (iid != null && branch != null) {
                    long dq = lk.stockQty(iid, l);
                    stockRows.add(new Object[]{cid, branch, iid, lk.stockSize(iid, l), -dq, at, "bill " + no, dq, at, "bill " + no});
                }
            }
            t.batch("INSERT INTO bill_lines (company_id, bill_id, line_no, item_id, item_name, item_name_en, size_label, qty, rate, cost, amount, wt_g) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
            if (!stockRows.isEmpty())
                t.batch("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = qty - ?, updated_at = ?, updated_by = ?", stockRows);
            for (Map<String, Object> p : pend)
                t.update("UPDATE pending_bills SET status = 'billed', bill_id = ?, closed_at = ? WHERE id = ?", billId, at, lng(p.get("id")));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("no", no);
            b.put("seq", seq);
            b.put("at", iso(at));
            b.put("customer", cust);
            b.put("phone", phone);
            b.put("by", str(c.user.get("full_name")));
            b.put("byUser", str(c.user.get("username")));
            b.put("lines", lines);
            b.put("pieces", pieces);
            b.put("total", billTotal);
            b.put("exchange", exchange);
            b.put("exchangeRef", exRef);
            b.put("paid", round2(billTotal - exchange));
            b.put("pay", nilBalance ? "Exchange" : payName);
            b.put("payKind", nilBalance ? "exchange" : payKind);
            b.put("mergedPending", pend.size());
            if (!clientId.isEmpty()) b.put("clientId", clientId);
            return obj("bill", b);
        });
    }

    // ================================================================ offline: reserved numbers + data pull
    /**
     * Bill / cancel bill numbers for a device that may work without internet: {deviceId, bills: n, cancels: m}. The numbers are taken
     * out of the company's running counters (so no other device gets them) and printed on the bills the device makes offline.
     */
    public Map<String, Object> reserveNumbers(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String dev = clientId(in.get("deviceId"));
        if (dev.isEmpty()) throw new ApiException(400, "device id தேவை");
        int nb = (int) Math.max(0, Math.min(100, lng(in.get("bills")))), nc = (int) Math.max(0, Math.min(40, lng(in.get("cancels"))));
        LocalDateTime now = now();
        Map<String, Object> day = db.one("SELECT COALESCE(SUM(to_seq - from_seq + 1), 0) AS n FROM bill_no_blocks WHERE company_id = ? AND device_id = ? AND created_at >= ?", cid, dev, now.minusDays(1));
        if (lng(day.get("n")) + nb + nc > 600) throw new ApiException(429, "இன்று அதிக எண்கள் ஒதுக்கப்பட்டுவிட்டன");
        String by = str(c.user.get("username"));
        return db.tx(t -> {
            Map<String, Object> co = t.one("SELECT bill_seq, cn_seq, bill_prefix FROM companies WHERE id = ? FOR UPDATE", cid);
            Map<String, Object> out = new LinkedHashMap<>();
            String prefix = str(co.get("bill_prefix"));
            out.put("prefix", prefix.isEmpty() ? "INV" : prefix);
            if (nb > 0) {
                long from = lng(co.get("bill_seq")) + 1, to = from + nb - 1;
                t.update("UPDATE companies SET bill_seq = ? WHERE id = ?", to, cid);
                t.update("INSERT INTO bill_no_blocks (company_id, device_id, kind, from_seq, to_seq, user_name, created_at) VALUES (?, ?, 'bill', ?, ?, ?, ?)", cid, dev, from, to, by, now);
                out.put("bills", new long[]{from, to});
            }
            if (nc > 0) {
                long from = lng(co.get("cn_seq")) + 1, to = from + nc - 1;
                t.update("UPDATE companies SET cn_seq = ? WHERE id = ?", to, cid);
                t.update("INSERT INTO bill_no_blocks (company_id, device_id, kind, from_seq, to_seq, user_name, created_at) VALUES (?, ?, 'cancel', ?, ?, ?, ?)", cid, dev, from, to, by, now);
                out.put("cancels", new long[]{from, to});
            }
            return out;
        });
    }

    /**
     * Everything a device needs to keep working without internet: company + payment modes, the catalog (only when its version is not ?cv=),
     * and the retail bills + cancel bills of its branch for the last ?days (default 62) - used for bill history and "cancel from a bill" offline.
     */
    public Map<String, Object> syncPull(String auth, String cvIn, String daysIn) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        long cv = -1;
        try { if (!blank(cvIn)) cv = Long.parseLong(cvIn.trim()); } catch (NumberFormatException e) { /* send the catalog */ }
        int days = 62;
        try { if (!blank(daysIn)) days = Math.max(1, Math.min(90, Integer.parseInt(daysIn.trim()))); } catch (NumberFormatException e) { /* default */ }
        Map<String, Object> co = db.one("SELECT * FROM companies WHERE id = ?", cid);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("serverTime", iso(now()));
        o.put("company", pubCompany(co));
        o.put("payModes", payModeList(cid, true));
        if (lng(co.get("catalog_version")) != cv) o.put("catalog", loadCatalog(db, cid, c.seesCost()));
        else o.put("catalogSame", true);
        String br = c.admin() ? null : userBranch(c, cid);
        LocalDateTime from = LocalDate.now(zone).minusDays(days - 1).atStartOfDay();
        String bf = br == null ? "" : " AND branch_id = ?";
        o.put("bills", billList(cid, db.query("SELECT * FROM bills WHERE company_id = ? AND created_at >= ? AND bill_kind <> 'invoice'" + bf + " ORDER BY id DESC LIMIT 1500",
                br == null ? new Object[]{cid, from} : new Object[]{cid, from, br})));
        List<Object> cn = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM cancel_bills WHERE company_id = ? AND created_at >= ?" + bf + " ORDER BY id DESC LIMIT 1500",
                br == null ? new Object[]{cid, from} : new Object[]{cid, from, br}))
            cn.add(cancelRow(r));
        o.put("cancels", cn);
        return o;
    }

    // ================================================================ pending bills (saved, not yet billed)
    /** Save the cart as pending for a mobile number. Stock and reports change only when it becomes a bill. */
    public Map<String, Object> savePending(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String branch = userBranch(c, cid);
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "Pending-க்கு வாடிக்கையாளர் மொபைல் எண் தேவை");
        List<Map<String, Object>> lines = mergeLines(parseLines(list(in.get("lines"))));
        if (lines.isEmpty()) throw new ApiException(400, "பில்லில் ஐட்டம் இல்லை");
        double total = 0;
        long pcs = 0;
        for (Map<String, Object> l : lines) { total += (Double) l.get("amt"); pcs += (Long) l.get("qty"); }
        db.update("INSERT INTO pending_bills (company_id, branch_id, phone, customer_name, lines_json, pieces, total, status, created_at, created_by_user, created_by_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)", cid, branch, phone, clip(in.get("customer"), 60), toJson(lines), pcs, BigDecimal.valueOf(round2(total)),
                now(), str(c.user.get("username")), str(c.user.get("full_name")));
        return pendingList(c, phone);
    }

    /** ?phone= : today's open pending of that number; no phone: open pending of the branch (last 7 days) */
    public Map<String, Object> pendingList(String auth, String phone) {
        return pendingList(ctx(auth), str(phone).replaceAll("[^0-9]", ""));
    }

    private Map<String, Object> pendingList(Ctx c, String phone) {
        String cid = requireSelling(c);
        String branch = userBranch(c, cid);
        LocalDate today = LocalDate.now(zone);
        List<Map<String, Object>> rows = phone.isEmpty()
                ? db.query("SELECT * FROM pending_bills WHERE company_id = ? AND branch_id = ? AND status = 'open' AND created_at >= ? ORDER BY phone, id",
                        cid, branch, today.minusDays(6).atStartOfDay())
                : db.query("SELECT * FROM pending_bills WHERE company_id = ? AND branch_id = ? AND phone = ? AND status = 'open' AND created_at >= ? ORDER BY id",
                        cid, branch, phone, today.atStartOfDay());
        List<Object> list = new ArrayList<>();
        double total = 0;
        long pcs = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> n = new LinkedHashMap<>();
            LocalDateTime at = ldt(r.get("created_at"));
            n.put("id", lng(r.get("id")));
            n.put("phone", str(r.get("phone")));
            n.put("customer", str(r.get("customer_name")));
            n.put("at", iso(at));
            n.put("today", at != null && !at.toLocalDate().isBefore(today));
            n.put("by", str(r.get("created_by_name")));
            n.put("pieces", lng(r.get("pieces")));
            n.put("total", round2(dbl(r.get("total"))));
            n.put("lines", jsonList(r.get("lines_json")));
            total += dbl(r.get("total"));
            pcs += lng(r.get("pieces"));
            list.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("pending", list);
        o.put("count", list.size());
        o.put("pieces", pcs);
        o.put("total", round2(total));
        return o;
    }

    public Map<String, Object> deletePending(String auth, long id) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String branch = userBranch(c, cid);
        if (db.update("UPDATE pending_bills SET status = 'deleted', closed_at = ? WHERE id = ? AND company_id = ? AND branch_id = ? AND status = 'open'",
                now(), id, cid, branch) == 0) throw new ApiException(404, "Pending பில் இல்லை");
        return obj("ok", true);
    }

    public Map<String, Object> bills(String auth) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String br = c.admin() ? null : userBranch(c, cid);       // sales / stock: own branch only
        String bf = (br == null ? "" : " AND branch_id = ?") + (c.sales() ? " AND bill_kind <> 'invoice'" : "");   // sales: no wholesale invoices
        List<Map<String, Object>> rows = db.query("SELECT * FROM bills WHERE company_id = ?" + bf + " ORDER BY id DESC LIMIT 100", args(br, cid));
        List<Object> list = billList(cid, rows);
        LocalDateTime day = LocalDate.now(zone).atStartOfDay();
        Map<String, Object> td = db.one("SELECT COUNT(*) AS cnt, COALESCE(SUM(total), 0) AS tot FROM bills WHERE company_id = ? AND created_at >= ? AND created_at < ?" + bf,
                args(br, cid, day, day.plusDays(1)));
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("count", lng(td.get("cnt")));
        today.put("total", round2(dbl(td.get("tot"))));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("bills", list);
        o.put("today", today);
        return o;
    }

    // ================================================================ wholesale: retailers (parties), sales invoices on credit, payments
    private Map<String, Object> partyRow(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", str(r.get("id")));
        n.put("name", str(r.get("name")));
        n.put("contact", str(r.get("contact")));
        n.put("phone", str(r.get("phone")));
        n.put("gstin", str(r.get("gstin")));
        n.put("address", str(r.get("address")));
        n.put("city", str(r.get("city")));
        n.put("creditDays", lng(r.get("credit_days")));
        if (r.containsKey("inv_cnt")) {
            double billed = round2(dbl(r.get("billed"))), paid = round2(dbl(r.get("paid")));
            n.put("invoices", lng(r.get("inv_cnt")));
            n.put("billed", billed);
            n.put("paid", paid);
            n.put("pending", round2(billed - paid));
            n.put("overdue", round2(dbl(r.get("overdue"))));
            n.put("lastAt", r.get("last_at") == null ? null : iso(ldt(r.get("last_at"))));
        }
        return n;
    }

    private void requireWholesale(Ctx c) {
        if (c.sales()) throw new ApiException(403, "Sales user-க்கு Wholesale / Invoice அனுமதி இல்லை");
    }

    /** retailers with invoice totals: how many, billed, paid, pending, overdue */
    public Map<String, Object> parties(String auth) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        requireWholesale(c);
        LocalDate today = LocalDate.now(zone);
        List<Object> out = new ArrayList<>();
        double pend = 0, over = 0;
        for (Map<String, Object> r : db.query("SELECT p.*, COALESCE(b.inv_cnt, 0) AS inv_cnt, COALESCE(b.billed, 0) AS billed, COALESCE(b.paid, 0) AS paid, "
                + "COALESCE(b.overdue, 0) AS overdue, b.last_at FROM parties p LEFT JOIN (SELECT party_id, COUNT(*) AS inv_cnt, SUM(total) AS billed, SUM(paid_amount) AS paid, "
                + "SUM(CASE WHEN due_date < ? THEN total - paid_amount ELSE 0 END) AS overdue, MAX(created_at) AS last_at FROM bills "
                + "WHERE company_id = ? AND bill_kind = 'invoice' GROUP BY party_id) b ON b.party_id = p.id "
                + "WHERE p.company_id = ? ORDER BY COALESCE(b.billed, 0) - COALESCE(b.paid, 0) DESC, p.name", today, cid, cid)) {
            Map<String, Object> n = partyRow(r);
            pend += dbl(n.get("pending"));
            over += dbl(n.get("overdue"));
            out.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("parties", out);
        o.put("pending", round2(pend));
        o.put("overdue", round2(over));
        o.put("canEdit", !c.owner());
        o.put("canRate", !c.sales());
        return o;
    }

    /** {id?, name, contact, phone, gstin, address, city, creditDays} */
    public Map<String, Object> saveParty(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        requireWholesale(c);
        String id = str(in.get("id"));
        String name = clip(in.get("name"), 80);
        if (name.isEmpty()) throw new ApiException(400, "கடை / நிறுவனத்தின் பெயர் தேவை");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String gst = clip(in.get("gstin"), 20).toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (!gst.isEmpty() && !GSTIN.matcher(gst).matches()) throw new ApiException(400, "GSTIN தவறு (15 எழுத்து)");
        long days = Math.max(0, Math.min(365, lng(in.get("creditDays"))));
        if (db.one("SELECT id FROM parties WHERE company_id = ? AND LOWER(name) = LOWER(?) AND id <> ?", cid, name, id) != null)
            throw new ApiException(409, "இதே பெயரில் retailer உள்ளார்");
        Object[] f = {name, clip(in.get("contact"), 60), phone, gst, clip(in.get("address"), 300), clip(in.get("city"), 40), days};
        if (id.isEmpty()) {
            id = newId("r");
            db.update("INSERT INTO parties (name, contact, phone, gstin, address, city, credit_days, company_id, id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", concat(f, cid, id, now()));
        } else if (db.update("UPDATE parties SET name = ?, contact = ?, phone = ?, gstin = ?, address = ?, city = ?, credit_days = ? WHERE company_id = ? AND id = ?", concat(f, cid, id)) == 0)
            throw new ApiException(404, "Retailer இல்லை");
        return obj("party", partyRow(db.one("SELECT * FROM parties WHERE company_id = ? AND id = ?", cid, id)));
    }

    private Map<String, Object> invoiceRow(Map<String, Object> r, LocalDate today) {
        Map<String, Object> n = new LinkedHashMap<>();
        double tot = round2(dbl(r.get("total"))), paid = round2(dbl(r.get("paid_amount")));
        LocalDate due = r.get("due_date") == null ? null : LocalDate.parse(str(r.get("due_date")).substring(0, 10));
        n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("bill_no")));
        n.put("at", iso(ldt(r.get("created_at"))));
        n.put("due", due == null ? null : due.toString());
        n.put("taxable", round2(dbl(r.get("taxable"))));
        n.put("gstPct", round2(dbl(r.get("gst_pct"))));
        n.put("gst", round2(dbl(r.get("gst_amount"))));
        n.put("total", tot);
        n.put("paid", paid);
        n.put("balance", round2(tot - paid));
        n.put("pieces", lng(r.get("pieces")));
        n.put("by", str(r.get("billed_by_name")));
        n.put("notes", str(r.get("inv_notes")));
        n.put("status", tot - paid < 0.005 ? "paid" : paid > 0 ? "partial" : "unpaid");
        n.put("overdue", tot - paid >= 0.005 && due != null && due.isBefore(today));
        return n;
    }

    /** one retailer: info, every invoice (total / paid / balance), every payment */
    public Map<String, Object> party(String auth, String id) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        requireWholesale(c);
        Map<String, Object> p = db.one("SELECT * FROM parties WHERE company_id = ? AND id = ?", cid, id);
        if (p == null) throw new ApiException(404, "Retailer இல்லை");
        LocalDate today = LocalDate.now(zone);
        List<Map<String, Object>> rows = db.query("SELECT * FROM bills WHERE company_id = ? AND party_id = ? AND bill_kind = 'invoice' ORDER BY created_at DESC, id DESC", cid, id);
        List<Object> invs = new ArrayList<>();
        double billed = 0, paid = 0, over = 0;
        for (Map<String, Object> r : rows) {
            Map<String, Object> n = invoiceRow(r, today);
            billed += (Double) n.get("total");
            paid += (Double) n.get("paid");
            if (Boolean.TRUE.equals(n.get("overdue"))) over += (Double) n.get("balance");
            invs.add(n);
        }
        // lines of the invoices (for the printable invoice)
        Map<Long, List<Object>> lines = new HashMap<>();
        for (Map<String, Object> l : db.query("SELECT l.bill_id, l.item_name, l.item_name_en, l.size_label, l.qty, l.rate, l.amount FROM bill_lines l JOIN bills b ON b.company_id = l.company_id AND b.id = l.bill_id "
                + "WHERE b.company_id = ? AND b.party_id = ? AND b.bill_kind = 'invoice' ORDER BY l.bill_id, l.line_no", cid, id)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", str(l.get("item_name")));
            n.put("en", str(l.get("item_name_en")));
            n.put("size", str(l.get("size_label")));
            n.put("qty", lng(l.get("qty")));
            n.put("rate", dbl(l.get("rate")));
            n.put("amt", dbl(l.get("amount")));
            lines.computeIfAbsent(lng(l.get("bill_id")), k -> new ArrayList<>()).add(n);
        }
        for (Object o : invs) { Map<String, Object> n = map(o); n.put("lines", lines.getOrDefault(lng(n.get("id")), new ArrayList<>())); }
        List<Object> pays = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT x.*, b.bill_no FROM party_payments x LEFT JOIN bills b ON b.company_id = x.company_id AND b.id = x.bill_id "
                + "WHERE x.company_id = ? AND x.party_id = ? ORDER BY x.paid_on DESC, x.id DESC", cid, id)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", lng(r.get("id")));
            n.put("on", str(r.get("paid_on")).substring(0, 10));
            n.put("amount", round2(dbl(r.get("amount"))));
            n.put("mode", str(r.get("mode")));
            n.put("ref", str(r.get("ref")));
            n.put("billNo", str(r.get("bill_no")));
            n.put("by", str(r.get("by_name")));
            pays.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("party", partyRow(p));
        o.put("invoices", invs);
        o.put("payments", pays);
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("invoices", invs.size());
        sm.put("billed", round2(billed));
        sm.put("paid", round2(paid));
        sm.put("pending", round2(billed - paid));
        sm.put("overdue", round2(over));
        o.put("summary", sm);
        o.put("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", cid)));
        o.put("canEdit", !c.owner());
        return o;
    }

    /**
     * Wholesale tax invoice for a retailer (credit sale): {partyId, lines, gstPct, dueDays, notes, paid: {amount, mode, ref}}.
     * Saved as a bill (bill_kind 'invoice', number PREFIX-W0001) so stock, sales report, profit and the software invoice
     * include it. Sales staff use catalog rates; stock / admin may give a wholesale rate.
     */
    public Map<String, Object> createInvoice(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        requireWholesale(c);
        String branch = userBranch(c, cid);
        Map<String, Object> party = db.one("SELECT * FROM parties WHERE company_id = ? AND id = ?", cid, str(in.get("partyId")));
        if (party == null) throw new ApiException(400, "Retailer-ஐ தேர்வு செய்யவும்");
        List<Map<String, Object>> lines = mergeLines(parseLines(list(in.get("lines"))));
        if (lines.isEmpty()) throw new ApiException(400, "Invoice-ல் ஐட்டம் இல்லை");
        if (c.sales()) {
            Map<String, Double> rates = new HashMap<>();
            for (Map<String, Object> z : db.query("SELECT item_id, size_label, COALESCE(wrate, rate) AS rate FROM item_sizes WHERE company_id = ?", cid))
                rates.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), dbl(z.get("rate")));
            for (Map<String, Object> l : lines) {
                Double r = l.get("id") == null ? null : rates.get(l.get("id") + "|" + l.get("size"));
                if (r != null && Math.abs(r - (Double) l.get("rate")) > 0.001) throw new ApiException(400, "Wholesale விலை மாற்ற அனுமதி இல்லை: " + l.get("name"));
            }
        }
        double gstPct = round2(dbl(in.get("gstPct")));
        if (!Set.of(0.0, 3.0, 5.0, 12.0, 18.0, 28.0).contains(gstPct)) throw new ApiException(400, "GST % தவறு");
        long dueDays = in.get("dueDays") == null ? lng(party.get("credit_days")) : Math.max(0, Math.min(365, lng(in.get("dueDays"))));
        double taxable = 0;
        long pcs = 0;
        for (Map<String, Object> l : lines) { taxable += (Double) l.get("amt"); pcs += (Long) l.get("qty"); }
        taxable = round2(taxable);
        final double tx = taxable, gst = round2(taxable * gstPct / 100), total = round2(taxable + gst);
        final long pieces = pcs;
        Map<String, Object> pay = in.get("paid") instanceof Map ? map(in.get("paid")) : new HashMap<>();
        double paidNow = round2(dbl(pay.get("amount")));
        if (Double.isNaN(paidNow) || paidNow < 0) paidNow = 0;
        if (paidNow > total + 0.001) throw new ApiException(400, "செலுத்திய தொகை invoice தொகையை விட அதிகம்");
        final double pn = paidNow;
        final String pmode = blank(str(pay.get("mode"))) ? "Cash" : clip(pay.get("mode"), 30);
        String by = str(c.user.get("full_name"));
        long billId = db.tx(t -> {
            Map<String, Object> co = t.one("SELECT bill_seq, bill_prefix FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq = lng(co.get("bill_seq")) + 1;
            String prefix = str(co.get("bill_prefix"));
            if (prefix.isEmpty()) prefix = "INV";
            String no = prefix + "-W" + String.format("%04d", seq);
            t.update("UPDATE companies SET bill_seq = ? WHERE id = ?", seq, cid);
            LocalDateTime at = now();
            long id = t.insert("INSERT INTO bills (company_id, branch_id, bill_seq, bill_no, created_at, customer_name, customer_phone, billed_by_user, billed_by_name, pieces, total, "
                    + "pay_mode_id, pay_name, pay_kind, platform_fee, bill_kind, party_id, due_date, taxable, gst_pct, gst_amount, paid_amount, inv_notes) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'Credit (Invoice)', 'credit', (SELECT bill_fee FROM companies WHERE id = ?), 'invoice', ?, ?, ?, ?, ?, ?, ?)",
                    cid, branch, seq, no, at, str(party.get("name")), str(party.get("phone")), str(c.user.get("username")), by, pieces, BigDecimal.valueOf(total),
                    cid, str(party.get("id")), at.toLocalDate().plusDays(dueDays), BigDecimal.valueOf(tx), BigDecimal.valueOf(gstPct), BigDecimal.valueOf(gst),
                    BigDecimal.valueOf(pn), clip(in.get("notes"), 300));
            Link lk = link(t, cid);
            List<Object[]> rows = new ArrayList<>(), stockRows = new ArrayList<>();
            int ln = 1;
            for (Map<String, Object> l : lines) {
                String iid = (String) l.get("id");
                if (iid != null && !lk.linked(iid, l)) iid = null;
                rows.add(new Object[]{cid, id, ln++, iid, l.get("name"), l.get("en"), l.get("size"), l.get("qty"),
                        BigDecimal.valueOf((Double) l.get("rate")), iid == null ? null : lk.cost(iid, l), BigDecimal.valueOf((Double) l.get("amt")), l.get("wt")});
                if (iid != null && branch != null) {
                    long dq = lk.stockQty(iid, l);
                    stockRows.add(new Object[]{cid, branch, iid, lk.stockSize(iid, l), -dq, at, "invoice " + no, dq, at, "invoice " + no});
                }
            }
            t.batch("INSERT INTO bill_lines (company_id, bill_id, line_no, item_id, item_name, item_name_en, size_label, qty, rate, cost, amount, wt_g) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
            if (!stockRows.isEmpty())
                t.batch("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = qty - ?, updated_at = ?, updated_by = ?", stockRows);
            if (pn > 0)
                t.update("INSERT INTO party_payments (company_id, party_id, bill_id, amount, paid_on, mode, ref, created_at, by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        cid, str(party.get("id")), id, BigDecimal.valueOf(pn), at.toLocalDate(), pmode, clip(pay.get("ref"), 60), at, by);
            return id;
        });
        Map<String, Object> o = party(auth, str(party.get("id")));
        o.put("invoiceId", billId);
        return o;
    }

    /**
     * Payment received from a retailer: {amount, date, mode, ref, billId?}. With billId it goes to that invoice,
     * otherwise to the oldest unpaid invoices first. Not more than what is pending.
     */
    public Map<String, Object> partyPayment(String auth, String partyId, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        requireWholesale(c);
        if (db.one("SELECT id FROM parties WHERE company_id = ? AND id = ?", cid, partyId) == null) throw new ApiException(404, "Retailer இல்லை");
        double amt = round2(dbl(in.get("amount")));
        if (Double.isNaN(amt) || amt <= 0) throw new ApiException(400, "தொகை தவறு");
        LocalDate on = LocalDate.now(zone);
        if (!blank(str(in.get("date")))) {
            try { on = LocalDate.parse(str(in.get("date"))); } catch (DateTimeParseException e) { throw new ApiException(400, "தேதி தவறு"); }
            if (on.isAfter(LocalDate.now(zone))) throw new ApiException(400, "எதிர்கால தேதி வேண்டாம்");
        }
        final LocalDate payOn = on;
        String mode = blank(str(in.get("mode"))) ? "Cash" : clip(in.get("mode"), 30);
        String ref = clip(in.get("ref"), 60);
        long only = lng(in.get("billId"));
        String by = str(c.user.get("full_name"));
        db.tx(t -> {
            List<Map<String, Object>> open = t.query("SELECT id, total, paid_amount FROM bills WHERE company_id = ? AND party_id = ? AND bill_kind = 'invoice' AND total > paid_amount"
                    + (only > 0 ? " AND id = ?" : "") + " ORDER BY created_at, id FOR UPDATE", only > 0 ? new Object[]{cid, partyId, only} : new Object[]{cid, partyId});
            double pending = 0;
            for (Map<String, Object> r : open) pending += dbl(r.get("total")) - dbl(r.get("paid_amount"));
            if (amt > round2(pending) + 0.001)
                throw new ApiException(400, "நிலுவை " + round2(pending) + " மட்டுமே — அதிகமாக பதிவு செய்ய முடியாது");
            double left = amt;
            LocalDateTime now = now();
            for (Map<String, Object> r : open) {
                if (left < 0.005) break;
                double bal = round2(dbl(r.get("total")) - dbl(r.get("paid_amount"))), put = Math.min(bal, left);
                t.update("UPDATE bills SET paid_amount = paid_amount + ? WHERE id = ?", BigDecimal.valueOf(round2(put)), lng(r.get("id")));
                t.update("INSERT INTO party_payments (company_id, party_id, bill_id, amount, paid_on, mode, ref, created_at, by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        cid, partyId, lng(r.get("id")), BigDecimal.valueOf(round2(put)), payOn, mode, ref, now, by);
                left = round2(left - put);
            }
            return null;
        });
        return party(auth, partyId);
    }

    // ================================================================ cancel bills (ரத்து பில்) - a separate document, the original bill stays
    /** documents that are charged in the software invoice: every bill and every cancel bill */
    private static final String FEE_DOCS = "(SELECT company_id, created_at, platform_fee FROM bills UNION ALL SELECT company_id, created_at, platform_fee FROM cancel_bills)";

    private Map<String, Object> cancelSummary(String cid, String br, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> r = db.one("SELECT COUNT(*) AS n, COALESCE(SUM(total), 0) AS t, COALESCE(SUM(pieces), 0) AS p FROM cancel_bills WHERE company_id = ? AND created_at >= ? AND created_at < ?"
                + (br == null ? "" : " AND branch_id = ?"), br == null ? new Object[]{cid, from, to} : new Object[]{cid, from, to, br});
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("count", lng(r.get("n")));
        n.put("total", round2(dbl(r.get("t"))));
        n.put("pieces", lng(r.get("p")));
        return n;
    }

    /**
     * New cancel bill, made like a normal bill: the items being cancelled / returned are added and saved as their own
     * document (RASI-C0001 ...). The original bill is never changed. Catalog pieces go back to the branch stock.
     * {customer, phone, refBill, reason, lines:[...]}
     */
    public Map<String, Object> addCancelBill(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        final String clientId = clientId(in.get("clientId"));
        if (!clientId.isEmpty()) {
            Map<String, Object> dup = db.one("SELECT * FROM cancel_bills WHERE company_id = ? AND client_id = ?", cid, clientId);
            if (dup != null) { Map<String, Object> o = cancelRow(dup); o.put("duplicate", true); o.put("clientId", clientId); return obj("cancel", o); }
        }
        try {
            return addCancelBillNew(c, cid, clientId, in);
        } catch (DbException e) {
            if (!clientId.isEmpty() && e.isConstraintViolation()) {
                Map<String, Object> dup = db.one("SELECT * FROM cancel_bills WHERE company_id = ? AND client_id = ?", cid, clientId);
                if (dup != null) { Map<String, Object> o = cancelRow(dup); o.put("duplicate", true); o.put("clientId", clientId); return obj("cancel", o); }
            }
            throw e;
        }
    }

    private Map<String, Object> addCancelBillNew(Ctx c, String cid, String clientId, Map<String, Object> in) {
        final boolean offline = bool(in.get("offline"));
        final String deviceId = clientId.isEmpty() ? "" : clip(in.get("deviceId"), 40);
        String reason = clip(in.get("reason"), 120);
        if (reason.isEmpty()) throw new ApiException(400, "ரத்து செய்யும் காரணம் தேவை");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!phone.isEmpty() && !MOBILE.matcher(phone).matches()) throw new ApiException(400, "மொபைல் எண் தவறு");
        String ref = clip(in.get("refBill"), 30).toUpperCase(Locale.ROOT);
        Map<String, Object> refRow = ref.isEmpty() ? null : db.one("SELECT id, branch_id, bill_kind, party_id, gst_pct FROM bills WHERE company_id = ? AND bill_no = ?", cid, ref);
        final boolean inv = refRow != null && "invoice".equals(str(refRow.get("bill_kind")));
        if (inv) requireWholesale(c); else requireSelling(c);
        // wholesale invoice: stock goes back to the place it was sold from (warehouse / shop)
        String branch = inv ? str(refRow.get("branch_id")) : userBranch(c, cid);
        final double gstPct = inv ? dbl(refRow.get("gst_pct")) : 0;
        if (!ref.isEmpty() && refRow == null)
            throw new ApiException(404, "பில் எண் " + ref + " இல்லை — சரி பார்க்கவும் அல்லது காலியாக விடவும்");
        List<Map<String, Object>> lines = mergeLines(parseLines(list(in.get("lines"))));
        if (refRow != null) {
            // items of the original bill: not more than what is still left on it (sold − cancelled earlier)
            Map<String, long[]> left = billLeft(cid, lng(refRow.get("id")), ref);
            for (Map<String, Object> l : lines) {
                long[] lf = left.get(lineKey(l.get("id"), l.get("name"), l.get("size")));
                if (lf != null && (Long) l.get("qty") > lf[0])
                    throw new ApiException(400, l.get("name") + " (" + l.get("size") + "): " + ref + "-ல் ரத்து செய்ய மீதம் " + lf[0] + " மட்டும்");
            }
        }
        if (lines.isEmpty()) throw new ApiException(400, "ரத்து செய்ய ஐட்டம் இல்லை");
        boolean staleList = offline && lng(in.get("catalogVersion")) != lng(db.one("SELECT catalog_version FROM companies WHERE id = ?", cid).get("catalog_version"));
        if (c.sales() && !inv && !staleList) {             // sales staff: catalog items at the catalog rate only (not for an offline bill made with an older price list)
            Map<String, Double> rates = new HashMap<>();
            for (Map<String, Object> z : db.query("SELECT item_id, size_label, rate FROM item_sizes WHERE company_id = ?", cid))
                rates.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), dbl(z.get("rate")));
            for (Map<String, Object> l : lines) {
                Double r = l.get("id") == null ? null : rates.get(l.get("id") + "|" + l.get("size"));
                if (r != null && Math.abs(r - (Double) l.get("rate")) > 0.001) throw new ApiException(400, "ரேட் மாற்ற அனுமதி இல்லை: " + l.get("name"));
            }
        }
        double total = 0;
        long pcs = 0;
        for (Map<String, Object> l : lines) { total += (Double) l.get("amt"); pcs += (Long) l.get("qty"); }
        final double tot = round2(inv ? total * (1 + gstPct / 100.0) : total);   // invoice: GST is cancelled too
        final long pieces = pcs;
        String by = str(c.user.get("full_name"));
        Map<String, Object> out = db.tx(t -> {
            Map<String, Object> co = t.one("SELECT cn_seq, bill_prefix, bill_fee FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq;
            long rs = lng(in.get("reservedSeq"));
            if (reserved(t, cid, deviceId, "cancel", rs) && t.one("SELECT 1 AS x FROM cancel_bills WHERE company_id = ? AND cn_seq = ?", cid, rs) == null) {
                seq = rs;
            } else {
                seq = lng(co.get("cn_seq")) + 1;
                t.update("UPDATE companies SET cn_seq = ? WHERE id = ?", seq, cid);
            }
            String no = str(co.get("bill_prefix")) + "-C" + String.format("%04d", seq);
            LocalDateTime at = clientTime(in.get("clientAt"), now());
            Link lk = link(t, cid);                                // cost price now: the cancel bill keeps it, so profit is reduced by the right margin
            for (Map<String, Object> l : lines) {
                String iid = (String) l.get("id");
                if (iid != null && !lk.linked(iid, l)) { l.put("id", null); continue; }
                if (iid == null) continue;
                Object cst = lk.cost(iid, l);
                if (cst != null) l.put("cost", dbl(cst));
                if (branch != null) {
                    long dq = lk.stockQty(iid, l);
                    t.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE qty = qty + ?, updated_at = ?, updated_by = ?",
                            cid, branch, iid, lk.stockSize(iid, l), dq, at, "ரத்து " + no, dq, at, "ரத்து " + no + " · " + by);
                }
            }
            t.update("INSERT INTO cancel_bills (company_id, branch_id, cn_seq, cn_no, ref_bill_no, created_at, customer_name, customer_phone, by_user, by_name, pieces, total, reason, lines_json, platform_fee, client_id, device_id, offline, synced_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", cid, branch, seq, no, ref, at, clip(in.get("customer"), 60), phone,
                    str(c.user.get("username")), by, pieces, BigDecimal.valueOf(tot), reason, toJson(lines), co.get("bill_fee"),
                    clientId.isEmpty() ? null : clientId, clientId.isEmpty() || deviceId.isEmpty() ? null : deviceId, offline ? 1 : 0, clientId.isEmpty() ? null : now());
            if (inv && refRow.get("party_id") != null) {
                // credit note: reduces what the retailer owes — first this invoice, then other open invoices (oldest first)
                String pid = str(refRow.get("party_id"));
                long refId = lng(refRow.get("id"));
                double left = tot;
                List<Map<String, Object>> open = t.query("SELECT id, total, paid_amount FROM bills WHERE company_id = ? AND party_id = ? AND bill_kind = 'invoice' AND total > paid_amount "
                        + "ORDER BY (id = ?) DESC, created_at, id FOR UPDATE", cid, pid, refId);
                for (Map<String, Object> o : open) {
                    if (left < 0.005) break;
                    double put = round2(Math.min(round2(dbl(o.get("total")) - dbl(o.get("paid_amount"))), left));
                    t.update("UPDATE bills SET paid_amount = paid_amount + ? WHERE id = ?", BigDecimal.valueOf(put), lng(o.get("id")));
                    t.update("INSERT INTO party_payments (company_id, party_id, bill_id, amount, paid_on, mode, ref, created_at, by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            cid, pid, lng(o.get("id")), BigDecimal.valueOf(put), at.toLocalDate(), "ரத்து (Credit note)", no, at, by);
                    left = round2(left - put);
                }
            }
            return cancelRow(t.one("SELECT * FROM cancel_bills WHERE company_id = ? AND cn_no = ?", cid, no));
        });
        return obj("cancel", out);
    }

    private static String lineKey(Object id, Object name, Object size) {
        return (id != null ? "i:" + id : "c:" + str(name).toLowerCase(Locale.ROOT)) + "|" + str(size);
    }

    /** per line of a bill: {qty still not cancelled, qty sold, qty cancelled} - earlier cancel bills that name this bill are taken off */
    private Map<String, long[]> billLeft(String cid, long billId, String billNo) {
        Map<String, long[]> m = new LinkedHashMap<>();
        for (Map<String, Object> l : db.query("SELECT item_id, item_name, size_label, qty FROM bill_lines WHERE company_id = ? AND bill_id = ? ORDER BY line_no", cid, billId)) {
            long[] v = m.computeIfAbsent(lineKey(l.get("item_id"), l.get("item_name"), l.get("size_label")), k -> new long[3]);
            v[0] += lng(l.get("qty")); v[1] += lng(l.get("qty"));
        }
        for (Map<String, Object> x : db.query("SELECT lines_json FROM cancel_bills WHERE company_id = ? AND ref_bill_no = ?", cid, billNo))
            for (Object o : jsonList(x.get("lines_json"))) {
                Map<String, Object> l = map(o);
                long[] v = m.get(lineKey(l.get("id"), l.get("name"), l.get("size")));
                if (v != null) { long q = lng(l.get("qty")); v[0] = Math.max(0, v[0] - q); v[2] += q; }
            }
        return m;
    }

    /**
     * Bills of one mobile number in this month and the months before (?months=1..12, default 3) (sales / stock: own branch), with what is still
     * left to cancel on every line - for "cancel from a bill" (பில்லிலிருந்து ரத்து).
     */
    /** one bill (retail or wholesale invoice) with what is still left to cancel per line */
    private Map<String, Object> cancelView(String cid, Map<String, Object> r, Map<String, Object> names) {
        long id = lng(r.get("id"));
        String no = str(r.get("bill_no"));
        Map<String, long[]> left = billLeft(cid, id, no);
        List<Object> ls = new ArrayList<>();
        long leftPcs = 0;
        for (Map<String, Object> l : db.query("SELECT item_id, item_name, item_name_en, size_label, qty, rate, amount, wt_g FROM bill_lines WHERE company_id = ? AND bill_id = ? ORDER BY line_no", cid, id)) {
            String k = lineKey(l.get("item_id"), l.get("item_name"), l.get("size_label"));
            long[] v = left.get(k);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", l.get("item_id") == null ? null : str(l.get("item_id")));
            n.put("name", str(l.get("item_name")));
            n.put("en", str(l.get("item_name_en")));
            n.put("size", str(l.get("size_label")));
            n.put("qty", lng(l.get("qty")));
            n.put("rate", dbl(l.get("rate")));
            if (l.get("wt_g") != null) n.put("wt", lng(l.get("wt_g")));       // weight line: grams of one portion (stock goes back in grams)
            long lf = v == null ? 0 : Math.min(v[0], lng(l.get("qty")));
            if (v != null) v[0] -= lf;                     // same item twice on a bill: share what is left
            n.put("left", lf);
            n.put("cancelled", lng(l.get("qty")) - lf);
            leftPcs += lf;
            ls.add(n);
        }
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("no", no);
        n.put("at", iso(ldt(r.get("created_at"))));
        n.put("customer", str(r.get("customer_name")));
        n.put("phone", str(r.get("customer_phone")));
        n.put("by", str(r.get("billed_by_name")));
        n.put("branch", names.get(str(r.get("branch_id"))));
        n.put("pieces", lng(r.get("pieces")));
        n.put("total", round2(dbl(r.get("total"))));
        n.put("leftPieces", leftPcs);
        n.put("lines", ls);
        n.put("kind", str(r.get("bill_kind")));
        n.put("gstPct", dbl(r.get("gst_pct")));
        n.put("partyId", r.get("party_id") == null ? null : str(r.get("party_id")));
        return n;
    }

    /** one bill by its number, for cancelling (retail: shops; wholesale invoice: warehouse / shop staff, never sales) */
    public Map<String, Object> billForCancel(String auth, String noIn) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        String no = clip(noIn, 30).toUpperCase(Locale.ROOT);
        Map<String, Object> r = db.one("SELECT * FROM bills WHERE company_id = ? AND bill_no = ?", cid, no);
        if (r == null) throw new ApiException(404, "பில் எண் " + no + " இல்லை");
        if ("invoice".equals(str(r.get("bill_kind")))) requireWholesale(c); else requireSelling(c);
        if (!c.admin() && !"invoice".equals(str(r.get("bill_kind")))) {
            String br = userBranch(c, cid);
            if (br != null && !br.equals(str(r.get("branch_id")))) throw new ApiException(403, "இந்த பில் வேறு கிளையுடையது");
        }
        Map<String, Object> names = new HashMap<>();
        for (Map<String, Object> b : db.query("SELECT id, name FROM branches WHERE company_id = ?", cid)) names.put(str(b.get("id")), str(b.get("name")));
        return obj("bill", cancelView(cid, r, names));
    }

    public Map<String, Object> billsByPhone(String auth, String phoneIn, String monthsIn) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String phone = str(phoneIn).replaceAll("[^0-9]", "");
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String br = c.admin() ? null : userBranch(c, cid);
        int months = 3;
        try { if (!blank(monthsIn)) months = Math.max(1, Math.min(12, Integer.parseInt(monthsIn.trim()))); } catch (NumberFormatException e) { /* default */ }
        LocalDateTime from = LocalDate.now(zone).withDayOfMonth(1).minusMonths(months - 1).atStartOfDay();
        List<Map<String, Object>> rows = db.query("SELECT * FROM bills WHERE company_id = ? AND customer_phone = ? AND created_at >= ?" + (br == null ? "" : " AND branch_id = ?")
                + " ORDER BY id DESC LIMIT 60", br == null ? new Object[]{cid, phone, from} : new Object[]{cid, phone, from, br});
        List<Object> out = new ArrayList<>();
        Map<String, Object> names = new HashMap<>();
        for (Map<String, Object> r : rows) names.put(str(r.get("branch_id")), null);
        for (Map<String, Object> b : db.query("SELECT id, name FROM branches WHERE company_id = ?", cid)) names.put(str(b.get("id")), str(b.get("name")));
        for (Map<String, Object> r : rows) out.add(cancelView(cid, r, names));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("phone", phone);
        o.put("from", from.toLocalDate().toString());
        o.put("months", months);
        o.put("bills", out);
        return o;
    }

    private Map<String, Object> cancelRow(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("cn_no")));
        n.put("refBill", str(r.get("ref_bill_no")));
        n.put("at", iso(ldt(r.get("created_at"))));
        n.put("customer", str(r.get("customer_name")));
        n.put("phone", str(r.get("customer_phone")));
        n.put("by", str(r.get("by_name")));
        n.put("pieces", lng(r.get("pieces")));
        n.put("total", round2(dbl(r.get("total"))));
        n.put("reason", str(r.get("reason")));
        n.put("branchId", str(r.get("branch_id")));
        n.put("lines", jsonList(r.get("lines_json")));
        n.put("usedBill", str(r.get("used_bill_no")));
        return n;
    }

    /** unused cancel bills of a mobile number (any branch) - credit for an exchange bill */
    public Map<String, Object> exchangeOpen(String auth, String phoneIn) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String phone = str(phoneIn).replaceAll("[^0-9]", "");
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        List<Object> list = new ArrayList<>();
        double sum = 0;
        for (Map<String, Object> r : db.query("SELECT * FROM cancel_bills WHERE company_id = ? AND customer_phone = ? AND used_bill_no = '' ORDER BY id DESC LIMIT 20", cid, phone)) {
            list.add(cancelRow(r));
            sum += dbl(r.get("total"));
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("phone", phone);
        o.put("cancels", list);
        o.put("total", round2(sum));
        return o;
    }

    /** cancel bill history: sales / stock own branch, admin / owner all (?branch=) */
    public Map<String, Object> cancelBills(String auth, String branch) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        if (c.warehouse()) throw new ApiException(403, "அனுமதி இல்லை");
        String br = c.admin() || c.owner() ? (blank(branch) ? null : checkBranch(cid, branch)) : userBranch(c, cid);
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM cancel_bills WHERE company_id = ?" + (br == null ? "" : " AND branch_id = ?") + " ORDER BY id DESC LIMIT 200",
                br == null ? new Object[]{cid} : new Object[]{cid, br}))
            list.add(cancelRow(r));
        LocalDate today = LocalDate.now(zone);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("cancels", list);
        o.put("today", cancelSummary(cid, br, today.atStartOfDay(), today.plusDays(1).atStartOfDay()));
        o.put("month", cancelSummary(cid, br, today.withDayOfMonth(1).atStartOfDay(), today.withDayOfMonth(1).plusMonths(1).atStartOfDay()));
        return o;
    }

    /** Bills with their lines, in the JSON shape used by the app (bill history, WhatsApp re-send, report). */
    private List<Object> billList(String cid, List<Map<String, Object>> rows) {
        Map<Long, List<Object>> lines = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            StringBuilder in = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(cid);
            for (Map<String, Object> r : rows) {
                in.append(in.length() == 0 ? "?" : ", ?");
                args.add(lng(r.get("id")));
            }
            for (Map<String, Object> l : db.query("SELECT * FROM bill_lines WHERE company_id = ? AND bill_id IN (" + in + ") ORDER BY bill_id, line_no", args.toArray())) {
                Map<String, Object> n = new LinkedHashMap<>();
                if (l.get("item_id") != null) n.put("id", str(l.get("item_id")));
                n.put("name", str(l.get("item_name")));
                n.put("en", str(l.get("item_name_en")));
                n.put("size", str(l.get("size_label")));
                n.put("qty", lng(l.get("qty")));
                n.put("rate", dbl(l.get("rate")));
                n.put("amt", dbl(l.get("amount")));
                if (l.get("wt_g") != null) n.put("wt", lng(l.get("wt_g")));
                lines.computeIfAbsent(lng(l.get("bill_id")), k -> new ArrayList<>()).add(n);
            }
        }
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", lng(r.get("id")));
            b.put("no", str(r.get("bill_no")));
            b.put("seq", lng(r.get("bill_seq")));
            b.put("at", iso(ldt(r.get("created_at"))));
            b.put("customer", str(r.get("customer_name")));
            b.put("phone", str(r.get("customer_phone")));
            b.put("by", str(r.get("billed_by_name")));
            b.put("byUser", str(r.get("billed_by_user")));
            b.put("lines", lines.getOrDefault(lng(r.get("id")), new ArrayList<>()));
            b.put("pieces", lng(r.get("pieces")));
            b.put("total", dbl(r.get("total")));
            b.put("pay", str(r.get("pay_name")));
            b.put("payKind", str(r.get("pay_kind")));
            b.put("exchange", round2(dbl(r.get("exchange_amount"))));
            b.put("exchangeRef", str(r.get("exchange_ref")));
            b.put("paid", round2(dbl(r.get("total")) - dbl(r.get("exchange_amount"))));
            b.put("branchId", str(r.get("branch_id")));
            list.add(b);
        }
        return list;
    }

    // ================================================================ sales report / statistics
    static final int REPORT_BILL_LIMIT = 500;

    /** "2026-11-02" -> date; blank -> today. Future dates and dates before 2020 are refused. */
    private LocalDate reportDate(String s) {
        LocalDate today = LocalDate.now(zone);
        if (blank(s)) return today;
        LocalDate d;
        try { d = LocalDate.parse(s.trim()); } catch (DateTimeParseException e) { throw new ApiException(400, "தவறான தேதி (Invalid date)"); }
        if (d.isAfter(today)) throw new ApiException(400, "எதிர்கால தேதி அனுமதி இல்லை (Future date)");
        if (d.getYear() < 2020) throw new ApiException(400, "தவறான தேதி (Invalid date)");
        return d;
    }

    /** query args with the optional branch filter appended (the filter is always the last condition) */
    static Object[] args(String branch, Object... a) { return branch == null ? a : concat(a, branch); }

    /** count / total / avg / max / pieces of bills in [from, to), optionally of one branch */
    private Map<String, Object> salesSummary(String cid, String br, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> r = db.one("SELECT COUNT(*) AS cnt, COALESCE(SUM(total), 0) AS tot, COALESCE(MAX(total), 0) AS mx, "
                + "COALESCE(SUM(pieces), 0) AS pcs FROM bills b WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + bf(br), args(br, cid, from, to));
        long cnt = lng(r.get("cnt"));
        double tot = round2(dbl(r.get("tot")));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("count", cnt);
        o.put("total", tot);
        o.put("avg", cnt == 0 ? 0.0 : round2(tot / cnt));
        o.put("max", round2(dbl(r.get("mx"))));
        o.put("pieces", lng(r.get("pcs")));
        return o;
    }

    /**
     * sales = all line amounts; costed = amounts of lines whose cost was known at billing time;
     * cogs = cost x qty of those lines; profit = costed - cogs; margin % on costed sales.
     */
    private Map<String, Object> profit(String cid, String br, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> r = db.one("SELECT COALESCE(SUM(l.amount), 0) AS sales, COALESCE(SUM(CASE WHEN l.cost IS NOT NULL THEN l.amount END), 0) AS costed, "
                + "COALESCE(SUM(l.cost * l.qty), 0) AS cogs, COALESCE(SUM(CASE WHEN l.cost IS NULL THEN l.qty END), 0) AS nocost_pcs "
                + "FROM bill_lines l JOIN bills b ON b.company_id = l.company_id AND b.id = l.bill_id "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + bf(br), args(br, cid, from, to));
        double sales = round2(dbl(r.get("sales"))), costed = round2(dbl(r.get("costed"))), cogs = round2(dbl(r.get("cogs")));
        long noCostPcs = lng(r.get("nocost_pcs"));
        // cancel bills (ரத்து பில்) of the same period come off: their amount from sales, their cost from the cost of goods
        double cxSales = 0, cxProfit = 0;
        List<Map<String, Object>> cxRows = db.query("SELECT lines_json FROM cancel_bills WHERE company_id = ? AND created_at >= ? AND created_at < ?"
                + (br == null ? "" : " AND branch_id = ?"), br == null ? new Object[]{cid, from, to} : new Object[]{cid, from, to, br});
        if (!cxRows.isEmpty()) {
            Map<String, Object> curCost = null;
            for (Map<String, Object> x : cxRows) {
                for (Object o : jsonList(x.get("lines_json"))) {
                    Map<String, Object> l = map(o);
                    double amt = dbl(l.get("amt")), q = dbl(l.get("qty"));
                    Object cost = l.get("cost");
                    if (cost == null && l.get("id") != null) {           // older cancel bills without a cost snapshot: today's catalog cost
                        if (curCost == null) {
                            curCost = new HashMap<>();
                            for (Map<String, Object> z : db.query("SELECT item_id, size_label, cost FROM item_sizes WHERE company_id = ? AND cost IS NOT NULL", cid))
                                curCost.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), z.get("cost"));
                        }
                        cost = curCost.get(str(l.get("id")) + "|" + str(l.get("size")));
                    }
                    sales -= amt;
                    cxSales += amt;
                    if (cost != null) {
                        double cg = dbl(cost) * q;
                        costed -= amt;
                        cogs -= cg;
                        cxProfit += amt - cg;
                    } else noCostPcs -= (long) q;
                }
            }
            sales = round2(sales); costed = round2(costed); cogs = round2(cogs);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("sales", sales);
        o.put("cancelSales", round2(cxSales));
        o.put("cancelProfit", round2(cxProfit));
        o.put("costedSales", costed);
        o.put("cost", cogs);
        o.put("profit", round2(costed - cogs));
        o.put("margin", costed > 0 ? Math.round((costed - cogs) * 1000.0 / costed) / 10.0 : null);
        o.put("noCostSales", round2(sales - costed));
        o.put("noCostPieces", Math.max(0, noCostPcs));
        return o;
    }

    private static String bf(String br) { return br == null ? "" : " AND b.branch_id = ?"; }

    private static Double growth(double cur, double prev) {
        if (prev <= 0) return null;                  // no base to compare
        return Math.round((cur - prev) * 1000.0 / prev) / 10.0;
    }

    /**
     * Sales statistics of the company in the session.
     * owner / admin: whole company, or one branch with ?branch=; sales / stock: always their own branch.
     * date = the selected day (yyyy-MM-dd, default today). Month figures are for the month of that day.
     */
    public Map<String, Object> report(String auth, String date, String branch) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        if (c.sales()) throw new ApiException(403, "Sales user-க்கு விற்பனை அறிக்கை அனுமதி இல்லை");
        boolean wide = c.admin() || c.owner();
        String br;
        if (wide) {
            br = blank(branch) ? null : branch.trim();
            if (br != null && branchRow(cid, br) == null) throw new ApiException(404, "கிளை இல்லை (Branch not found)");
        } else br = userBranch(c, cid);
        String f = bf(br);

        LocalDate today = LocalDate.now(zone);
        LocalDate sel = reportDate(date);
        LocalDateTime dFrom = sel.atStartOfDay(), dTo = sel.plusDays(1).atStartOfDay();
        LocalDate m1 = sel.withDayOfMonth(1);
        LocalDateTime mFrom = m1.atStartOfDay(), mTo = m1.plusMonths(1).atStartOfDay();
        LocalDateTime pFrom = m1.minusMonths(1).atStartOfDay();
        boolean curMonth = m1.equals(today.withDayOfMonth(1));
        // same-period comparison: 1..N of previous month when the current month is still running
        LocalDateTime pSameTo = curMonth ? pFrom.plusDays(Math.min(today.getDayOfMonth(), m1.minusMonths(1).lengthOfMonth())) : mFrom;

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("date", sel.toString());
        o.put("today", today.toString());
        o.put("month", m1.toString().substring(0, 7));
        List<Object> branches = branchList(cid, "shop");                    // warehouses never sell
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("branch", br);
        Map<String, Object> brRow = branchRow(cid, br);
        scope.put("branchName", brRow == null ? "" : str(brRow.get("name")));
        scope.put("canFilter", wide);
        scope.put("branchCount", branches.size());
        o.put("scope", scope);
        if (wide) o.put("branches", branches);

        Map<String, Object> todayS = salesSummary(cid, br, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<String, Object> yest = salesSummary(cid, br, today.minusDays(1).atStartOfDay(), today.atStartOfDay());
        todayS.put("growth", growth(dbl(todayS.get("total")), dbl(yest.get("total"))));
        todayS.put("yesterday", yest.get("total"));
        o.put("todaySales", todayS);
        o.put("daySales", salesSummary(cid, br, dFrom, dTo));
        Map<String, Object> cx = new LinkedHashMap<>();
        cx.put("day", cancelSummary(cid, br, dFrom, dTo));
        cx.put("month", cancelSummary(cid, br, mFrom, mTo));
        o.put("cancels", cx);

        Map<String, Object> monthS = salesSummary(cid, br, mFrom, mTo);
        Map<String, Object> prevS = salesSummary(cid, br, pFrom, mFrom);
        Map<String, Object> prevSame = curMonth ? salesSummary(cid, br, pFrom, pSameTo) : prevS;
        int days = curMonth ? today.getDayOfMonth() : m1.lengthOfMonth();
        monthS.put("days", days);
        monthS.put("perDay", round2(dbl(monthS.get("total")) / days));
        monthS.put("prevTotal", prevS.get("total"));
        monthS.put("prevSamePeriod", prevSame.get("total"));
        monthS.put("growth", growth(dbl(monthS.get("total")), dbl(prevSame.get("total"))));
        if (curMonth) monthS.put("projected", round2(dbl(monthS.get("total")) / days * m1.lengthOfMonth()));
        o.put("monthSales", monthS);

        // day-wise trend of the month (every day present, 0 when no sale)
        Map<String, double[]> byDay = new LinkedHashMap<>();
        for (Map<String, Object> r : db.query("SELECT DATE(b.created_at) AS d, COUNT(*) AS cnt, COALESCE(SUM(b.total), 0) AS tot FROM bills b "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + f + " GROUP BY DATE(b.created_at)", args(br, cid, mFrom, mTo)))
            byDay.put(str(r.get("d")).substring(0, 10), new double[]{lng(r.get("cnt")), dbl(r.get("tot"))});
        List<Object> trend = new ArrayList<>();
        Map<String, Object> best = null;
        for (int i = 1; i <= days; i++) {
            String k = m1.withDayOfMonth(i).toString();
            double[] v = byDay.getOrDefault(k, new double[2]);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("d", k);
            p.put("count", (long) v[0]);
            p.put("total", round2(v[1]));
            trend.add(p);
            if (v[1] > 0 && (best == null || v[1] > dbl(best.get("total")))) best = p;
        }
        o.put("trend", trend);
        o.put("bestDay", best);

        // hour-wise sales of the selected day
        long[] hc = new long[24];
        double[] ht = new double[24];
        for (Map<String, Object> r : db.query("SELECT HOUR(b.created_at) AS h, COUNT(*) AS cnt, COALESCE(SUM(b.total), 0) AS tot FROM bills b "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + f + " GROUP BY HOUR(b.created_at)", args(br, cid, dFrom, dTo))) {
            int h = (int) lng(r.get("h"));
            hc[h] = lng(r.get("cnt"));
            ht[h] = round2(dbl(r.get("tot")));
        }
        List<Object> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (hc[h] == 0) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("h", h);
            p.put("count", hc[h]);
            p.put("total", ht[h]);
            hours.add(p);
        }
        o.put("hours", hours);

        o.put("topItemsDay", topItems(cid, br, dFrom, dTo, 5));
        o.put("topItemsMonth", topItems(cid, br, mFrom, mTo, 10));
        o.put("sizesMonth", rows("SELECT l.size_label AS k, SUM(l.qty) AS qty, SUM(l.amount) AS amt FROM bill_lines l JOIN bills b ON b.company_id = l.company_id AND b.id = l.bill_id "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ? AND l.size_label <> ''" + f + " GROUP BY l.size_label ORDER BY qty DESC LIMIT 8", args(br, cid, mFrom, mTo)));

        // branch-wise sales (selected day + month), whole company view only
        if (wide && br == null) {
            List<Object> bs = new ArrayList<>();
            for (Map<String, Object> r : db.query("SELECT br.id, br.name, "
                    + "COALESCE(SUM(b.created_at >= ? AND b.created_at < ?), 0) AS d_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total END), 0) AS d_tot, "
                    + "COUNT(b.id) AS m_cnt, COALESCE(SUM(b.total), 0) AS m_tot "
                    + "FROM branches br LEFT JOIN bills b ON b.company_id = br.company_id AND b.branch_id = br.id AND b.created_at >= ? AND b.created_at < ? "
                    + "WHERE br.company_id = ? AND br.kind = 'shop' GROUP BY br.id, br.name, br.sort_order, br.created_at ORDER BY br.sort_order, br.created_at, br.id",
                    dFrom, dTo, dFrom, dTo, mFrom, mTo, cid)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", str(r.get("id")));
                p.put("name", str(r.get("name")));
                p.put("dayCount", lng(r.get("d_cnt")));
                p.put("dayTotal", round2(dbl(r.get("d_tot"))));
                p.put("monthCount", lng(r.get("m_cnt")));
                p.put("monthTotal", round2(dbl(r.get("m_tot"))));
                bs.add(p);
            }
            o.put("branchSales", bs);
        }

        // payment-mode-wise (selected day + month): Cash, Card, each UPI account ...
        List<Object> ps = new ArrayList<>();
        // money actually received by mode = bill total - exchange; exchange (ரத்து பில் credit) is its own row
        List<Map<String, Object>> payRows = new ArrayList<>(db.query("SELECT b.pay_name AS n, MAX(b.pay_kind) AS k, "
                + "COALESCE(SUM(b.created_at >= ? AND b.created_at < ?), 0) AS d_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total - b.exchange_amount END), 0) AS d_tot, "
                + "COUNT(*) AS m_cnt, COALESCE(SUM(b.total - b.exchange_amount), 0) AS m_tot FROM bills b "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ? AND b.total > b.exchange_amount" + f + " GROUP BY b.pay_name ORDER BY m_tot DESC",
                args(br, dFrom, dTo, dFrom, dTo, cid, mFrom, mTo)));
        Map<String, Object> exr = db.one("SELECT '🔁 Exchange (ரத்து பில்)' AS n, 'exchange' AS k, "
                + "COALESCE(SUM(b.created_at >= ? AND b.created_at < ?), 0) AS d_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.exchange_amount END), 0) AS d_tot, "
                + "COUNT(*) AS m_cnt, COALESCE(SUM(b.exchange_amount), 0) AS m_tot FROM bills b "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ? AND b.exchange_amount > 0" + f, args(br, dFrom, dTo, dFrom, dTo, cid, mFrom, mTo));
        if (exr != null && lng(exr.get("m_cnt")) > 0) payRows.add(exr);
        for (Map<String, Object> r : payRows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", blank(str(r.get("n"))) ? "Cash" : str(r.get("n")));
            p.put("kind", blank(str(r.get("k"))) ? "cash" : str(r.get("k")));
            p.put("dayCount", lng(r.get("d_cnt")));
            p.put("dayTotal", round2(dbl(r.get("d_tot"))));
            p.put("monthCount", lng(r.get("m_cnt")));
            p.put("monthTotal", round2(dbl(r.get("m_tot"))));
            ps.add(p);
        }
        o.put("paySales", ps);

        // real profit from the cost saved on each bill line (cost viewers only)
        if (c.seesCost()) {
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("day", profit(cid, br, dFrom, dTo));
            pr.put("month", profit(cid, br, mFrom, mTo));
            pr.put("prevMonth", profit(cid, br, pFrom, mFrom));
            pr.put("prevSame", curMonth ? profit(cid, br, pFrom, pSameTo) : pr.get("prevMonth"));   // same days of last month
            pr.put("sameDays", curMonth);
            if (wide && br == null) {
                List<Object> pb = new ArrayList<>();
                for (Object bo : branches) {
                    String bid = str(((Map<?, ?>) bo).get("id"));
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("id", bid);
                    n.put("name", str(((Map<?, ?>) bo).get("name")));
                    n.put("day", profit(cid, bid, dFrom, dTo));
                    n.put("month", profit(cid, bid, mFrom, mTo));
                    pb.add(n);
                }
                pr.put("branches", pb);
            }
            o.put("profit", pr);
        }

        // user-wise bills (selected day + month)
        List<Object> us = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT b.billed_by_user AS u, MAX(b.billed_by_name) AS n, MAX(br.name) AS bn, "
                + "COALESCE(SUM(b.created_at >= ? AND b.created_at < ?), 0) AS d_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total END), 0) AS d_tot, "
                + "COUNT(*) AS m_cnt, COALESCE(SUM(b.total), 0) AS m_tot, COALESCE(SUM(b.pieces), 0) AS m_pcs "
                + "FROM bills b LEFT JOIN branches br ON br.company_id = b.company_id AND br.id = b.branch_id "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + f + " GROUP BY b.billed_by_user ORDER BY m_tot DESC",
                args(br, dFrom, dTo, dFrom, dTo, cid, mFrom, mTo))) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("user", str(r.get("u")));
            p.put("name", blank(str(r.get("n"))) ? str(r.get("u")) : str(r.get("n")));
            p.put("branch", str(r.get("bn")));
            p.put("dayCount", lng(r.get("d_cnt")));
            p.put("dayTotal", round2(dbl(r.get("d_tot"))));
            p.put("monthCount", lng(r.get("m_cnt")));
            p.put("monthTotal", round2(dbl(r.get("m_tot"))));
            p.put("monthPieces", lng(r.get("m_pcs")));
            us.add(p);
        }
        o.put("userSales", us);

        Map<String, Object> cust = db.one("SELECT COUNT(DISTINCT NULLIF(b.customer_phone, '')) AS uniq, COALESCE(SUM(b.customer_phone <> ''), 0) AS withPhone, COUNT(*) AS cnt "
                + "FROM bills b WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + f, args(br, cid, mFrom, mTo));
        Map<String, Object> cu = new LinkedHashMap<>();
        cu.put("unique", lng(cust.get("uniq")));
        cu.put("withPhone", lng(cust.get("withphone")));
        cu.put("bills", lng(cust.get("cnt")));
        o.put("customersMonth", cu);

        List<Map<String, Object>> billRows = db.query("SELECT b.* FROM bills b WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + f
                + " ORDER BY b.id DESC LIMIT " + REPORT_BILL_LIMIT, args(br, cid, dFrom, dTo));
        o.put("bills", billList(cid, billRows));
        o.put("billsTruncated", billRows.size() >= REPORT_BILL_LIMIT);
        return o;
    }

    private List<Object> topItems(String cid, String br, LocalDateTime from, LocalDateTime to, int limit) {
        return rows("SELECT l.item_name AS k, MAX(l.item_name_en) AS en, SUM(l.qty) AS qty, SUM(l.amount) AS amt FROM bill_lines l "
                + "JOIN bills b ON b.company_id = l.company_id AND b.id = l.bill_id "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND b.created_at < ?" + bf(br) + " GROUP BY l.item_name ORDER BY amt DESC LIMIT " + limit, args(br, cid, from, to));
    }

    /** rows of k / en / qty / amt as JSON maps */
    private List<Object> rows(String sql, Object... args) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(sql, args)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", str(r.get("k")));
            if (r.containsKey("en")) p.put("en", str(r.get("en")));
            p.put("qty", lng(r.get("qty")));
            p.put("amt", round2(dbl(r.get("amt"))));
            out.add(p);
        }
        return out;
    }

    // ================================================================ stock (branch-wise, item + size)
    static final long STOCK_LIMIT = 999999;

    /** stock users: own branch; admin: any branch of the opened company */
    private String stockBranch(Ctx c, String cid, String requested) {
        if (!c.admin() && !c.stock() && !c.warehouse()) throw new ApiException(403, "Stock அனுமதி இல்லை (Stock users only)");
        if (c.admin() && !blank(requested)) {
            if (branchRow(cid, requested.trim()) == null) throw new ApiException(404, "கிளை இல்லை (Branch not found)");
            return requested.trim();
        }
        String b = userBranch(c, cid);
        if (b == null) throw new ApiException(400, "கிளை இல்லை (No branch)");
        return b;
    }

    public Map<String, Object> stock(String auth, String branch) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        String br = stockBranch(c, cid, branch);
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT item_id, size_label, qty, updated_at, updated_by FROM stock WHERE company_id = ? AND branch_id = ?", cid, br)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("item", str(r.get("item_id")));
            n.put("size", str(r.get("size_label")));
            n.put("qty", lng(r.get("qty")));
            n.put("at", iso(ldt(r.get("updated_at"))));
            n.put("by", str(r.get("updated_by")));
            list.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> b = branchRow(cid, br);
        o.put("branch", br);
        o.put("branchName", b == null ? "" : str(b.get("name")));
        if (c.admin()) o.put("branches", branchList(cid));
        o.put("stock", list);
        return o;
    }

    /**
     * Stock report: owner / admin see every branch side by side (or one with ?branch=), stock users their own branch.
     * Rows follow the catalog order; qty per branch, total, value (qty x rate). Read only.
     */
    public Map<String, Object> stockReport(String auth, String branch) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        boolean wide = c.admin() || c.owner() || c.warehouse();
        if (!wide && !c.stock()) throw new ApiException(403, "Stock அறிக்கை அனுமதி இல்லை");
        List<Object> all = branchList(cid), shown = new ArrayList<>();
        String br = wide ? (blank(branch) ? null : branch.trim()) : userBranch(c, cid);
        for (Object o : all) if (br == null || br.equals(((Map<?, ?>) o).get("id"))) shown.add(o);
        if (shown.isEmpty()) throw new ApiException(404, "கிளை இல்லை (Branch not found)");
        Map<String, Integer> col = new LinkedHashMap<>();
        for (Object o : shown) col.put(str(((Map<?, ?>) o).get("id")), col.size());
        int nb = col.size();

        Map<String, long[]> qty = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT branch_id, item_id, size_label, qty FROM stock WHERE company_id = ?", cid)) {
            Integer ci = col.get(str(r.get("branch_id")));
            if (ci == null) continue;
            qty.computeIfAbsent(str(r.get("item_id")) + "|" + str(r.get("size_label")), k -> new long[nb])[ci] = lng(r.get("qty"));
        }
        // pieces sold in the last 30 days per item+size and branch (for "days of stock left")
        LocalDateTime since = LocalDate.now(zone).minusDays(30).atStartOfDay();
        Map<String, Long> sold = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT l.item_id, l.size_label, SUM(l.qty) AS q FROM bill_lines l JOIN bills b ON b.company_id = l.company_id AND b.id = l.bill_id "
                + "WHERE b.company_id = ? AND b.created_at >= ? AND l.item_id IS NOT NULL" + bf(br) + " GROUP BY l.item_id, l.size_label", args(br, cid, since)))
            sold.put(str(r.get("item_id")) + "|" + str(r.get("size_label")), lng(r.get("q")));

        Map<String, String[]> groups = new HashMap<>();
        for (Map<String, Object> g : db.query("SELECT id, name_ta, emoji FROM item_groups WHERE company_id = ?", cid))
            groups.put(str(g.get("id")), new String[]{str(g.get("name_ta")), str(g.get("emoji"))});
        long[] bPcs = new long[nb], bNoCost = new long[nb];
        double[] bVal = new double[nb], bCost = new double[nb], bValCosted = new double[nb];
        long[] bNeg = new long[nb], bZero = new long[nb];
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT i.id, i.group_id, i.name_ta, i.name_en, i.sold_by, i.wt_unit, z.size_label, z.rate, z.wrate, z.cost FROM items i "
                + "JOIN item_groups g ON g.company_id = i.company_id AND g.id = i.group_id "
                + "JOIN item_sizes z ON z.company_id = i.company_id AND z.item_id = i.id "
                + "WHERE i.company_id = ? ORDER BY g.sort_order, i.sort_order, z.sort_order", cid)) {
            String k = str(r.get("id")) + "|" + str(r.get("size_label"));
            long[] q = qty.getOrDefault(k, new long[nb]);
            boolean wt = "weight".equals(str(r.get("sold_by")));         // weight item: stock is kept in grams, rate / cost are per kg (or per g)
            int ug = wt ? ("g".equals(str(r.get("wt_unit"))) ? 1 : 1000) : 1;
            double rate = dbl(r.get("rate")), rv = rate / ug;
            Double cost = r.get("cost") == null ? null : dbl(r.get("cost"));
            double cv = cost == null ? 0 : cost / ug;
            long tot = 0, inHand = 0;                 // inHand = positive stock per branch (a branch's minus never lowers another's value)
            for (int i = 0; i < nb; i++) {
                tot += q[i];
                if (q[i] > 0) inHand += q[i];
                if (q[i] > 0) {
                    if (!wt) bPcs[i] += q[i];
                    bVal[i] += q[i] * rv;
                    if (cost != null) { bCost[i] += q[i] * cv; bValCosted[i] += q[i] * rv; }
                    else if (!wt) bNoCost[i] += q[i];
                }
                else if (q[i] < 0) bNeg[i]++;
                else bZero[i]++;
            }
            String[] g = groups.getOrDefault(str(r.get("group_id")), new String[]{"", ""});
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("group", g[0]);
            n.put("emoji", g[1]);
            n.put("item", str(r.get("id")));
            n.put("name", str(r.get("name_ta")));
            n.put("en", str(r.get("name_en")));
            n.put("size", str(r.get("size_label")));
            n.put("rate", round2(rate));
            n.put("wrate", r.get("wrate") == null ? round2(rate) : round2(dbl(r.get("wrate"))));   // wholesale price (blank = retail)
            n.put("cost", cost == null ? null : round2(cost));
            n.put("costValue", cost == null ? null : round2(inHand * cv));
            if (wt) { n.put("by", "weight"); n.put("unit", ug == 1 ? "g" : "kg"); }
            List<Long> ql = new ArrayList<>();
            for (long v : q) ql.add(v);
            n.put("qty", ql);
            n.put("total", tot);
            n.put("value", round2(inHand * rv));
            n.put("sold30", sold.getOrDefault(k, 0L));
            rows.add(n);
        }
        List<Object> tots = new ArrayList<>();
        long pcs = 0, neg = 0, zero = 0, noCost = 0;
        double val = 0, costVal = 0, valCosted = 0;
        int i = 0;
        for (Object o : shown) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", str(((Map<?, ?>) o).get("id")));
            t.put("name", str(((Map<?, ?>) o).get("name")));
            t.put("kind", str(((Map<?, ?>) o).get("kind")));
            t.put("pieces", bPcs[i]);
            t.put("value", round2(bVal[i]));
            t.put("costValue", round2(bCost[i]));
            t.put("margin", round2(bValCosted[i] - bCost[i]));      // on pieces whose cost is known
            t.put("noCostPieces", bNoCost[i]);
            t.put("negative", bNeg[i]);
            t.put("zero", bZero[i]);
            pcs += bPcs[i]; val += bVal[i]; neg += bNeg[i]; zero += bZero[i];
            costVal += bCost[i]; valCosted += bValCosted[i]; noCost += bNoCost[i];
            tots.add(t);
            i++;
        }
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("pieces", pcs);
        sum.put("value", round2(val));
        sum.put("costValue", round2(costVal));
        sum.put("costedValue", round2(valCosted));           // selling value of the pieces that have a cost
        sum.put("margin", round2(valCosted - costVal));
        sum.put("noCostPieces", noCost);
        sum.put("negative", neg);
        sum.put("zero", zero);
        sum.put("lines", rows.size());
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("branch", br);
        o.put("canFilter", wide);
        o.put("canEdit", c.admin() || c.stock() || c.warehouse());
        if (wide) o.put("allBranches", all);
        Map<String, Object> tr = db.one("SELECT COUNT(DISTINCT t.id) AS n, COALESCE(SUM(l.qty), 0) AS q FROM stock_transfers t JOIN transfer_lines l ON l.tr_id = t.id "
                + "WHERE t.company_id = ? AND t.status = 'sent'" + (br == null ? "" : " AND (t.from_branch = ? OR t.to_branch = ?)"), br == null ? new Object[]{cid} : new Object[]{cid, br, br});
        o.put("transitCount", lng(tr.get("n")));
        o.put("transitPieces", lng(tr.get("q")));
        o.put("branches", tots);
        o.put("summary", sum);
        o.put("rows", rows);
        o.put("at", iso(now()));
        return o;
    }

    /** {item, size, qty} sets the stock; {item, size, add} adds / subtracts. Negative stock is allowed. */
    public Map<String, Object> setStock(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        String br = stockBranch(c, cid, str(in.get("branch")));
        String item = str(in.get("item")), size = str(in.get("size"));
        if (db.one("SELECT 1 AS x FROM item_sizes WHERE company_id = ? AND item_id = ? AND size_label = ?", cid, item, size) == null)
            throw new ApiException(404, "ஐட்டம் / சைஸ் இல்லை");
        boolean add = in.containsKey("add") && in.get("add") != null;
        double v = dbl(add ? in.get("add") : in.get("qty"));
        if (Double.isNaN(v) || v != Math.floor(v) || Math.abs(v) > STOCK_LIMIT) throw new ApiException(400, "ஸ்டாக் எண் தவறு (whole number)");
        long q = (long) v;
        String by = str(c.user.get("full_name"));
        if (by.isEmpty()) by = str(c.user.get("username"));
        LocalDateTime now = now();
        if (add)
            db.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE qty = qty + ?, updated_at = ?, updated_by = ?", cid, br, item, size, q, now, by, q, now, by);
        else
            db.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE qty = ?, updated_at = ?, updated_by = ?", cid, br, item, size, q, now, by, q, now, by);
        Map<String, Object> r = db.one("SELECT qty, updated_at, updated_by FROM stock WHERE company_id = ? AND branch_id = ? AND item_id = ? AND size_label = ?", cid, br, item, size);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("item", item);
        n.put("size", size);
        n.put("qty", lng(r.get("qty")));
        n.put("at", iso(ldt(r.get("updated_at"))));
        n.put("by", str(r.get("updated_by")));
        return obj("stock", n);
    }

    // ================================================================ purchase orders (stock users)
    static final Set<String> PO_GST = Set.of("0", "3", "5", "12", "18", "28");

    /** stock users: own branch; admin: any branch (?branch=); owner: read only, any branch */
    private String poBranch(Ctx c, String cid, String requested, boolean write) {
        if (c.owner()) {
            if (write) throw new ApiException(403, "Owner: பார்க்க மட்டும் (view only)");
            return blank(requested) ? null : checkBranch(cid, requested);
        }
        if (!c.admin() && !c.stock() && !c.warehouse()) throw new ApiException(403, "Purchase Order: Stock / Warehouse users மட்டும்");
        // admin / stock / warehouse may assign a PO to ANY branch or warehouse of the company; blank = own (or first) branch
        if (blank(requested)) return write ? (c.admin() ? firstBranch(cid) : userBranch(c, cid)) : null;
        return checkBranch(cid, requested);
    }

    private String checkBranch(String cid, String b) {
        if (branchRow(cid, b.trim()) == null) throw new ApiException(404, "கிளை இல்லை (Branch not found)");
        return b.trim();
    }

    /** every warehouse / stock user of the company a PO may be allocated to (the form filters by the chosen branch). */
    private List<Object> assigneePool(Ctx c, String cid) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT username, full_name, user_type, branch_id FROM users WHERE company_id = ?"
                + " AND active = 1 AND user_type IN ('warehouse','stock') ORDER BY branch_id, user_type = 'stock', full_name", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("user", str(r.get("username")));
            n.put("name", str(r.get("full_name")));
            n.put("type", str(r.get("user_type")));
            n.put("branchId", str(r.get("branch_id")));
            out.add(n);
        }
        return out;
    }

    /** resolve + validate the warehouse / stock user a PO (of branch br) is allocated to; "" = nobody. Returns the user's full name. */
    private String assignName(String cid, String br, String username) {
        if (blank(username)) return "";
        Map<String, Object> au = db.one("SELECT full_name, branch_id FROM users WHERE company_id = ? AND username = ? AND active = 1 AND user_type IN ('warehouse','stock')", cid, username.trim());
        if (au == null) throw new ApiException(400, "Allocate செய்ய தேர்வு செய்த warehouse / stock user தவறு");
        if (!str(au.get("branch_id")).equals(br)) throw new ApiException(400, "அந்த user இந்த PO-வின் கிளையை சேர்ந்தவர் அல்ல (user must belong to the PO branch)");
        return str(au.get("full_name"));
    }

    private static String cell(List<String> row, Integer i) { return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i); }

    /** normalise a PO-CSV header name (English / Tamil aliases) to a canonical key. */
    private static String poCsvKey(String h) {
        String k = (h == null ? "" : h).trim().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "item", "item name", "name", "product", "ஐட்டம்", "பொருள்", "பெயர்" -> "item";
            case "size", "சைஸ்", "அளவு" -> "size";
            case "qty", "quantity", "count", "எண்ணிக்கை" -> "qty";
            case "cost", "unit_cost", "unitcost", "rate", "price", "அடக்கவிலை", "விலை" -> "cost";
            case "gst", "gst%", "gst_pct", "tax" -> "gst";
            case "by", "type", "sold_by", "முறை" -> "by";
            case "unit", "wt_unit", "அலகு" -> "unit";
            default -> k;
        };
    }

    private static LocalDate dateOrNull(Object o, String what) {
        String v = str(o).trim();
        if (v.isEmpty()) return null;
        try { return LocalDate.parse(v); } catch (DateTimeParseException e) { throw new ApiException(400, "தவறான தேதி: " + what); }
    }

    private static double money(Object o, String what, double max) {
        String v = str(o).replace(",", "").trim();
        if (v.isEmpty()) return 0;
        double d = dbl(v);
        if (Double.isNaN(d) || d < 0 || d > max) throw new ApiException(400, "தவறான தொகை: " + what);
        return round2(d);
    }

    public Map<String, Object> suppliers(String auth) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        poBranch(c, cid, null, false);
        return obj("suppliers", supplierList(cid));
    }

    private List<Object> supplierList(String cid) {
        List<Object> out = new ArrayList<>();
        Map<String, Map<String, Object>> st = new HashMap<>();      // per supplier: PO count / value / paid / last order (cancelled POs left out)
        for (Map<String, Object> s : db.query("SELECT supplier_id, COUNT(*) AS n, COALESCE(SUM(total),0) AS t, COALESCE(SUM(paid),0) AS p, MAX(order_date) AS last FROM purchase_orders "
                + "WHERE company_id = ? AND status <> 'cancelled' AND supplier_id IS NOT NULL GROUP BY supplier_id", cid)) st.put(str(s.get("supplier_id")), s);
        Map<String, Long> allPo = new HashMap<>();
        for (Map<String, Object> s : db.query("SELECT supplier_id, COUNT(*) AS n FROM purchase_orders WHERE company_id = ? AND supplier_id IS NOT NULL GROUP BY supplier_id", cid)) allPo.put(str(s.get("supplier_id")), lng(s.get("n")));
        for (Map<String, Object> r : db.query("SELECT * FROM suppliers WHERE company_id = ? ORDER BY name", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", str(r.get("id")));
            n.put("name", str(r.get("name")));
            n.put("phone", str(r.get("phone")));
            n.put("gstin", str(r.get("gstin")));
            n.put("address", str(r.get("address")));
            Map<String, Object> s = st.get(str(r.get("id")));
            n.put("poCount", s == null ? 0 : lng(s.get("n")));
            n.put("total", s == null ? 0.0 : round2(dbl(s.get("t"))));
            n.put("paid", s == null ? 0.0 : round2(dbl(s.get("p"))));
            n.put("balance", s == null ? 0.0 : round2(dbl(s.get("t")) - dbl(s.get("p"))));
            n.put("lastOrder", s == null || s.get("last") == null ? "" : str(s.get("last")).substring(0, 10));
            n.put("canDelete", !allPo.containsKey(str(r.get("id"))));
            out.add(n);
        }
        return out;
    }

    /** add / edit one supplier from the Suppliers screen (stock / admin users; owner is view-only) */
    public Map<String, Object> saveSupplier(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        poBranch(c, cid, null, true);
        String id = str(in.get("id")), name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "Supplier பெயர் தேவை");
        Map<String, Object> dup = db.one("SELECT id FROM suppliers WHERE company_id = ? AND LOWER(name) = LOWER(?)", cid, name);
        if (dup != null && !str(dup.get("id")).equals(id)) throw new ApiException(400, "இந்த பெயரில் Supplier ஏற்கனவே உள்ளார்: " + name);
        String sid = db.tx(t -> upsertSupplier(t, cid, in));
        Map<String, Object> res = obj("suppliers", supplierList(cid));
        res.put("id", sid);
        return res;
    }

    public Map<String, Object> deleteSupplier(String auth, String id) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        poBranch(c, cid, null, true);
        if (db.one("SELECT id FROM suppliers WHERE company_id = ? AND id = ?", cid, id) == null) throw new ApiException(404, "Supplier இல்லை");
        if (db.one("SELECT id FROM purchase_orders WHERE company_id = ? AND supplier_id = ? LIMIT 1", cid, id) != null)
            throw new ApiException(400, "இந்த Supplier-க்கு Purchase Order உள்ளது — நீக்க முடியாது");
        db.update("DELETE FROM suppliers WHERE company_id = ? AND id = ?", cid, id);
        return obj("suppliers", supplierList(cid));
    }

    /** create or update a supplier inside a PO save; returns its id */
    private String upsertSupplier(Db t, String cid, Map<String, Object> s) {
        String id = str(s.get("id")), name = clip(s.get("name"), 60);
        String phone = str(s.get("phone")).replaceAll("[^0-9]", ""), gstin = str(s.get("gstin")).trim().toUpperCase(Locale.ROOT);
        String addr = clip(s.get("address"), 200);
        if (!id.isEmpty() && t.one("SELECT id FROM suppliers WHERE company_id = ? AND id = ?", cid, id) != null && name.isEmpty()) return id;
        if (name.isEmpty()) throw new ApiException(400, "Supplier பெயர் தேவை");
        if (!phone.isEmpty() && !MOBILE.matcher(phone).matches()) throw new ApiException(400, "Supplier மொபைல் எண் தவறு");
        if (!gstin.isEmpty() && !GSTIN.matcher(gstin).matches()) throw new ApiException(400, "Supplier GSTIN தவறு (15 எழுத்து)");
        if (!id.isEmpty() && t.one("SELECT id FROM suppliers WHERE company_id = ? AND id = ?", cid, id) != null) {
            t.update("UPDATE suppliers SET name = ?, phone = ?, gstin = ?, address = ? WHERE company_id = ? AND id = ?", name, phone, gstin, addr, cid, id);
            return id;
        }
        Map<String, Object> dup = t.one("SELECT id FROM suppliers WHERE company_id = ? AND LOWER(name) = LOWER(?)", cid, name);
        if (dup != null) {
            t.update("UPDATE suppliers SET phone = IF(? = '', phone, ?), gstin = IF(? = '', gstin, ?), address = IF(? = '', address, ?) WHERE company_id = ? AND id = ?",
                    phone, phone, gstin, gstin, addr, addr, cid, str(dup.get("id")));
            return str(dup.get("id"));
        }
        String nid = newId("s");
        t.update("INSERT INTO suppliers (company_id, id, name, phone, gstin, address, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", cid, nid, name, phone, gstin, addr, now());
        return nid;
    }

    /** list: ?status=open|received|cancelled|all, ?branch= (admin / owner) */
    public Map<String, Object> poList(String auth, String status, String branch) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        String br = poBranch(c, cid, branch, false);
        String st = blank(status) ? "all" : status.trim();
        String sf = switch (st) {
            case "open" -> " AND p.status IN ('ordered','partial')";
            case "received" -> " AND p.status = 'received'";
            case "cancelled" -> " AND p.status = 'cancelled'";
            default -> "";
        };
        List<Object> out = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        args.add(cid);
        if (br != null) args.add(br);
        for (Map<String, Object> r : db.query("SELECT p.*, br.name AS branch_name, br.kind AS branch_kind, (SELECT COALESCE(SUM(qty),0) FROM po_lines l WHERE l.po_id = p.id AND l.wt_unit IS NULL) AS q, "
                + "(SELECT COALESCE(SUM(received_qty),0) FROM po_lines l WHERE l.po_id = p.id AND l.wt_unit IS NULL) AS rq, "
                + "(SELECT COALESCE(SUM(qty),0) FROM po_lines l WHERE l.po_id = p.id AND l.wt_unit IS NOT NULL) AS wq, "
                + "(SELECT COALESCE(SUM(received_qty),0) FROM po_lines l WHERE l.po_id = p.id AND l.wt_unit IS NOT NULL) AS rwq FROM purchase_orders p "
                + "LEFT JOIN branches br ON br.company_id = p.company_id AND br.id = p.branch_id WHERE p.company_id = ?" + (br == null ? "" : " AND p.branch_id = ?")
                + sf + " ORDER BY p.id DESC LIMIT 300", args.toArray()))
            out.add(poHead(r));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("orders", out);
        o.put("canEdit", !c.owner());
        o.put("branch", br);
        o.put("branches", branchList(cid));            // every branch of the company (shops + warehouses), for the branch filter + PO assignment
        o.put("assignees", assigneePool(c, cid));
        return o;
    }

    private Map<String, Object> poHead(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("po_no")));
        n.put("status", str(r.get("status")));
        n.put("branchId", str(r.get("branch_id")));
        n.put("branch", str(r.get("branch_name")));
        n.put("branchKind", str(r.get("branch_kind")));
        n.put("supplierId", str(r.get("supplier_id")));
        n.put("supplier", str(r.get("supplier_name")));
        n.put("orderDate", str(r.get("order_date")).length() >= 10 ? str(r.get("order_date")).substring(0, 10) : "");
        n.put("expectedDate", r.get("expected_date") == null ? "" : str(r.get("expected_date")).substring(0, 10));
        n.put("supplierRef", str(r.get("supplier_ref")));
        n.put("notes", str(r.get("notes")));
        n.put("subtotal", round2(dbl(r.get("subtotal"))));
        n.put("gst", round2(dbl(r.get("gst_total"))));
        n.put("other", round2(dbl(r.get("other_charges"))));
        n.put("discount", round2(dbl(r.get("discount"))));
        n.put("total", round2(dbl(r.get("total"))));
        n.put("paid", round2(dbl(r.get("paid"))));
        n.put("balance", round2(dbl(r.get("total")) - dbl(r.get("paid"))));
        n.put("by", str(r.get("created_by_name")));
        n.put("at", iso(ldt(r.get("created_at"))));
        n.put("assignedUser", str(r.get("assigned_user")));
        n.put("assignedTo", str(r.get("assigned_name")));
        if (r.get("received_at") != null) { n.put("receivedAt", iso(ldt(r.get("received_at")))); n.put("receivedBy", str(r.get("received_by"))); }
        if (r.containsKey("q")) { n.put("qty", lng(r.get("q"))); n.put("receivedQty", lng(r.get("rq"))); }
        if (r.containsKey("wq")) { n.put("wtG", lng(r.get("wq"))); n.put("receivedWtG", lng(r.get("rwq"))); }   // weight lines: grams
        return n;
    }

    public Map<String, Object> poGet(String auth, long id) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> p = poRow(c, cid, id, false);
        return obj("po", poFull(cid, p, c.seesCost()));
    }

    private Map<String, Object> poRow(Ctx c, String cid, long id, boolean write) {
        poBranch(c, cid, null, write);                 // permission check only (POs are company-wide: any branch may be opened / received)
        Map<String, Object> p = db.one("SELECT p.*, br.name AS branch_name, br.kind AS branch_kind FROM purchase_orders p LEFT JOIN branches br ON br.company_id = p.company_id AND br.id = p.branch_id "
                + "WHERE p.company_id = ? AND p.id = ?", cid, id);
        if (p == null) throw new ApiException(404, "PO இல்லை");
        return p;
    }

    private Map<String, Object> poFull(String cid, Map<String, Object> p, boolean withCost) {
        Map<String, Object> o = poHead(p);
        List<Object> pays = new ArrayList<>();
        for (Map<String, Object> x : db.query("SELECT * FROM po_payments WHERE po_id = ? ORDER BY paid_on, id", lng(p.get("id")))) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("on", str(x.get("paid_on")).substring(0, 10));
            n.put("amount", round2(dbl(x.get("amount"))));
            n.put("mode", str(x.get("mode")));
            n.put("ref", str(x.get("ref")));
            n.put("by", str(x.get("by_name")));
            pays.add(n);
        }
        o.put("payments", pays);
        Map<String, Object> sup = blank(str(p.get("supplier_id"))) ? null : db.one("SELECT * FROM suppliers WHERE company_id = ? AND id = ?", cid, str(p.get("supplier_id")));
        if (sup != null) {
            o.put("supplierPhone", str(sup.get("phone")));
            o.put("supplierGstin", str(sup.get("gstin")));
            o.put("supplierAddress", str(sup.get("address")));
        }
        List<Object> ls = new ArrayList<>();
        long q = 0, rq = 0, wq = 0, rwq = 0;
        for (Map<String, Object> l : db.query("SELECT * FROM po_lines WHERE po_id = ? ORDER BY line_no", lng(p.get("id")))) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("line", lng(l.get("line_no")));
            n.put("itemId", l.get("item_id") == null ? null : str(l.get("item_id")));
            n.put("name", str(l.get("item_name")));
            n.put("size", str(l.get("size_label")));
            n.put("qty", lng(l.get("qty")));                 // weight lines: grams
            n.put("received", lng(l.get("received_qty")));
            n.put("unitCost", round2(dbl(l.get("unit_cost"))));
            n.put("gstPct", round2(dbl(l.get("gst_pct"))));
            n.put("amount", round2(dbl(l.get("amount"))));
            if (l.get("wt_unit") != null) {
                n.put("by", "weight");
                n.put("unit", "g".equals(str(l.get("wt_unit"))) ? "g" : "kg");       // the unit unitCost is quoted in
                wq += lng(l.get("qty"));
                rwq += lng(l.get("received_qty"));
            } else {
                q += lng(l.get("qty"));
                rq += lng(l.get("received_qty"));
            }
            ls.add(n);
        }
        o.put("lines", ls);
        o.put("qty", q);
        o.put("receivedQty", rq);
        o.put("wtG", wq);
        o.put("receivedWtG", rwq);
        return o;
    }

    /** create (no id) or edit (id, only while nothing is received) a purchase order */
    public Map<String, Object> savePo(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        long id = lng(in.get("id"));
        String br = id == 0 ? poBranch(c, cid, str(in.get("branch")), true) : str(poRow(c, cid, id, true).get("branch_id"));
        String assignedUser = str(in.get("assignedUser")).trim();                 // warehouse / stock user allocated to this PO ("" = nobody)
        String assignedName = assignName(cid, br, assignedUser);
        LocalDate orderDate = dateOrNull(in.get("orderDate"), "ஆர்டர் தேதி");
        if (orderDate == null) orderDate = LocalDate.now(zone);
        LocalDate expected = dateOrNull(in.get("expectedDate"), "டெலிவரி தேதி");
        if (expected != null && expected.isBefore(orderDate)) throw new ApiException(400, "டெலிவரி தேதி ஆர்டர் தேதிக்கு முன் இருக்க முடியாது");
        String ref = clip(in.get("supplierRef"), 40), notes = clip(in.get("notes"), 300);
        double other = money(in.get("otherCharges"), "இதர செலவு", 9999999), disc = money(in.get("discount"), "தள்ளுபடி", 9999999),
                paid = money(in.get("paid"), "செலுத்திய தொகை", 99999999);
        List<Object> raw = list(in.get("lines"));
        if (raw.isEmpty()) throw new ApiException(400, "PO-ல் ஐட்டம் இல்லை");
        if (raw.size() > 300) throw new ApiException(400, "அதிக ஐட்டங்கள்");
        Set<String> known = new HashSet<>();
        for (Map<String, Object> z : db.query("SELECT item_id, size_label FROM item_sizes WHERE company_id = ?", cid))
            known.add(str(z.get("item_id")) + "|" + str(z.get("size_label")));
        Map<String, String> wtItems = new HashMap<>();          // weight items of this company: item id -> 'kg' | 'g' (their price / stock unit)
        for (Map<String, Object> i : db.query("SELECT id, wt_unit FROM items WHERE company_id = ? AND sold_by = 'weight'", cid))
            wtItems.put(str(i.get("id")), "g".equals(str(i.get("wt_unit"))) ? "g" : "kg");
        List<Object[]> lines = new ArrayList<>();
        double sub = 0, gst = 0;
        int ln = 1;
        for (Object o : raw) {
            Map<String, Object> l = map(o);
            String name = clip(l.get("name"), 60), size = clip(l.get("size"), 12), iid = str(l.get("itemId"));
            if (iid.isEmpty() || !known.contains(iid + "|" + size)) iid = null;
            boolean wantW = "weight".equals(str(l.get("by")));
            String wu = null;                                    // null = counted in pieces
            if (iid != null && wtItems.containsKey(iid)) {
                if (!wantW) throw new ApiException(400, "எடை ஐட்டம் — எடையில் (kg / g) உள்ளிடவும்: " + name);
                wu = wtItems.get(iid);
            } else if (wantW) {
                if (iid != null) throw new ApiException(400, "இது எண்ணிக்கை ஐட்டம்: " + name);
                wu = "g".equals(str(l.get("unit"))) ? "g" : "kg";
                size = "";
            }
            double q = dbl(l.get("qty"));                         // pieces, or GRAMS for a weight line
            if (name.isEmpty()) throw new ApiException(400, "ஐட்டம் பெயர் தேவை (வரி " + ln + ")");
            if ((wu == null ? !(q >= 1 && q <= 99999) : !(q >= 1 && q <= 9999999)) || q != Math.floor(q))
                throw new ApiException(400, (wu == null ? "அளவு தவறு: " : "எடை தவறு: ") + name);
            double uc = money(l.get("unitCost"), "அடக்க விலை (" + name + ")", 999999);
            String g = str(l.get("gstPct")).replaceAll("\\.0+$", "").trim();
            if (g.isEmpty()) g = "0";
            if (!PO_GST.contains(g)) throw new ApiException(400, "GST %: 0 / 3 / 5 / 12 / 18 / 28");
            double amt = round2(wu == null ? uc * q : uc * q / ("kg".equals(wu) ? 1000.0 : 1.0)), gp = Double.parseDouble(g);
            sub += amt;
            gst += amt * gp / 100;
            lines.add(new Object[]{ln++, iid, name, size, (long) q, BigDecimal.valueOf(uc), BigDecimal.valueOf(gp), BigDecimal.valueOf(amt), wu});
        }
        double subtotal = round2(sub), gstTotal = round2(gst), total = round2(subtotal + gstTotal + other - disc);
        if (total < 0) throw new ApiException(400, "தள்ளுபடி மொத்தத்தை விட அதிகம்");
        LocalDateTime now = now();
        final LocalDate od = orderDate;
        long poId = db.tx(t -> {
            Object sraw = in.get("supplier");
            String sid = sraw instanceof Map ? upsertSupplier(t, cid, map(sraw)) : null;
            if (sid == null) throw new ApiException(400, "Supplier-ஐ தேர்வு செய்யவும் / உருவாக்கவும்");
            String sname = str(t.one("SELECT name FROM suppliers WHERE company_id = ? AND id = ?", cid, sid).get("name"));
            long pid = id;
            if (pid == 0) {
                Map<String, Object> co = t.one("SELECT po_seq FROM companies WHERE id = ? FOR UPDATE", cid);
                long seq = lng(co.get("po_seq")) + 1;
                t.update("UPDATE companies SET po_seq = ? WHERE id = ?", seq, cid);
                pid = t.insert("INSERT INTO purchase_orders (company_id, branch_id, po_seq, po_no, supplier_id, supplier_name, order_date, expected_date, supplier_ref, notes, status, "
                        + "subtotal, gst_total, other_charges, discount, total, paid, created_at, created_by_user, created_by_name, assigned_user, assigned_name, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ordered', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        cid, br, seq, "PO-" + String.format("%04d", seq), sid, sname, od, expected, ref, notes,
                        BigDecimal.valueOf(subtotal), BigDecimal.valueOf(gstTotal), BigDecimal.valueOf(other), BigDecimal.valueOf(disc), BigDecimal.valueOf(total),
                        BigDecimal.valueOf(paid), now, str(c.user.get("username")), str(c.user.get("full_name")), assignedUser, assignedName, now);
            } else {
                Map<String, Object> cur = t.one("SELECT status, (SELECT COALESCE(SUM(received_qty),0) FROM po_lines WHERE po_id = p.id) AS rq FROM purchase_orders p WHERE id = ? FOR UPDATE", pid);
                if (!"ordered".equals(str(cur.get("status"))) || lng(cur.get("rq")) > 0)
                    throw new ApiException(409, "சரக்கு வந்த / ரத்தான PO-ஐ மாற்ற முடியாது");
                t.update("UPDATE purchase_orders SET supplier_id = ?, supplier_name = ?, order_date = ?, expected_date = ?, supplier_ref = ?, notes = ?, subtotal = ?, gst_total = ?, "
                        + "other_charges = ?, discount = ?, total = ?, paid = ?, assigned_user = ?, assigned_name = ?, updated_at = ? WHERE id = ?", sid, sname, od, expected, ref, notes,
                        BigDecimal.valueOf(subtotal), BigDecimal.valueOf(gstTotal), BigDecimal.valueOf(other), BigDecimal.valueOf(disc), BigDecimal.valueOf(total),
                        BigDecimal.valueOf(paid), assignedUser, assignedName, now, pid);
                t.update("DELETE FROM po_lines WHERE po_id = ?", pid);
            }
            List<Object[]> rows = new ArrayList<>();
            for (Object[] l : lines) rows.add(concat(new Object[]{pid, cid}, l));
            t.batch("INSERT INTO po_lines (po_id, company_id, line_no, item_id, item_name, size_label, qty, unit_cost, gst_pct, amount, wt_unit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
            return pid;
        });
        syncPoLedger(cid, poId, str(c.user.get("full_name")));
        return poGet(auth, poId);
    }

    /**
     * Bulk-create ONE purchase order from a CSV of item lines, then delegate to {@link #savePo} for all validation
     * and ledger work. The header carries the order's branch / supplier / assignee / dates / charges (same body as
     * savePo); the CSV rows carry only the lines. CSV columns (header row, any order, comma / tab / semicolon):
     * item (required), size, qty (required), cost, gst, by (count|weight), unit (kg|g). Item names that match a
     * catalog item (+ size) are linked by id so receiving updates catalog stock; unknown names stay as free text.
     */
    public Map<String, Object> importPo(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        poBranch(c, cid, str(in.get("branch")), true);                 // permission / branch check up front (clear error before parsing)
        String csv = str(in.get("csv"));
        if (csv.isBlank()) throw new ApiException(400, "CSV காலியாக உள்ளது");
        List<List<String>> table = CatalogImport.parseCsv(csv);
        if (table.size() < 2) throw new ApiException(400, "CSV-ல் header வரி + குறைந்தது ஒரு ஐட்டம் வரி தேவை");
        Map<String, Integer> col = new HashMap<>();
        List<String> head = table.get(0);
        for (int i = 0; i < head.size(); i++) col.putIfAbsent(poCsvKey(head.get(i)), i);
        if (!col.containsKey("item") || !col.containsKey("qty"))
            throw new ApiException(400, "CSV-ல் 'item' மற்றும் 'qty' நெடுவரிசை (header) தேவை. Sample CSV-ஐ பதிவிறக்கவும்");
        if (table.size() - 1 > 300) throw new ApiException(400, "ஒரு PO-ல் அதிகபட்சம் 300 வரிகள்");

        Map<String, String> nameToId = new HashMap<>();                // lower(name_ta | name_en) -> item id, to link CSV names to the catalog
        for (Map<String, Object> it : db.query("SELECT id, name_ta, name_en FROM items WHERE company_id = ?", cid)) {
            String iid = str(it.get("id"));
            nameToId.putIfAbsent(str(it.get("name_ta")).toLowerCase(Locale.ROOT), iid);
            String en = str(it.get("name_en"));
            if (!en.isBlank()) nameToId.putIfAbsent(en.toLowerCase(Locale.ROOT), iid);
        }
        Set<String> sizeKnown = new HashSet<>();
        for (Map<String, Object> z : db.query("SELECT item_id, size_label FROM item_sizes WHERE company_id = ?", cid))
            sizeKnown.add(str(z.get("item_id")) + "|" + str(z.get("size_label")));

        List<Object> lines = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            String name = cell(row, col.get("item")).trim();
            if (name.isEmpty()) continue;                              // skip blank rows
            boolean wt = col.containsKey("by") && cell(row, col.get("by")).trim().toLowerCase(Locale.ROOT).startsWith("w");
            String size = wt ? "" : cell(row, col.get("size")).trim();
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("name", name);
            l.put("size", size);
            l.put("qty", cell(row, col.get("qty")).trim());
            l.put("unitCost", cell(row, col.get("cost")).trim());
            l.put("gstPct", cell(row, col.get("gst")).trim());
            l.put("by", wt ? "weight" : "count");
            if (wt) l.put("unit", cell(row, col.get("unit")).trim().toLowerCase(Locale.ROOT).startsWith("g") ? "g" : "kg");
            String iid = nameToId.get(name.toLowerCase(Locale.ROOT));
            if (iid != null && (wt || sizeKnown.contains(iid + "|" + size))) l.put("itemId", iid);
            lines.add(l);
        }
        if (lines.isEmpty()) throw new ApiException(400, "CSV-ல் செல்லுபடியாகும் ஐட்டம் வரி இல்லை");

        Map<String, Object> po = new LinkedHashMap<>(in);              // carry branch / supplier / assignedUser / dates / charges / notes
        po.remove("csv");
        po.remove("id");                                              // import always creates a new PO
        po.put("lines", lines);
        return savePo(auth, po);
    }

    /** goods received: {lines:[{line, qty}], updateCost} - adds to stock of the PO's branch; partial receipts allowed */
    public Map<String, Object> receivePo(String auth, long id, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> p0 = poRow(c, cid, id, true);
        String br = str(p0.get("branch_id"));
        boolean updateCost = !(in.get("updateCost") instanceof Boolean) || (Boolean) in.get("updateCost");
        String by = str(c.user.get("full_name"));
        db.tx(t -> {
            Map<String, Object> p = t.one("SELECT * FROM purchase_orders WHERE id = ? FOR UPDATE", id);
            String st = str(p.get("status"));
            if (!st.equals("ordered") && !st.equals("partial")) throw new ApiException(409, "இந்த PO-க்கு சரக்கு பெற முடியாது (" + st + ")");
            Map<Long, Map<String, Object>> lines = new HashMap<>();
            for (Map<String, Object> l : t.query("SELECT * FROM po_lines WHERE po_id = ?", id)) lines.put(lng(l.get("line_no")), l);
            LocalDateTime now = now();
            String pono = str(p.get("po_no"));
            boolean any = false, costChanged = false;
            for (Object o : list(in.get("lines"))) {
                Map<String, Object> r = map(o);
                Map<String, Object> l = lines.get(lng(r.get("line")));
                if (l == null) throw new ApiException(400, "PO வரி இல்லை");
                double q = dbl(r.get("qty"));
                if (Double.isNaN(q) || q != Math.floor(q) || q < 0) throw new ApiException(400, "வந்த அளவு தவறு: " + str(l.get("item_name")));
                long left = lng(l.get("qty")) - lng(l.get("received_qty"));
                if (q > left) throw new ApiException(400, str(l.get("item_name")) + ": ஆர்டர் செய்ததை விட அதிகம் (" + left + " மீதம்)");
                if (q == 0) continue;
                any = true;
                t.update("UPDATE po_lines SET received_qty = received_qty + ? WHERE po_id = ? AND line_no = ?", (long) q, id, lng(l.get("line_no")));
                String iid = l.get("item_id") == null ? null : str(l.get("item_id"));
                if (iid != null && t.one("SELECT 1 AS x FROM item_sizes WHERE company_id = ? AND item_id = ? AND size_label = ?", cid, iid, str(l.get("size_label"))) != null) {
                    t.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE qty = qty + ?, updated_at = ?, updated_by = ?",
                            cid, br, iid, str(l.get("size_label")), (long) q, now, pono, (long) q, now, pono + " · " + by);
                    if (updateCost && dbl(l.get("unit_cost")) > 0) {
                        t.update("UPDATE item_sizes SET cost = ? WHERE company_id = ? AND item_id = ? AND size_label = ?", l.get("unit_cost"), cid, iid, str(l.get("size_label")));
                        costChanged = true;
                    }
                }
            }
            if (!any) throw new ApiException(400, "வந்த அளவை உள்ளிடவும்");
            Map<String, Object> left = t.one("SELECT COALESCE(SUM(qty - received_qty),0) AS l FROM po_lines WHERE po_id = ?", id);
            t.update("UPDATE purchase_orders SET status = ?, received_at = ?, received_by = ?, updated_at = ? WHERE id = ?",
                    lng(left.get("l")) == 0 ? "received" : "partial", now, by, now, id);
            if (costChanged) t.update("UPDATE companies SET catalog_version = catalog_version + 1 WHERE id = ?", cid);
            return null;
        });
        return poGet(auth, id);
    }

    /** {paid} or {cancel:true} */
    public Map<String, Object> updatePo(String auth, long id, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> p = poRow(c, cid, id, true);
        if (Boolean.TRUE.equals(in.get("cancel"))) {
            Map<String, Object> rq = db.one("SELECT COALESCE(SUM(received_qty),0) AS r FROM po_lines WHERE po_id = ?", id);
            if (lng(rq.get("r")) > 0) throw new ApiException(409, "சரக்கு வந்த PO-ஐ ரத்து செய்ய முடியாது");
            if (!"ordered".equals(str(p.get("status")))) throw new ApiException(409, "ரத்து செய்ய முடியாது");
            db.update("UPDATE purchase_orders SET status = 'cancelled', updated_at = ? WHERE id = ?", now(), id);
        }
        if (in.containsKey("paid")) {
            double paid = money(in.get("paid"), "செலுத்திய தொகை", 99999999);
            db.update("UPDATE purchase_orders SET paid = ?, updated_at = ? WHERE id = ?", BigDecimal.valueOf(paid), now(), id);
            syncPoLedger(cid, id, str(c.user.get("full_name")));
        }
        return poGet(auth, id);
    }

    /** keeps po_payments equal to purchase_orders.paid when "paid" was typed as a total (PO form): the difference is one row today */
    private void syncPoLedger(String cid, long poId, String by) {
        Map<String, Object> r = db.one("SELECT p.paid, (SELECT COALESCE(SUM(amount), 0) FROM po_payments x WHERE x.po_id = p.id) AS led FROM purchase_orders p WHERE p.id = ?", poId);
        double diff = round2(dbl(r.get("paid")) - dbl(r.get("led")));
        if (Math.abs(diff) >= 0.005)
            db.update("INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) VALUES (?, ?, ?, ?, 'Cash', ?, ?, ?)",
                    cid, poId, BigDecimal.valueOf(diff), LocalDate.now(zone), diff > 0 ? "PO form" : "திருத்தம் (correction)", now(), by);
    }

    /** payment to the supplier for one PO: {amount, date, mode, ref} - not more than the balance */
    public Map<String, Object> poPayment(String auth, long id, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> p = poRow(c, cid, id, true);
        if ("cancelled".equals(str(p.get("status")))) throw new ApiException(409, "ரத்தான PO");
        double amt = round2(dbl(in.get("amount")));
        if (Double.isNaN(amt) || amt <= 0) throw new ApiException(400, "தொகை தவறு");
        double bal = round2(dbl(p.get("total")) - dbl(p.get("paid")));
        if (amt > bal + 0.001) throw new ApiException(400, "பாக்கி " + bal + " மட்டுமே — அதிகமாக பதிவு செய்ய முடியாது");
        LocalDate on = LocalDate.now(zone);
        if (!blank(str(in.get("date")))) {
            try { on = LocalDate.parse(str(in.get("date"))); } catch (DateTimeParseException e) { throw new ApiException(400, "தேதி தவறு"); }
            if (on.isAfter(LocalDate.now(zone))) throw new ApiException(400, "எதிர்கால தேதி வேண்டாம்");
        }
        final LocalDate payOn = on;
        String by = str(c.user.get("full_name"));
        db.tx(t -> {
            t.update("UPDATE purchase_orders SET paid = paid + ?, updated_at = ? WHERE id = ?", BigDecimal.valueOf(amt), now(), id);
            t.update("INSERT INTO po_payments (company_id, po_id, amount, paid_on, mode, ref, created_at, by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    cid, id, BigDecimal.valueOf(amt), payOn, blank(str(in.get("mode"))) ? "Cash" : clip(in.get("mode"), 30), clip(in.get("ref"), 60), now(), by);
            return null;
        });
        return poGet(auth, id);
    }

    /**
     * Payables: what is still to be paid to suppliers - pending POs (balance > 0), per supplier, and every payment by date.
     * stock / warehouse: own branch; admin / owner: all (?branch=).
     */
    public Map<String, Object> payables(String auth, String branch) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        String br = poBranch(c, cid, branch, false);
        String bf = br == null ? "" : " AND p.branch_id = ?";
        Object[] a = br == null ? new Object[]{cid} : new Object[]{cid, br};
        List<Object> pend = new ArrayList<>();
        Map<String, double[]> sup = new LinkedHashMap<>();
        double due = 0, billed = 0, paid = 0;
        for (Map<String, Object> r : db.query("SELECT p.*, br.name AS branch_name FROM purchase_orders p LEFT JOIN branches br ON br.company_id = p.company_id AND br.id = p.branch_id "
                + "WHERE p.company_id = ? AND p.status <> 'cancelled'" + bf + " ORDER BY p.order_date, p.id", a)) {
            double t = dbl(r.get("total")), pd = dbl(r.get("paid")), b = round2(t - pd);
            billed += t; paid += pd;
            double[] s2 = sup.computeIfAbsent(str(r.get("supplier_name")), k -> new double[4]);
            s2[0] += t; s2[1] += pd;
            if (b >= 0.005) {
                due += b; s2[2] += b; s2[3]++;
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", lng(r.get("id")));
                n.put("no", str(r.get("po_no")));
                n.put("supplier", str(r.get("supplier_name")));
                n.put("date", str(r.get("order_date")).substring(0, 10));
                n.put("branch", str(r.get("branch_name")));
                n.put("status", str(r.get("status")));
                n.put("total", round2(t));
                n.put("paid", round2(pd));
                n.put("balance", b);
                pend.add(n);
            }
        }
        List<Object> sups = new ArrayList<>();
        for (Map.Entry<String, double[]> e : sup.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("name", e.getKey());
            n.put("total", round2(e.getValue()[0]));
            n.put("paid", round2(e.getValue()[1]));
            n.put("balance", round2(e.getValue()[2]));
            n.put("pendingPos", (long) e.getValue()[3]);
            sups.add(n);
        }
        sups.sort((x, y) -> Double.compare(dbl(map(y).get("balance")), dbl(map(x).get("balance"))));
        List<Object> pays = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT x.*, p.po_no, p.supplier_name FROM po_payments x JOIN purchase_orders p ON p.id = x.po_id "
                + "WHERE p.company_id = ?" + bf + " ORDER BY x.paid_on DESC, x.id DESC LIMIT 300", a)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("on", str(r.get("paid_on")).substring(0, 10));
            n.put("no", str(r.get("po_no")));
            n.put("poId", lng(r.get("po_id")));
            n.put("supplier", str(r.get("supplier_name")));
            n.put("amount", round2(dbl(r.get("amount"))));
            n.put("mode", str(r.get("mode")));
            n.put("ref", str(r.get("ref")));
            n.put("by", str(r.get("by_name")));
            pays.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("pendingCount", pend.size());
        o.put("due", round2(due));
        o.put("total", round2(billed));
        o.put("paid", round2(paid));
        o.put("pending", pend);
        o.put("suppliers", sups);
        o.put("payments", pays);
        return o;
    }

    /** Receivables: retailer invoices still to be paid, per retailer, and every payment received by date */
    public Map<String, Object> receivables(String auth) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        requireWholesale(c);
        LocalDate today = LocalDate.now(zone);
        List<Object> pend = new ArrayList<>();
        double due = 0, over = 0, billed = 0, paid = 0;
        for (Map<String, Object> r : db.query("SELECT b.*, p.name AS party_name FROM bills b JOIN parties p ON p.company_id = b.company_id AND p.id = b.party_id "
                + "WHERE b.company_id = ? AND b.bill_kind = 'invoice' ORDER BY b.created_at, b.id", cid)) {
            Map<String, Object> n = invoiceRow(r, today);
            billed += (Double) n.get("total"); paid += (Double) n.get("paid");
            if ((Double) n.get("balance") >= 0.005) {
                n.put("party", str(r.get("party_name")));
                n.put("partyId", str(r.get("party_id")));
                due += (Double) n.get("balance");
                if (Boolean.TRUE.equals(n.get("overdue"))) over += (Double) n.get("balance");
                pend.add(n);
            }
        }
        List<Object> pays = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT x.*, b.bill_no, p.name AS party_name FROM party_payments x LEFT JOIN bills b ON b.company_id = x.company_id AND b.id = x.bill_id "
                + "JOIN parties p ON p.company_id = x.company_id AND p.id = x.party_id WHERE x.company_id = ? ORDER BY x.paid_on DESC, x.id DESC LIMIT 300", cid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("on", str(r.get("paid_on")).substring(0, 10));
            n.put("no", str(r.get("bill_no")));
            n.put("party", str(r.get("party_name")));
            n.put("partyId", str(r.get("party_id")));
            n.put("amount", round2(dbl(r.get("amount"))));
            n.put("mode", str(r.get("mode")));
            n.put("ref", str(r.get("ref")));
            n.put("by", str(r.get("by_name")));
            pays.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("pendingCount", pend.size());
        o.put("due", round2(due));
        o.put("overdue", round2(over));
        o.put("total", round2(billed));
        o.put("paid", round2(paid));
        o.put("pending", pend);
        o.put("payments", pays);
        return o;
    }

    // ================================================================ stock transfers (warehouse -> shops)
    /** who may send: warehouse users (from their warehouse) and admin (from any location) */
    private String trFrom(Ctx c, String cid, String requested) {
        if (c.warehouse()) return userBranch(c, cid);
        if (c.admin()) {
            if (blank(requested)) throw new ApiException(400, "எங்கிருந்து அனுப்ப வேண்டும் (From)?");
            return checkBranch(cid, requested);
        }
        throw new ApiException(403, "சரக்கு அனுப்ப Warehouse users மட்டும்");
    }

    /** list: warehouse users see what they sent, stock users what comes to their shop, admin / owner everything */
    public Map<String, Object> transfers(String auth, String status) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        if (c.sales()) throw new ApiException(403, "அனுமதி இல்லை");
        String own = c.admin() || c.owner() ? null : userBranch(c, cid);
        String st = blank(status) ? "all" : status.trim();
        String sf = switch (st) { case "sent" -> " AND t.status = 'sent'"; case "received" -> " AND t.status = 'received'"; case "cancelled" -> " AND t.status = 'cancelled'"; default -> ""; };
        List<Object> args = new ArrayList<>();
        args.add(cid);
        String wf = "";
        if (own != null) { wf = c.warehouse() ? " AND t.from_branch = ?" : " AND t.to_branch = ?"; args.add(own); }
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT t.*, f.name AS from_name, f.kind AS from_kind, d.name AS to_name, "
                + "(SELECT COALESCE(SUM(qty),0) FROM transfer_lines l WHERE l.tr_id = t.id) AS q, (SELECT COALESCE(SUM(received_qty),0) FROM transfer_lines l WHERE l.tr_id = t.id) AS rq "
                + "FROM stock_transfers t LEFT JOIN branches f ON f.company_id = t.company_id AND f.id = t.from_branch LEFT JOIN branches d ON d.company_id = t.company_id AND d.id = t.to_branch "
                + "WHERE t.company_id = ?" + wf + sf + " ORDER BY t.id DESC LIMIT 300", args.toArray()))
            out.add(trHead(r));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("transfers", out);
        o.put("canSend", c.warehouse() || c.admin());
        o.put("shops", branchList(cid, "shop"));
        if (c.admin()) o.put("locations", branchList(cid));
        return o;
    }

    private Map<String, Object> trHead(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("tr_no")));
        n.put("status", str(r.get("status")));
        n.put("fromId", str(r.get("from_branch")));
        n.put("from", str(r.get("from_name")));
        n.put("fromKind", str(r.get("from_kind")));
        n.put("toId", str(r.get("to_branch")));
        n.put("to", str(r.get("to_name")));
        n.put("notes", str(r.get("notes")));
        n.put("by", str(r.get("created_by_name")));
        n.put("at", iso(ldt(r.get("created_at"))));
        if (r.get("received_at") != null) { n.put("receivedAt", iso(ldt(r.get("received_at")))); n.put("receivedBy", str(r.get("received_by"))); }
        if (r.containsKey("q")) { n.put("qty", lng(r.get("q"))); n.put("receivedQty", lng(r.get("rq"))); }
        return n;
    }

    private Map<String, Object> trRow(Ctx c, String cid, long id) {
        Map<String, Object> t = db.one("SELECT t.*, f.name AS from_name, f.kind AS from_kind, d.name AS to_name FROM stock_transfers t "
                + "LEFT JOIN branches f ON f.company_id = t.company_id AND f.id = t.from_branch LEFT JOIN branches d ON d.company_id = t.company_id AND d.id = t.to_branch "
                + "WHERE t.company_id = ? AND t.id = ?", cid, id);
        if (t == null) throw new ApiException(404, "Transfer இல்லை");
        if (c.sales()) throw new ApiException(403, "அனுமதி இல்லை");
        if (!c.admin() && !c.owner()) {
            String own = userBranch(c, cid);
            if (!own.equals(str(t.get("from_branch"))) && !own.equals(str(t.get("to_branch")))) throw new ApiException(403, "வேறு இடத்தின் transfer");
        }
        return t;
    }

    public Map<String, Object> transferGet(String auth, long id) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> t = trRow(c, cid, id);
        Map<String, Object> o = trHead(t);
        List<Object> ls = new ArrayList<>();
        long q = 0, rq = 0;
        for (Map<String, Object> l : db.query("SELECT * FROM transfer_lines WHERE tr_id = ? ORDER BY line_no", id)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("line", lng(l.get("line_no")));
            n.put("itemId", str(l.get("item_id")));
            n.put("name", str(l.get("item_name")));
            n.put("size", str(l.get("size_label")));
            n.put("qty", lng(l.get("qty")));
            n.put("received", lng(l.get("received_qty")));
            q += lng(l.get("qty")); rq += lng(l.get("received_qty"));
            ls.add(n);
        }
        o.put("lines", ls);
        o.put("qty", q);
        o.put("receivedQty", rq);
        String own = c.admin() || c.owner() ? null : userBranch(c, cid);
        boolean sent = "sent".equals(str(t.get("status")));
        o.put("canReceive", sent && (c.admin() || (c.stock() && str(t.get("to_branch")).equals(own))));
        o.put("canCancel", sent && (c.admin() || (c.warehouse() && str(t.get("from_branch")).equals(own))));
        return obj("transfer", o);
    }

    /** send stock: {from (admin), to, notes, lines:[{itemId, size, qty}]} - leaves the source at once (in transit) */
    public Map<String, Object> sendTransfer(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        String from = trFrom(c, cid, str(in.get("from")));
        String to = checkBranch(cid, str(in.get("to")));
        if (to.equals(from)) throw new ApiException(400, "அனுப்பும் இடமும் பெறும் இடமும் ஒன்றாக இருக்க முடியாது");
        if (isWarehouse(cid, to)) throw new ApiException(400, "கடை கிளைக்கு மட்டுமே அனுப்பலாம்");
        String notes = clip(in.get("notes"), 200);
        Map<String, String[]> names = new HashMap<>();
        for (Map<String, Object> z : db.query("SELECT z.item_id, z.size_label, i.name_ta FROM item_sizes z JOIN items i ON i.company_id = z.company_id AND i.id = z.item_id WHERE z.company_id = ?", cid))
            names.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), new String[]{str(z.get("name_ta"))});
        Map<String, long[]> want = new LinkedHashMap<>();
        for (Object o : list(in.get("lines"))) {
            Map<String, Object> l = map(o);
            String k = str(l.get("itemId")) + "|" + str(l.get("size"));
            if (!names.containsKey(k)) throw new ApiException(400, "ஐட்டம் / சைஸ் இல்லை");
            double q = dbl(l.get("qty"));
            if (!(q >= 1 && q <= 99999) || q != Math.floor(q)) throw new ApiException(400, "அளவு தவறு: " + names.get(k)[0]);
            want.computeIfAbsent(k, x -> new long[1])[0] += (long) q;
        }
        if (want.isEmpty()) throw new ApiException(400, "அனுப்ப ஐட்டம் இல்லை");
        if (want.size() > 300) throw new ApiException(400, "அதிக ஐட்டங்கள்");
        String by = str(c.user.get("full_name"));
        long tid = db.tx(t -> {
            // enough stock at the source? (locked, so two senders can't both take the last pieces)
            for (Map.Entry<String, long[]> e : want.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                Map<String, Object> r = t.one("SELECT qty FROM stock WHERE company_id = ? AND branch_id = ? AND item_id = ? AND size_label = ? FOR UPDATE", cid, from, k[0], k[1]);
                long have = r == null ? 0 : lng(r.get("qty"));
                if (have < e.getValue()[0])
                    throw new ApiException(409, names.get(e.getKey())[0] + " (" + k[1] + "): இருப்பு " + have + " மட்டும் — " + e.getValue()[0] + " அனுப்ப முடியாது");
            }
            Map<String, Object> co = t.one("SELECT tr_seq FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq = lng(co.get("tr_seq")) + 1;
            t.update("UPDATE companies SET tr_seq = ? WHERE id = ?", seq, cid);
            LocalDateTime now = now();
            String no = "TR-" + String.format("%04d", seq);
            long id = t.insert("INSERT INTO stock_transfers (company_id, tr_seq, tr_no, from_branch, to_branch, status, notes, created_at, created_by_user, created_by_name) "
                    + "VALUES (?, ?, ?, ?, ?, 'sent', ?, ?, ?, ?)", cid, seq, no, from, to, notes, now, str(c.user.get("username")), by);
            List<Object[]> rows = new ArrayList<>();
            int ln = 1;
            for (Map.Entry<String, long[]> e : want.entrySet()) {
                String[] k = e.getKey().split("\\|", 2);
                rows.add(new Object[]{id, cid, ln++, k[0], names.get(e.getKey())[0], k[1], e.getValue()[0]});
                t.update("UPDATE stock SET qty = qty - ?, updated_at = ?, updated_by = ? WHERE company_id = ? AND branch_id = ? AND item_id = ? AND size_label = ?",
                        e.getValue()[0], now, no + " · " + by, cid, from, k[0], k[1]);
            }
            t.batch("INSERT INTO transfer_lines (tr_id, company_id, line_no, item_id, item_name, size_label, qty) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
            return id;
        });
        return transferGet(auth, tid);
    }

    /** the shop confirms what arrived: {lines:[{line, qty}]} (less than sent = shortage, recorded) */
    public Map<String, Object> receiveTransfer(String auth, long id, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> t0 = trRow(c, cid, id);
        String to = str(t0.get("to_branch"));
        if (!c.admin() && !(c.stock() && to.equals(userBranch(c, cid)))) throw new ApiException(403, "பெறும் கிளையின் Stock user மட்டும்");
        Map<Long, Long> got = new HashMap<>();
        for (Object o : list(in.get("lines"))) {
            Map<String, Object> r = map(o);
            double q = dbl(r.get("qty"));
            if (Double.isNaN(q) || q < 0 || q != Math.floor(q)) throw new ApiException(400, "வந்த அளவு தவறு");
            got.put(lng(r.get("line")), (long) q);
        }
        String by = str(c.user.get("full_name"));
        db.tx(t -> {
            Map<String, Object> tr = t.one("SELECT * FROM stock_transfers WHERE id = ? FOR UPDATE", id);
            if (!"sent".equals(str(tr.get("status")))) throw new ApiException(409, "ஏற்கனவே பெறப்பட்டது / ரத்து");
            LocalDateTime now = now();
            String no = str(tr.get("tr_no"));
            for (Map<String, Object> l : t.query("SELECT * FROM transfer_lines WHERE tr_id = ?", id)) {
                long sent = lng(l.get("qty"));
                long q = got.getOrDefault(lng(l.get("line_no")), sent);           // not listed = all arrived
                if (q > sent) throw new ApiException(400, str(l.get("item_name")) + ": அனுப்பியதை (" + sent + ") விட அதிகம்");
                t.update("UPDATE transfer_lines SET received_qty = ? WHERE tr_id = ? AND line_no = ?", q, id, lng(l.get("line_no")));
                if (q > 0)
                    t.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE qty = qty + ?, updated_at = ?, updated_by = ?",
                            cid, to, str(l.get("item_id")), str(l.get("size_label")), q, now, no + " · " + by, q, now, no + " · " + by);
            }
            t.update("UPDATE stock_transfers SET status = 'received', received_at = ?, received_by = ? WHERE id = ?", now, by, id);
            return null;
        });
        return transferGet(auth, id);
    }

    /** sender / admin, before it is received: the pieces go back to the source */
    public Map<String, Object> cancelTransfer(String auth, long id) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        Map<String, Object> t0 = trRow(c, cid, id);
        String from = str(t0.get("from_branch"));
        if (!c.admin() && !(c.warehouse() && from.equals(userBranch(c, cid)))) throw new ApiException(403, "அனுப்பியவர் மட்டும் ரத்து செய்யலாம்");
        String by = str(c.user.get("full_name"));
        db.tx(t -> {
            Map<String, Object> tr = t.one("SELECT * FROM stock_transfers WHERE id = ? FOR UPDATE", id);
            if (!"sent".equals(str(tr.get("status")))) throw new ApiException(409, "பெறப்பட்ட transfer-ஐ ரத்து செய்ய முடியாது");
            LocalDateTime now = now();
            for (Map<String, Object> l : t.query("SELECT * FROM transfer_lines WHERE tr_id = ?", id))
                t.update("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = qty + ?, updated_at = ?, updated_by = ?", cid, from, str(l.get("item_id")), str(l.get("size_label")),
                        lng(l.get("qty")), now, "ரத்து " + str(tr.get("tr_no")), lng(l.get("qty")), now, "ரத்து " + str(tr.get("tr_no")) + " · " + by);
            t.update("UPDATE stock_transfers SET status = 'cancelled', received_at = ?, received_by = ? WHERE id = ?", now, by, id);
            return null;
        });
        return transferGet(auth, id);
    }

    // ================================================================ branches (admin)
    public Map<String, Object> saveBranch(String auth, Map<String, Object> in) {
        Ctx c0 = admin(auth);
        String cid = str(in.get("companyId")), id = str(in.get("id"));
        ownCompany(c0, cid);
        if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null) throw new ApiException(404, "கம்பெனி இல்லை");
        String name = clip(in.get("name"), 40);
        if (name.isEmpty()) throw new ApiException(400, "கிளை பெயர் தேவை");
        Map<String, Object> dup = db.one("SELECT id FROM branches WHERE company_id = ? AND LOWER(name) = LOWER(?)", cid, name);
        if (dup != null && !str(dup.get("id")).equals(id)) throw new ApiException(409, "இந்த பெயரில் கிளை ஏற்கனவே உள்ளது");
        String kind = "warehouse".equals(str(in.get("kind"))) ? "warehouse" : "shop";
        if (id.isEmpty()) {
            long n = lng(db.one("SELECT COUNT(*) AS n FROM branches WHERE company_id = ?", cid).get("n"));
            if (n >= 50) throw new ApiException(400, "அதிகபட்சம் 50 கிளைகள்");
            id = newId(kind.equals("warehouse") ? "w" : "b");
            db.update("INSERT INTO branches (company_id, id, name, kind, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?)", cid, id, name, kind, n, now());
        } else {
            if (branchRow(cid, id) == null) throw new ApiException(404, "கிளை இல்லை");
            db.update("UPDATE branches SET name = ? WHERE company_id = ? AND id = ?", name, cid, id);
        }
        return obj("branches", branchList(cid));
    }

    public Map<String, Object> savePayMode(String auth, Map<String, Object> in) {
        Ctx c0 = admin(auth);
        String cid = str(in.get("companyId")), id = str(in.get("id"));
        ownCompany(c0, cid);
        if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null) throw new ApiException(404, "கம்பெனி இல்லை");
        String name = clip(in.get("name"), 30), kind = str(in.get("kind"));
        if (name.isEmpty()) throw new ApiException(400, "பெயர் தேவை (எ.கா. UPI - Jafar)");
        if (!PAY_KINDS.contains(kind)) throw new ApiException(400, "வகை: cash / card / upi / other");
        boolean active = !(in.get("active") instanceof Boolean) || (Boolean) in.get("active");
        Map<String, Object> dup = db.one("SELECT id FROM pay_modes WHERE company_id = ? AND LOWER(name) = LOWER(?)", cid, name);
        if (dup != null && !str(dup.get("id")).equals(id)) throw new ApiException(409, "இந்த பெயர் ஏற்கனவே உள்ளது");
        if (id.isEmpty()) {
            long n = lng(db.one("SELECT COUNT(*) AS n FROM pay_modes WHERE company_id = ?", cid).get("n"));
            if (n >= 30) throw new ApiException(400, "அதிகபட்சம் 30");
            db.update("INSERT INTO pay_modes (company_id, id, name, kind, sort_order, active) VALUES (?, ?, ?, ?, ?, ?)", cid, newId("p"), name, kind, n, active);
        } else {
            if (db.one("SELECT 1 AS x FROM pay_modes WHERE company_id = ? AND id = ?", cid, id) == null) throw new ApiException(404, "இல்லை");
            if (!active && lng(db.one("SELECT COUNT(*) AS n FROM pay_modes WHERE company_id = ? AND active = 1 AND id <> ?", cid, id).get("n")) == 0)
                throw new ApiException(409, "குறைந்தது ஒரு முறை செயலில் இருக்க வேண்டும்");
            db.update("UPDATE pay_modes SET name = ?, kind = ?, active = ? WHERE company_id = ? AND id = ?", name, kind, active, cid, id);
        }
        return obj("payModes", payModeList(cid, false));
    }

    public Map<String, Object> deleteBranch(String auth, String cid, String id) {
        ownCompany(admin(auth), cid);
        if (branchRow(cid, id) == null) throw new ApiException(404, "கிளை இல்லை");
        if (lng(db.one("SELECT COUNT(*) AS n FROM branches WHERE company_id = ?", cid).get("n")) <= 1)
            throw new ApiException(409, "கடைசி கிளையை நீக்க முடியாது");
        if (db.one("SELECT 1 AS x FROM users WHERE company_id = ? AND branch_id = ? LIMIT 1", cid, id) != null)
            throw new ApiException(409, "இந்த கிளையில் users உள்ளனர். முதலில் அவர்களை வேறு கிளைக்கு மாற்றவும்");
        if (db.one("SELECT 1 AS x FROM bills WHERE company_id = ? AND branch_id = ? LIMIT 1", cid, id) != null)
            throw new ApiException(409, "இந்த கிளையில் பில்கள் உள்ளன — அறிக்கைக்காக நீக்க முடியாது. பெயரை மாற்றலாம்");
        db.update("DELETE FROM stock WHERE company_id = ? AND branch_id = ?", cid, id);
        db.update("DELETE FROM branches WHERE company_id = ? AND id = ?", cid, id);
        return obj("branches", branchList(cid));
    }

    /** Admin: today / selected day / month sales of every company, plus grand totals. */
    public Map<String, Object> adminSales(String auth, String date) {
        Ctx c = admin(auth);
        LocalDate today = LocalDate.now(zone);
        LocalDate sel = reportDate(date);
        LocalDate m1 = sel.withDayOfMonth(1);
        Object[] a = {today.atStartOfDay(), today.plusDays(1).atStartOfDay(), sel.atStartOfDay(), sel.plusDays(1).atStartOfDay(),
                m1.atStartOfDay(), m1.plusMonths(1).atStartOfDay()};
        List<Object> list = new ArrayList<>();
        double[] g = new double[6];
        for (Map<String, Object> r : db.query("SELECT c.id, c.name, c.city, "
                + "SUM(b.created_at >= ? AND b.created_at < ?) AS t_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total END), 0) AS t_tot, "
                + "SUM(b.created_at >= ? AND b.created_at < ?) AS d_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total END), 0) AS d_tot, "
                + "SUM(b.created_at >= ? AND b.created_at < ?) AS m_cnt, COALESCE(SUM(CASE WHEN b.created_at >= ? AND b.created_at < ? THEN b.total END), 0) AS m_tot "
                + "FROM companies c LEFT JOIN bills b ON b.company_id = c.id AND b.created_at >= ? AND b.created_at < ? "
                + "WHERE c.archived = 0 AND (? = 1 OR c.created_by = ?) GROUP BY c.id, c.name, c.city ORDER BY m_tot DESC, c.name",
                a[0], a[1], a[0], a[1], a[2], a[3], a[2], a[3], a[4], a[5], a[4], a[5],
                earliest(a[0], a[2], a[4]), latest(a[1], a[3], a[5]), c.system() ? 1 : 0, c.uid())) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", str(r.get("id")));
            p.put("name", str(r.get("name")));
            p.put("city", str(r.get("city")));
            String[] keys = {"t_cnt", "t_tot", "d_cnt", "d_tot", "m_cnt", "m_tot"};
            String[] out = {"todayCount", "todayTotal", "dayCount", "dayTotal", "monthCount", "monthTotal"};
            for (int i = 0; i < 6; i++) {
                double v = i % 2 == 0 ? lng(r.get(keys[i])) : round2(dbl(r.get(keys[i])));
                g[i] += v;
                p.put(out[i], i % 2 == 0 ? (Object) (long) v : (Object) v);
            }
            list.add(p);
        }
        Map<String, Object> tot = new LinkedHashMap<>();
        String[] out = {"todayCount", "todayTotal", "dayCount", "dayTotal", "monthCount", "monthTotal"};
        for (int i = 0; i < 6; i++) tot.put(out[i], i % 2 == 0 ? (Object) (long) g[i] : (Object) round2(g[i]));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("date", sel.toString());
        o.put("today", today.toString());
        o.put("month", m1.toString().substring(0, 7));
        o.put("companies", list);
        o.put("totals", tot);
        return o;
    }

    private static LocalDateTime earliest(Object... t) {
        LocalDateTime m = null;
        for (Object o : t) if (m == null || ((LocalDateTime) o).isBefore(m)) m = (LocalDateTime) o;
        return m;
    }

    private static LocalDateTime latest(Object... t) {
        LocalDateTime m = null;
        for (Object o : t) if (m == null || ((LocalDateTime) o).isAfter(m)) m = (LocalDateTime) o;
        return m;
    }

    // ================================================================ admin
    /** admin check for other components (post-deploy tests); returns the admin's name */
    public String adminName(String auth) { return str(system(auth).user.get("full_name")); }

    /** System user only: the user name that is written into the "unblocked by" column of the blocked-IP list */
    public String systemUser(String auth) { return str(system(auth).user.get("username")); }

    private Ctx admin(String auth) {
        Ctx c = ctx(auth);
        if (!c.admin()) throw new ApiException(403, "Admin மட்டும் (Admin only)");
        return c;
    }

    private Ctx system(String auth) {
        Ctx c = ctx(auth);
        if (!c.system()) throw new ApiException(403, "System user மட்டும் (System only)");
        return c;
    }

    /** an admin works only with the companies he created; the system user with all of them */
    private void ownCompany(Ctx c, String cid) {
        if (c.system()) return;
        if (blank(cid) || db.one("SELECT 1 AS x FROM companies WHERE id = ? AND created_by = ?", cid, c.uid()) == null) throw new ApiException(404, "கம்பெனி இல்லை");
    }

    private static boolean adminLevel(String type) { return "admin".equals(type) || "system".equals(type); }

    public Map<String, Object> overview(String auth) {
        Ctx c = admin(auth);
        List<Object> cs = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT c.*, "
                + "(SELECT COUNT(*) FROM users u WHERE u.company_id = c.id) AS user_count, "
                + "(SELECT COUNT(*) FROM item_groups g WHERE g.company_id = c.id) AS group_count, "
                + "(SELECT COUNT(*) FROM items i WHERE i.company_id = c.id) AS item_count "
                + "FROM companies c WHERE c.archived = 0 AND (? = 1 OR c.created_by = ?) ORDER BY c.created_at, c.name", c.system() ? 1 : 0, c.uid())) {
            Map<String, Object> p = pubCompany(r);
            p.put("userCount", lng(r.get("user_count")));
            p.put("groupCount", lng(r.get("group_count")));
            p.put("itemCount", lng(r.get("item_count")));
            p.put("billCount", lng(r.get("bill_seq")));
            List<Object> bl = new ArrayList<>();
            for (Map<String, Object> b : db.query("SELECT br.id, br.name, br.kind, (SELECT COUNT(*) FROM users u WHERE u.company_id = br.company_id AND u.branch_id = br.id) AS uc "
                    + "FROM branches br WHERE br.company_id = ? ORDER BY br.kind = 'warehouse', br.sort_order, br.created_at, br.id", str(r.get("id")))) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", str(b.get("id")));
                n.put("name", str(b.get("name")));
                n.put("kind", str(b.get("kind")));
                n.put("userCount", lng(b.get("uc")));
                bl.add(n);
            }
            p.put("branches", bl);
            p.put("payModes", payModeList(str(r.get("id")), false));
            cs.add(p);
        }
        List<Object> us = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT u.*, c.name AS company_name, br.name AS branch_name FROM users u LEFT JOIN companies c ON c.id = u.company_id "
                + "LEFT JOIN branches br ON br.company_id = u.company_id AND br.id = u.branch_id "
                + "WHERE (? = 1 OR (u.created_by = ? AND u.user_type NOT IN ('admin','system'))) ORDER BY u.created_at, u.username", c.system() ? 1 : 0, c.uid())) {
            Map<String, Object> p = pubUser(r);
            p.put("companyName", str(r.get("company_name")));
            p.put("branchName", str(r.get("branch_name")));
            us.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companies", cs);
        out.put("users", us);
        return out;
    }

    public Map<String, Object> saveCompany(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String id = str(in.get("id"));
        if (!id.isEmpty() && db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", id) == null)
            throw new ApiException(404, "கம்பெனி இல்லை");
        if (!id.isEmpty()) ownCompany(c, id);
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "கம்பெனி பெயர் தேவை");
        if (db.one("SELECT id FROM companies WHERE archived = 0 AND LOWER(name) = LOWER(?) AND id <> ?", name, id) != null)
            throw new ApiException(409, "இதே பெயரில் கம்பெனி உள்ளது");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (phone.length() > 10) phone = phone.substring(phone.length() - 10);
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String gst = clip(in.get("gstin"), 20).toUpperCase(Locale.ROOT).replaceAll("\\s", "");
        if (!gst.isEmpty() && !GSTIN.matcher(gst).matches()) throw new ApiException(400, "GSTIN தவறு (15 எழுத்து)");
        String email = clip(in.get("email"), 80);
        if (!email.isEmpty() && !EMAIL.matcher(email).matches()) throw new ApiException(400, "Email தவறு");
        String prefix = clip(in.get("billPrefix"), 8).toUpperCase(Locale.ROOT);
        if (prefix.isEmpty()) prefix = "INV";
        if (!PREFIX.matcher(prefix).matches()) throw new ApiException(400, "Bill prefix: A-Z, 0-9 மட்டும் (max 8)");
        String footer = clip(in.get("footer"), 120);
        boolean bis = false;                                   // religious greeting on bills removed: the column stays (always 0)
        double fee = in.get("billFee") == null || str(in.get("billFee")).isEmpty() ? DEFAULT_BILL_FEE : round2(dbl(in.get("billFee")));
        if (Double.isNaN(fee) || fee < 0 || fee > 1000) throw new ApiException(400, "ஒரு பில்லுக்கான கட்டணம் தவறு (0 – 1000 ₹)");
        LocalDateTime now = now();
        Object[] f = {name, clip(in.get("tagline"), 60), clip(in.get("owner"), 60), phone, clip(in.get("address"), 200),
                clip(in.get("city"), 40), gst, email, prefix, bis, footer.isEmpty() ? DEFAULT_FOOTER : footer, now};
        boolean fresh = id.isEmpty();
        if (id.isEmpty()) {
            id = newId("c");
            db.update("INSERT INTO companies (name, tagline, owner_name, phone, address, city, gstin, email, bill_prefix, show_bismillah, bill_footer, updated_at, id, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", concat(f, id, now));
            db.update("UPDATE companies SET created_by = ? WHERE id = ?", c.uid(), id);
        } else {
            db.update("UPDATE companies SET name = ?, tagline = ?, owner_name = ?, phone = ?, address = ?, city = ?, gstin = ?, email = ?, "
                    + "bill_prefix = ?, show_bismillah = ?, bill_footer = ?, updated_at = ? WHERE id = ?", concat(f, id));
        }
        db.update("UPDATE companies SET bill_fee = ? WHERE id = ?", BigDecimal.valueOf(fee), id);
        db.update("UPDATE companies SET shop_code = ? WHERE id = ? AND shop_code IS NULL", hex(bytes(5)), id);
        if (db.one("SELECT 1 AS x FROM companies WHERE id = ? AND shop_slug IS NULL", id) != null)
            db.update("UPDATE companies SET shop_slug = ? WHERE id = ?", newSlug(name, id), id);
        if (in.containsKey("shopWa")) setShopWa(id, in.get("shopWa"));
        if (in.containsKey("rateChange")) db.update("UPDATE companies SET rate_change = ? WHERE id = ?", bool(in.get("rateChange")) ? 1 : 0, id);
        if (in.containsKey("orderNoStock")) db.update("UPDATE companies SET order_no_stock = ? WHERE id = ?", bool(in.get("orderNoStock")) ? 1 : 0, id);
        ensureBranches(id);
        ensurePayModes(id);
        Map<String, Object> out = obj("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", id)));
        String tpl = str(in.get("template")).trim();
        if (fresh && !tpl.isEmpty()) out.putAll(tryTemplate(id, tpl, in));
        return out;
    }

    /** Archives the company (its groups, items and bills stay in the database). */
    public Map<String, Object> deleteCompany(String auth, String id) {
        ownCompany(admin(auth), id);
        if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", id) == null) throw new ApiException(404, "கம்பெனி இல்லை");
        if (lng(db.one("SELECT COUNT(*) AS n FROM users WHERE company_id = ?", id).get("n")) > 0)
            throw new ApiException(409, "இந்த கம்பெனியில் users உள்ளனர். முதலில் அவர்களை நீக்கவும் அல்லது மாற்றவும்");
        db.update("UPDATE companies SET archived = 1, updated_at = ? WHERE id = ?", now(), id);
        db.update("UPDATE auth_tokens SET company_id = NULL WHERE company_id = ?", id);
        return obj("ok", true);
    }

    public Map<String, Object> saveUser(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String id = str(in.get("id"));
        Map<String, Object> u = null;
        if (!id.isEmpty()) {
            u = db.one("SELECT * FROM users WHERE id = ?", id);
            if (u == null) throw new ApiException(404, "User இல்லை");
            if (!c.system() && !id.equals(c.uid()) && !c.uid().equals(str(u.get("created_by")))) throw new ApiException(404, "User இல்லை");   // an admin sees only his own users
        }
        String un = str(in.get("user")).trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(un).matches()) throw new ApiException(400, "Username: 3-30 எழுத்து, a-z 0-9 . _ - மட்டும்");
        Map<String, Object> dup = db.one("SELECT id FROM users WHERE username = ?", un);
        if (dup != null && !str(dup.get("id")).equals(id)) throw new ApiException(409, "இந்த username ஏற்கனவே உள்ளது");
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "பெயர் தேவை");
        String type = str(in.get("type"));
        if (type.equals("customer")) type = "sales";               // old app versions
        if (!USER_TYPES.contains(type)) throw new ApiException(400, "User type: system / admin / owner / sales / stock / warehouse");
        if (!c.system() && adminLevel(type) && !(u != null && id.equals(c.uid()) && type.equals(c.type())))
            throw new ApiException(403, "Admin / System user-ஐ உருவாக்க System user மட்டுமே முடியும்");
        String cid = null, bid = null;
        if (!adminLevel(type)) {
            cid = str(in.get("companyId"));
            ownCompany(c, cid);
            if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null)
                throw new ApiException(400, "இந்த user-க்கு கம்பெனி தேவை");
            if (!type.equals("owner")) {                            // sales / stock: one shop; warehouse: one warehouse
                ensureBranches(cid);
                bid = str(in.get("branchId"));
                if (blank(bid) && !type.equals("warehouse")) bid = firstBranch(cid);
                if (branchRow(cid, bid) == null) throw new ApiException(400, type.equals("warehouse") ? "Warehouse-ஐ தேர்வு செய்யவும்" : "கிளையை தேர்வு செய்யவும்");
                if (type.equals("warehouse") != isWarehouse(cid, bid))
                    throw new ApiException(400, type.equals("warehouse") ? "Warehouse user-க்கு warehouse இடம் தேவை" : "Sales / Stock user-க்கு கடை கிளை தேவை (warehouse அல்ல)");
            }
        }
        boolean active = !(in.get("active") instanceof Boolean) || (Boolean) in.get("active");
        String pw = rawStr(in.get("password"));
        if (u == null && pw.isEmpty()) pw = DEFAULT_PASSWORD;                       // new user without a password -> the default (admin1234)
        if (!pw.isEmpty()) validPassword(pw);
        boolean self = u != null && str(u.get("id")).equals(c.uid());
        if (self && (!active || !type.equals(c.type())))
            throw new ApiException(400, "உங்கள் சொந்த admin கணக்கை நிறுத்தவோ type மாற்றவோ முடியாது");
        if (u != null && adminLevel(str(u.get("user_type"))) && bool(u.get("active")) && (!adminLevel(type) || !active) && activeAdmins() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு Admin இருக்க வேண்டும்");
        if (u != null && "system".equals(str(u.get("user_type"))) && bool(u.get("active")) && (!type.equals("system") || !active) && activeSystems() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு System user இருக்க வேண்டும்");
        LocalDateTime now = now();
        long maxS = in.get("maxSessions") == null || str(in.get("maxSessions")).isBlank() ? (u == null ? DEFAULT_MAX_SESSIONS : lng(u.get("max_sessions"))) : lng(in.get("maxSessions"));
        long idleM = in.get("idleMinutes") == null || str(in.get("idleMinutes")).isBlank() ? (u == null ? DEFAULT_IDLE_MINUTES : lng(u.get("idle_minutes"))) : lng(in.get("idleMinutes"));
        if (maxS < 0 || maxS > 20) throw new ApiException(400, "அதிகபட்ச Session: 1-20 (0 = வரம்பில்லை)");
        if (idleM < 0 || idleM > 1440) throw new ApiException(400, "Idle logout: 1-1440 நிமிடம் (0 = தானாக logout இல்லை)");
        if (u == null) {
            id = newId("u");
            byte[] salt = bytes(16);
            db.update("INSERT INTO users (id, username, full_name, user_type, company_id, branch_id, pass_salt, pass_hash, active, must_change, max_sessions, idle_minutes, created_at, updated_at, created_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)", id, un, name, type, cid, bid, b64(salt), hash(pw, salt), active, maxS, idleM, now, now, c.uid());
        } else {
            db.update("UPDATE users SET max_sessions = ?, idle_minutes = ? WHERE id = ?", maxS, idleM, id);
            if (maxS > 0) db.update("DELETE FROM auth_tokens WHERE user_id = ? AND token_hash NOT IN (SELECT token_hash FROM (SELECT token_hash FROM auth_tokens WHERE user_id = ? "
                    + "ORDER BY COALESCE(last_seen, created_at) DESC LIMIT " + maxS + ") keep)", id, id);   // lowered limit: keep the most recently used sessions
            boolean roleChanged = !type.equals(str(u.get("user_type"))) || !java.util.Objects.equals(cid, nz(u.get("company_id")))
                    || !java.util.Objects.equals(bid, nz(u.get("branch_id")));
            db.update("UPDATE users SET username = ?, full_name = ?, user_type = ?, company_id = ?, branch_id = ?, active = ?, updated_at = ? WHERE id = ?",
                    un, name, type, cid, bid, active, now, id);
            if (!self && roleChanged) db.update("DELETE FROM auth_tokens WHERE user_id = ?", id);   // new rights from the next login
            if (!pw.isEmpty()) {
                byte[] salt = bytes(16);
                db.update("UPDATE users SET pass_salt = ?, pass_hash = ?, must_change = 0 WHERE id = ?", b64(salt), hash(pw, salt), id);
            }
            if (!self && (!pw.isEmpty() || !active)) db.update("DELETE FROM auth_tokens WHERE user_id = ?", id);
        }
        Map<String, Object> out = obj("user", pubUser(db.one("SELECT * FROM users WHERE id = ?", id)));
        String tpl = str(in.get("template")).trim();
        if (cid != null && !tpl.isEmpty()) out.putAll(tryTemplate(cid, tpl, in));
        return out;
    }

    public Map<String, Object> deleteUser(String auth, String id) {
        Ctx c = admin(auth);
        Map<String, Object> u = db.one("SELECT * FROM users WHERE id = ?", id);
        if (u == null) throw new ApiException(404, "User இல்லை");
        if (!c.system() && !c.uid().equals(str(u.get("created_by")))) throw new ApiException(404, "User இல்லை");
        if (id.equals(c.uid())) throw new ApiException(400, "உங்களை நீங்களே நீக்க முடியாது");
        if (adminLevel(str(u.get("user_type"))) && bool(u.get("active")) && activeAdmins() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு Admin இருக்க வேண்டும்");
        if ("system".equals(str(u.get("user_type"))) && bool(u.get("active")) && activeSystems() <= 1)
            throw new ApiException(400, "குறைந்தது ஒரு System user இருக்க வேண்டும்");
        db.update("DELETE FROM users WHERE id = ?", id);   // tokens are removed by ON DELETE CASCADE
        return obj("ok", true);
    }

    public Map<String, Object> openCompany(String auth, Map<String, Object> in) {
        Ctx c = admin(auth);
        String cid = str(in.get("companyId"));
        if (!cid.isEmpty() && db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", cid) == null)
            throw new ApiException(404, "கம்பெனி இல்லை");
        if (!cid.isEmpty()) ownCompany(c, cid);
        db.update("UPDATE auth_tokens SET company_id = ? WHERE token_hash = ?", cid.isEmpty() ? null : cid, c.key);
        c.activeCompany = cid.isEmpty() ? null : cid;
        return session(c);
    }

    private long activeAdmins() {
        return lng(db.one("SELECT COUNT(*) AS n FROM users WHERE user_type IN ('admin','system') AND active = 1").get("n"));
    }

    private long activeSystems() {
        return lng(db.one("SELECT COUNT(*) AS n FROM users WHERE user_type = 'system' AND active = 1").get("n"));
    }

    // ================================================================ output mapping
    private Map<String, Object> pubUser(Map<String, Object> u) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", str(u.get("id")));
        p.put("user", str(u.get("username")));
        p.put("name", str(u.get("full_name")));
        p.put("type", str(u.get("user_type")));
        p.put("companyId", u.get("company_id") == null ? null : str(u.get("company_id")));
        p.put("branchId", u.get("branch_id") == null ? null : str(u.get("branch_id")));
        p.put("active", bool(u.get("active")));
        p.put("maxSessions", u.get("max_sessions") == null ? DEFAULT_MAX_SESSIONS : lng(u.get("max_sessions")));
        p.put("idleMinutes", u.get("idle_minutes") == null ? DEFAULT_IDLE_MINUTES : lng(u.get("idle_minutes")));
        p.put("lang", str(u.get("lang")));
        p.put("created", iso(ldt(u.get("created_at"))));
        return p;
    }

    private Map<String, Object> pubCompany(Map<String, Object> c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", str(c.get("id")));
        p.put("name", str(c.get("name")));
        p.put("tagline", str(c.get("tagline")));
        p.put("owner", str(c.get("owner_name")));
        p.put("phone", str(c.get("phone")));
        p.put("address", str(c.get("address")));
        p.put("city", str(c.get("city")));
        p.put("gstin", str(c.get("gstin")));
        p.put("email", str(c.get("email")));
        p.put("billPrefix", str(c.get("bill_prefix")));
        String f = str(c.get("bill_footer"));
        p.put("footer", f.isEmpty() ? DEFAULT_FOOTER : f);
        p.put("created", iso(ldt(c.get("created_at"))));
        p.put("billFee", round2(dbl(c.get("bill_fee"))));
        p.put("shopCode", str(c.get("shop_code")));
        p.put("shopSlug", str(c.get("shop_slug")));
        p.put("shopWa", str(c.get("shop_wa")));
        p.put("rateChange", bool(c.get("rate_change")));
        p.put("orderNoStock", bool(c.get("order_no_stock")));
        p.put("shopEnabled", c.get("shop_enabled") == null || bool(c.get("shop_enabled")));
        return p;
    }

    // ================================================================ software invoices (MBill (Mobile Bill) -> each company)
    static final double DEFAULT_BILL_FEE = 0.25;

    /** Monthly invoices of every company for one finished month (bills x the fee saved on each bill). Safe to run again. */
    public int generateInvoices(YearMonth m) {
        if (!m.isBefore(YearMonth.now(zone))) throw new ApiException(400, "நடப்பு / வரும் மாதத்திற்கு invoice உருவாக்க முடியாது — Live invoice பார்க்கவும்");
        LocalDateTime from = m.atDay(1).atStartOfDay(), to = m.plusMonths(1).atDay(1).atStartOfDay();
        int n = 0;
        for (Map<String, Object> r : db.query("SELECT c.id, COUNT(*) AS cnt, COALESCE(SUM(COALESCE(b.platform_fee, c.bill_fee)), 0) AS amt, c.bill_fee, "
                + "(SELECT COUNT(*) FROM cancel_bills x WHERE x.company_id = c.id AND x.created_at >= ? AND x.created_at < ?) AS cx "
                + "FROM companies c JOIN " + FEE_DOCS + " b ON b.company_id = c.id AND b.created_at >= ? AND b.created_at < ? "
                + "WHERE NOT EXISTS (SELECT 1 FROM platform_invoices i WHERE i.company_id = c.id AND i.month = ?) GROUP BY c.id, c.bill_fee ORDER BY c.id", from, to, from, to, m.toString())) {
            String key = m.toString().replace("-", "");
            n += db.tx(t -> {
                if (t.one("SELECT id FROM platform_invoices WHERE company_id = ? AND month = ? FOR UPDATE", str(r.get("id")), m.toString()) != null) return 0;
                long seq = lng(t.one("SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(inv_no, '-', -1) AS UNSIGNED)), 0) AS n FROM platform_invoices WHERE month = ?", m.toString()).get("n")) + 1;   // max, not count: a deleted company leaves a gap
                t.update("INSERT INTO platform_invoices (company_id, inv_no, month, bill_count, cancel_count, fee, amount, status, generated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'unpaid', ?)",
                        str(r.get("id")), "MBILL-" + key + "-" + String.format("%04d", seq), m.toString(), lng(r.get("cnt")) - lng(r.get("cx")), lng(r.get("cx")), r.get("bill_fee"),
                        BigDecimal.valueOf(round2(dbl(r.get("amt")))), now());
                return 1;
            });
        }
        return n;
    }

    /** the scheduler: 2nd of every month 8 AM, and at start-up if that time has already passed (catch-up) */
    public int generateDueInvoices() {
        LocalDateTime nowT = now();
        YearMonth last = YearMonth.from(nowT).minusMonths(1);
        if (nowT.isBefore(YearMonth.from(nowT).atDay(2).atTime(8, 0))) last = last.minusMonths(1);   // before the 2nd, 8 AM: only up to the month before
        int n = 0;
        for (YearMonth m = last.minusMonths(2); !m.isAfter(last); m = m.plusMonths(1)) n += generateInvoices(m);
        return n;
    }

    private YearMonth month(String s, YearMonth def) {
        if (blank(s)) return def;
        try { return YearMonth.parse(s.trim()); } catch (DateTimeParseException e) { throw new ApiException(400, "மாதம் தவறு (yyyy-MM)"); }
    }

    /** owner: own company; admin: ?company= */
    private String invCompany(Ctx c, String company) {
        if (c.admin()) {
            if (blank(company)) throw new ApiException(400, "கம்பெனியை தேர்வு செய்யவும்");
            if (db.one("SELECT id FROM companies WHERE id = ?", company.trim()) == null) throw new ApiException(404, "கம்பெனி இல்லை");
            ownCompany(c, company.trim());
            return company.trim();
        }
        if (!c.owner()) throw new ApiException(403, "Invoice: Owner / Admin மட்டும்");
        return requireCompany(c);
    }

    private Map<String, Object> invRow(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("inv_no")));
        n.put("month", str(r.get("month")));
        n.put("companyId", str(r.get("company_id")));
        if (r.containsKey("company_name")) n.put("company", str(r.get("company_name")));
        if (r.containsKey("company_phone")) n.put("phone", str(r.get("company_phone")));
        n.put("bills", lng(r.get("bill_count")));
        n.put("cancels", lng(r.get("cancel_count")));
        n.put("docs", lng(r.get("bill_count")) + lng(r.get("cancel_count")));
        n.put("fee", round2(dbl(r.get("fee"))));
        n.put("amount", round2(dbl(r.get("amount"))));
        n.put("status", str(r.get("status")));
        n.put("generatedAt", iso(ldt(r.get("generated_at"))));
        if (r.get("paid_at") != null) {
            n.put("paidAt", iso(ldt(r.get("paid_at"))));
            n.put("paidAmount", round2(dbl(r.get("paid_amount"))));
            n.put("paidRef", str(r.get("paid_ref")));
            n.put("paidBy", str(r.get("paid_by")));
        }
        return n;
    }

    /** live (this month so far) figures */
    private Map<String, Object> liveInv(String cid) {
        YearMonth m = YearMonth.now(zone);
        LocalDateTime f = m.atDay(1).atStartOfDay(), to = m.plusMonths(1).atDay(1).atStartOfDay();
        Map<String, Object> r = db.one("SELECT COUNT(b.created_at) AS cnt, COALESCE(SUM(COALESCE(b.platform_fee, c.bill_fee)), 0) AS amt, MAX(c.bill_fee) AS fee FROM companies c "
                + "LEFT JOIN " + FEE_DOCS + " b ON b.company_id = c.id AND b.created_at >= ? AND b.created_at < ? WHERE c.id = ?", f, to, cid);
        long cx = lng(db.one("SELECT COUNT(*) AS n FROM cancel_bills WHERE company_id = ? AND created_at >= ? AND created_at < ?", cid, f, to).get("n"));
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("month", m.toString());
        n.put("bills", lng(r.get("cnt")) - cx);
        n.put("cancels", cx);
        n.put("docs", lng(r.get("cnt")));
        n.put("amount", round2(dbl(r.get("amt"))));
        n.put("fee", round2(dbl(r.get("fee"))));
        n.put("asOf", iso(now()));
        n.put("generateOn", iso(m.plusMonths(1).atDay(2).atTime(8, 0)));
        return n;
    }

    /** invoices + payment statement of one company */
    public Map<String, Object> invoices(String auth, String company) {
        Ctx c = ctx(auth);
        String cid = invCompany(c, company);
        Map<String, Object> co = db.one("SELECT * FROM companies WHERE id = ?", cid);
        List<Object> list = new ArrayList<>();
        double billed = 0, paid = 0;
        for (Map<String, Object> r : db.query("SELECT * FROM platform_invoices WHERE company_id = ? ORDER BY month DESC", cid)) {
            list.add(invRow(r));
            billed += dbl(r.get("amount"));
            if ("paid".equals(str(r.get("status")))) paid += dbl(r.get("paid_amount"));
        }
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("billed", round2(billed));
        st.put("paid", round2(paid));
        st.put("due", round2(billed - paid));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("company", pubCompany(co));
        o.put("live", liveInv(cid));
        o.put("invoices", list);
        o.put("statement", st);
        o.put("admin", c.admin());
        return o;
    }

    /** bill-wise invoice for one month (a generated invoice, or the live current month) */
    public Map<String, Object> invoiceDetail(String auth, String company, String monthIn) {
        Ctx c = ctx(auth);
        String cid = invCompany(c, company);
        YearMonth m = month(monthIn, YearMonth.now(zone));
        Map<String, Object> co = db.one("SELECT * FROM companies WHERE id = ?", cid);
        Map<String, Object> inv = db.one("SELECT * FROM platform_invoices WHERE company_id = ? AND month = ?", cid, m.toString());
        LocalDateTime from = m.atDay(1).atStartOfDay(), to = m.plusMonths(1).atDay(1).atStartOfDay();
        List<Object> bills = new ArrayList<>();
        Map<String, double[]> days = new LinkedHashMap<>();
        double amt = 0;
        long cnt = 0;
        long cxn = 0;
        for (Map<String, Object> b : db.query("SELECT d.no AS bill_no, d.kind, d.created_at, d.total, COALESCE(d.platform_fee, c.bill_fee) AS fee, br.name AS branch FROM "
                + "(SELECT company_id, branch_id, bill_no AS no, 'bill' AS kind, created_at, total, platform_fee, id FROM bills WHERE company_id = ? AND created_at >= ? AND created_at < ? "
                + "UNION ALL SELECT company_id, branch_id, cn_no, 'cancel', created_at, total, platform_fee, id FROM cancel_bills WHERE company_id = ? AND created_at >= ? AND created_at < ?) d "
                + "JOIN companies c ON c.id = d.company_id LEFT JOIN branches br ON br.company_id = d.company_id AND br.id = d.branch_id ORDER BY d.created_at, d.id", cid, from, to, cid, from, to)) {
            if ("cancel".equals(str(b.get("kind")))) cxn++;
            LocalDateTime at = ldt(b.get("created_at"));
            double f = round2(dbl(b.get("fee")));
            amt += f; cnt++;
            double[] d = days.computeIfAbsent(at.toLocalDate().toString(), k -> new double[2]);
            d[0]++; d[1] += f;
            if (bills.size() < 5000) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("no", str(b.get("bill_no")));
                n.put("kind", str(b.get("kind")));
                n.put("at", iso(at));
                n.put("branch", str(b.get("branch")));
                n.put("total", round2(dbl(b.get("total"))));
                n.put("fee", f);
                bills.add(n);
            }
        }
        List<Object> dayList = new ArrayList<>();
        for (Map.Entry<String, double[]> e : days.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("date", e.getKey());
            n.put("bills", (long) e.getValue()[0]);
            n.put("amount", round2(e.getValue()[1]));
            dayList.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("company", pubCompany(co));
        o.put("month", m.toString());
        o.put("from", m.atDay(1).toString());
        o.put("to", m.atEndOfMonth().toString());
        o.put("live", inv == null && !m.isBefore(YearMonth.now(zone)));
        if (inv != null) o.put("invoice", invRow(inv));
        o.put("billCount", cnt - cxn);
        o.put("cancelCount", cxn);
        o.put("docCount", cnt);
        o.put("amount", round2(amt));
        o.put("days", dayList);
        o.put("bills", bills);
        o.put("admin", c.admin());
        return o;
    }

    /** admin: every company's invoice for a month */
    public Map<String, Object> adminInvoices(String auth, String monthIn) {
        system(auth);
        YearMonth m = month(monthIn, YearMonth.now(zone).minusMonths(1));
        boolean live = !m.isBefore(YearMonth.now(zone));
        LocalDateTime from = m.atDay(1).atStartOfDay(), to = m.plusMonths(1).atDay(1).atStartOfDay();
        List<Object> rows = new ArrayList<>();
        double amt = 0, paid = 0;
        for (Map<String, Object> co : db.query("SELECT c.id, c.name, c.phone, c.bill_fee, (SELECT COUNT(*) FROM " + FEE_DOCS + " b WHERE b.company_id = c.id AND b.created_at >= ? AND b.created_at < ?) AS cnt, "
                + "(SELECT COUNT(*) FROM cancel_bills x WHERE x.company_id = c.id AND x.created_at >= ? AND x.created_at < ?) AS cx, "
                + "(SELECT COALESCE(SUM(COALESCE(b.platform_fee, c.bill_fee)), 0) FROM " + FEE_DOCS + " b WHERE b.company_id = c.id AND b.created_at >= ? AND b.created_at < ?) AS amt "
                + "FROM companies c WHERE c.archived = 0 OR EXISTS (SELECT 1 FROM platform_invoices i WHERE i.company_id = c.id AND i.month = ?) ORDER BY c.name", from, to, from, to, from, to, m.toString())) {
            Map<String, Object> inv = db.one("SELECT * FROM platform_invoices WHERE company_id = ? AND month = ?", str(co.get("id")), m.toString());
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("companyId", str(co.get("id")));
            n.put("company", str(co.get("name")));
            n.put("phone", str(co.get("phone")));
            n.put("fee", round2(dbl(co.get("bill_fee"))));
            n.put("bills", lng(co.get("cnt")) - lng(co.get("cx")));
            n.put("cancels", lng(co.get("cx")));
            n.put("docs", lng(co.get("cnt")));
            n.put("amount", round2(dbl(co.get("amt"))));
            if (inv != null) {
                n.put("invoice", invRow(inv));
                if ("paid".equals(str(inv.get("status")))) paid += dbl(inv.get("paid_amount"));
                amt += dbl(inv.get("amount"));
            } else amt += dbl(co.get("amt"));
            rows.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("month", m.toString());
        o.put("live", live);
        o.put("rows", rows);
        o.put("amount", round2(amt));
        o.put("paid", round2(paid));
        o.put("due", round2(amt - paid));
        return o;
    }

    public Map<String, Object> adminGenerateInvoices(String auth, Map<String, Object> in) {
        system(auth);
        int n = generateInvoices(month(str(in.get("month")), YearMonth.now(zone).minusMonths(1)));
        return obj("created", n);
    }

    /** admin: payment received {amount, ref, date} (or paid=false to undo) */
    public Map<String, Object> payInvoice(String auth, long id, Map<String, Object> in) {
        Ctx c = system(auth);
        Map<String, Object> inv = db.one("SELECT * FROM platform_invoices WHERE id = ?", id);
        if (inv == null) throw new ApiException(404, "Invoice இல்லை");
        if (Boolean.FALSE.equals(in.get("paid"))) {
            db.update("UPDATE platform_invoices SET status = 'unpaid', paid_at = NULL, paid_amount = 0, paid_ref = '', paid_by = '' WHERE id = ?", id);
        } else {
            double a = in.get("amount") == null || str(in.get("amount")).isEmpty() ? dbl(inv.get("amount")) : round2(dbl(in.get("amount")));
            if (Double.isNaN(a) || a < 0 || a > 10000000) throw new ApiException(400, "தொகை தவறு");
            LocalDateTime at = now();
            if (!blank(str(in.get("date")))) {
                try { at = LocalDate.parse(str(in.get("date"))).atTime(at.toLocalTime()); } catch (DateTimeParseException e) { throw new ApiException(400, "தேதி தவறு"); }
            }
            db.update("UPDATE platform_invoices SET status = 'paid', paid_at = ?, paid_amount = ?, paid_ref = ?, paid_by = ? WHERE id = ?",
                    at, BigDecimal.valueOf(a), clip(in.get("ref"), 60), str(c.user.get("full_name")), id);
        }
        return obj("invoice", invRow(db.one("SELECT * FROM platform_invoices WHERE id = ?", id)));
    }

    // ================================================================ item photos
    /** {data: "data:image/jpeg;base64,..."} (resized in the browser) or {remove: true} */
    public Map<String, Object> savePhoto(String auth, String itemId, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        if (db.one("SELECT id FROM items WHERE company_id = ? AND id = ?", cid, itemId) == null) throw new ApiException(404, "ஐட்டம் இல்லை");
        if (Boolean.TRUE.equals(in.get("remove"))) {
            db.update("DELETE FROM item_photos WHERE company_id = ? AND item_id = ?", cid, itemId);
            return obj("photo", null);
        }
        String d = str(in.get("data"));
        java.util.regex.Matcher m = Pattern.compile("^data:(image/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=]+)$").matcher(d);
        if (!m.matches()) throw new ApiException(400, "படம் தவறு (JPEG / PNG / WebP)");
        byte[] img;
        try { img = Base64.getDecoder().decode(m.group(2)); } catch (IllegalArgumentException e) { throw new ApiException(400, "படம் தவறு"); }
        if (img.length > 400_000) throw new ApiException(400, "படம் மிகப் பெரியது (max 400 KB)");
        LocalDateTime now = now();
        db.update("INSERT INTO item_photos (company_id, item_id, mime, data, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE mime = VALUES(mime), data = VALUES(data), updated_at = VALUES(updated_at)", cid, itemId, m.group(1), img, now);
        return obj("photo", lng(db.one("SELECT UNIX_TIMESTAMP(updated_at) AS v FROM item_photos WHERE company_id = ? AND item_id = ?", cid, itemId).get("v")));
    }

    /** photo bytes for the shop page and the app: [mime, bytes] or null */
    public Object[] photo(String shopOrCompany, String itemId, boolean byShopCode) {
        String cid = byShopCode ? shopCompany(shopOrCompany) : shopOrCompany;
        Map<String, Object> r = db.one("SELECT mime, data FROM item_photos WHERE company_id = ? AND item_id = ?", cid, itemId);
        return r == null ? null : new Object[]{str(r.get("mime")), r.get("data")};
    }

    public Object[] appPhoto(String auth, String itemId) {
        Ctx c = ctx(auth);
        return photo(requireCompany(c), itemId, false);
    }

    // ================================================================ online shop (public, by the company's shop code)
    private String shopCompany(String code) {
        Map<String, Object> r = blank(code) ? null : db.one("SELECT id, shop_enabled FROM companies WHERE (shop_slug = ? OR shop_code = ?) AND archived = 0", code.trim(), code.trim());
        if (r == null) throw new ApiException(404, "கடை இல்லை (Shop not found)");
        if (!bool(r.get("shop_enabled"))) throw new ApiException(403, "Online ஆர்டர் தற்போது இல்லை");
        return str(r.get("id"));
    }

    /** catalog with selling prices (never cost), locations and the stock in hand at each location */
    public Map<String, Object> shop(String code) {
        String cid = shopCompany(code);
        Map<String, Object> co = db.one("SELECT * FROM companies WHERE id = ?", cid);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", str(co.get("name")));
        c.put("tagline", str(co.get("tagline")));
        c.put("phone", str(co.get("phone")));
        c.put("address", str(co.get("address")));
        c.put("city", str(co.get("city")));
        c.put("wa", shopWa(co));
        c.put("slug", str(co.get("shop_slug")));
        c.put("orderNoStock", bool(co.get("order_no_stock")));      // true: an item with no stock can still be ordered
        Map<String, Object> stock = new LinkedHashMap<>();
        for (Map<String, Object> r : db.query("SELECT branch_id, item_id, size_label, qty FROM stock WHERE company_id = ? AND qty > 0", cid)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) stock.computeIfAbsent(str(r.get("branch_id")), k -> new LinkedHashMap<>());
            m.put(str(r.get("item_id")) + "|" + str(r.get("size_label")), lng(r.get("qty")));
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("company", c);
        o.put("branches", branchList(cid));
        o.put("catalog", loadCatalog(db, cid, false));
        o.put("stock", stock);
        return o;
    }

    /** customer order: {name, phone, address, mode: delivery|pickup, branch, notes, lines:[{itemId, size, qty}]} */
    private static String fmtGrams(long g) { return g >= 1000 ? new BigDecimal(g).divide(new BigDecimal(1000)).stripTrailingZeros().toPlainString() + " kg" : g + " g"; }

    public Map<String, Object> shopOrder(String code, Map<String, Object> in) {
        String cid = shopCompany(code);
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "பெயர் தேவை");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (phone.length() > 10) phone = phone.substring(phone.length() - 10);
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String mode = "pickup".equals(str(in.get("mode"))) ? "pickup" : "delivery";
        String address = clip(in.get("address"), 300);
        if (mode.equals("delivery") && address.length() < 8) throw new ApiException(400, "டெலிவரி முகவரி தேவை");
        String branch = str(in.get("branch"));
        if (branchRow(cid, branch) == null) throw new ApiException(400, "எந்த கடை / warehouse-லிருந்து என்று தேர்வு செய்யவும்");
        // a customer can't flood the shop: max 5 open orders per mobile number
        if (lng(db.one("SELECT COUNT(*) AS n FROM shop_orders WHERE company_id = ? AND phone = ? AND status IN ('new','accepted')", cid, phone).get("n")) >= 5)
            throw new ApiException(429, "இந்த எண்ணுக்கு ஏற்கனவே 5 ஆர்டர்கள் நிலுவையில் உள்ளன — கடையை அழைக்கவும்");
        Map<String, Object[]> cat = new HashMap<>();
        Map<String, Integer> wtUnit = new HashMap<>();
        Map<String, String> wtLabel = new HashMap<>();
        for (Map<String, Object> z : db.query("SELECT z.item_id, z.size_label, z.rate, i.name_ta, i.name_en, i.sold_by, i.wt_unit FROM item_sizes z JOIN items i ON i.company_id = z.company_id AND i.id = z.item_id WHERE z.company_id = ?", cid)) {
            // weight items (fish, meat ...) are ordered in whole kg (or g) steps; their stock is kept in grams
            if ("weight".equals(str(z.get("sold_by")))) { wtUnit.put(str(z.get("item_id")), "g".equals(str(z.get("wt_unit"))) ? 1 : 1000); wtLabel.put(str(z.get("item_id")), str(z.get("size_label"))); }
            cat.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), new Object[]{dbl(z.get("rate")), str(z.get("name_ta")), str(z.get("name_en"))}); }
        Map<String, Long> have = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT item_id, size_label, qty FROM stock WHERE company_id = ? AND branch_id = ?", cid, branch))
            have.put(str(r.get("item_id")) + "|" + str(r.get("size_label")), lng(r.get("qty")));
        List<Object> lines = new ArrayList<>();
        Map<String, Long> seen = new LinkedHashMap<>();
        for (Object o : list(in.get("lines"))) {
            Map<String, Object> l = map(o);
            String wl = wtLabel.get(str(l.get("itemId")));
            if (wl != null) {                                     // weight item: the customer gives grams (wt) of the item
                double w = dbl(l.get("wt"));
                if (!(w >= 10 && w <= 99_000) || w != Math.floor(w)) throw new ApiException(400, "எடை தவறு (10 g – 99 kg)");
                seen.merge(str(l.get("itemId")) + "|" + wl, (long) w, Long::sum);
                continue;
            }
            String k = str(l.get("itemId")) + "|" + str(l.get("size"));
            double q = dbl(l.get("qty"));
            if (!cat.containsKey(k) || !(q >= 1 && q <= 99) || q != Math.floor(q)) throw new ApiException(400, "ஆர்டர் ஐட்டம் தவறு");
            seen.merge(k, (long) q, Long::sum);
        }
        if (seen.isEmpty()) throw new ApiException(400, "Cart காலியாக உள்ளது");
        if (seen.size() > 50) throw new ApiException(400, "ஒரு ஆர்டரில் 50 ஐட்டம் வரை");
        final boolean noStockOk = bool(db.one("SELECT order_no_stock FROM companies WHERE id = ?", cid).get("order_no_stock"));
        double total = 0;
        long pcs = 0;
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            Object[] z = cat.get(e.getKey());
            long h = have.getOrDefault(e.getKey(), 0L);
            String iid0 = e.getKey().split("\\|", 2)[0];
            if (wtLabel.containsKey(iid0)) {                        // weight line: quantity and stock in grams, rate per kg (or per g)
                long g = e.getValue(); int ug = wtUnit.get(iid0);
                if (!noStockOk && g > h) throw new ApiException(409, z[1] + ": இந்த கடையில் " + fmtGrams(Math.max(0, h)) + " மட்டுமே உள்ளது");
                double amt = round2((Double) z[0] * g / ug);
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("id", iid0); w.put("name", z[1]); w.put("en", z[2]); w.put("size", fmtGrams(g)); w.put("qty", 1L);
                w.put("rate", amt); w.put("amt", amt); w.put("wt", g);
                if (g > h) w.put("short", g - Math.max(0, h));
                total += amt; pcs += 1; lines.add(w);
                continue;
            }
            if (!noStockOk && e.getValue() > h) throw new ApiException(409, z[1] + " (" + e.getKey().split("\\|", 2)[1] + "): இந்த கடையில் " + Math.max(0, h) + " மட்டுமே உள்ளது");
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", e.getKey().split("\\|", 2)[0]);
            n.put("name", z[1]);
            n.put("en", z[2]);
            n.put("size", e.getKey().split("\\|", 2)[1]);
            n.put("qty", e.getValue());
            n.put("rate", z[0]);
            n.put("amt", round2((Double) z[0] * e.getValue()));
            if (e.getValue() > h) n.put("short", e.getValue() - Math.max(0, h));          // ordered more than the stock in hand (company allows it)
            total += (Double) z[0] * e.getValue();
            pcs += e.getValue();
            lines.add(n);
        }
        final double tot = round2(total);
        final long pieces = pcs;
        final String ph = phone;
        String token = hex(bytes(12));
        return db.tx(t -> {
            Map<String, Object> co = t.one("SELECT order_seq, bill_prefix FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq = lng(co.get("order_seq")) + 1;
            t.update("UPDATE companies SET order_seq = ? WHERE id = ?", seq, cid);
            String no = "ORD-" + String.format("%04d", seq);
            LocalDateTime now = now();
            t.update("INSERT INTO shop_orders (company_id, order_seq, order_no, token, branch_id, name, phone, address, mode, notes, lines_json, pieces, total, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'new', ?, ?)", cid, seq, no, token, branch, name, ph, address, mode, clip(in.get("notes"), 300),
                    toJson(lines), pieces, BigDecimal.valueOf(tot), now, now);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("no", no);
            o.put("token", token);
            o.put("total", tot);
            return o;
        });
    }

    static final List<String> ORDER_FLOW = List.of("new", "accepted", "billed", "out", "delivered");

    private boolean hasTable(String t) {
        return db.one("SELECT 1 AS x FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", t) != null;
    }

    /**
     * Sales / stock user takes an order for a customer (phone call, WhatsApp, at the counter for later delivery):
     * {name, phone, address, mode: delivery|pickup, notes, lines:[{id, name, en, size, qty, rate}]} - for their own branch.
     * It joins the same order flow as online orders (accept -> bill -> out for delivery -> delivered) and the customer
     * can follow it on the status link.
     */
    public Map<String, Object> takeOrder(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireSelling(c);
        String branch = userBranch(c, cid);
        String name = clip(in.get("name"), 60);
        if (name.isEmpty()) throw new ApiException(400, "வாடிக்கையாளர் பெயர் தேவை");
        String phone = str(in.get("phone")).replaceAll("[^0-9]", "");
        if (!MOBILE.matcher(phone).matches()) throw new ApiException(400, "சரியான 10 இலக்க மொபைல் எண் தேவை");
        String mode = "pickup".equals(str(in.get("mode"))) ? "pickup" : "delivery";
        String address = clip(in.get("address"), 300);
        if (mode.equals("delivery") && address.length() < 8) throw new ApiException(400, "டெலிவரி முகவரி தேவை");
        List<Map<String, Object>> lines = mergeLines(parseLines(list(in.get("lines"))));
        if (lines.isEmpty()) throw new ApiException(400, "ஆர்டரில் ஐட்டம் இல்லை");
        if (c.sales()) {                                    // sales staff: catalog rate only
            Map<String, Double> rates = new HashMap<>();
            for (Map<String, Object> z : db.query("SELECT item_id, size_label, rate FROM item_sizes WHERE company_id = ?", cid))
                rates.put(str(z.get("item_id")) + "|" + str(z.get("size_label")), dbl(z.get("rate")));
            for (Map<String, Object> l : lines) {
                Double r = l.get("id") == null ? null : rates.get(l.get("id") + "|" + l.get("size"));
                if (r != null && Math.abs(r - (Double) l.get("rate")) > 0.001) throw new ApiException(400, "ரேட் மாற்ற அனுமதி இல்லை: " + l.get("name"));
            }
        }
        // stock in hand at this branch: refuse an item without stock unless the company allows it (it is then marked "short" on the order)
        final boolean noStockOk = bool(db.one("SELECT order_no_stock FROM companies WHERE id = ?", cid).get("order_no_stock"));
        if (branch != null) {
            Link lk = link(db, cid);
            Map<String, Long> have = new HashMap<>();
            for (Map<String, Object> r : db.query("SELECT item_id, size_label, qty FROM stock WHERE company_id = ? AND branch_id = ?", cid, branch))
                have.put(str(r.get("item_id")) + "|" + str(r.get("size_label")), lng(r.get("qty")));
            Map<String, Long> need = new LinkedHashMap<>();
            Map<String, String> label = new HashMap<>();
            for (Map<String, Object> l : lines) {
                String iid = (String) l.get("id");
                if (iid == null || !lk.linked(iid, l)) continue;
                String k = iid + "|" + lk.stockSize(iid, l);
                need.merge(k, lk.stockQty(iid, l), Long::sum);
                label.putIfAbsent(k, l.get("name") + (lk.weight(iid) ? "" : " (" + l.get("size") + ")"));
            }
            for (Map.Entry<String, Long> e : need.entrySet()) {
                long h = have.getOrDefault(e.getKey(), 0L);
                if (e.getValue() <= h) continue;
                if (!noStockOk)
                    throw new ApiException(409, label.get(e.getKey()) + ": இந்த கிளையில் ஸ்டாக் " + Math.max(0, h) + " மட்டுமே — ஸ்டாக் இல்லாத ஐட்டத்துக்கு ஆர்டர் எடுக்க முடியாது");
                for (Map<String, Object> l : lines) {
                    String iid = (String) l.get("id");
                    if (iid != null && lk.linked(iid, l) && e.getKey().equals(iid + "|" + lk.stockSize(iid, l))) l.put("short", e.getValue() - Math.max(0, h));
                }
            }
        }
        double total = 0;
        long pcs = 0;
        for (Map<String, Object> l : lines) { total += (Double) l.get("amt"); pcs += (Long) l.get("qty"); }
        final double tot = round2(total);
        final long pieces = pcs;
        String by = str(c.user.get("full_name"));
        String token = hex(bytes(12));
        long id = db.tx(t -> {
            Map<String, Object> co = t.one("SELECT order_seq FROM companies WHERE id = ? FOR UPDATE", cid);
            long seq = lng(co.get("order_seq")) + 1;
            t.update("UPDATE companies SET order_seq = ? WHERE id = ?", seq, cid);
            LocalDateTime now = now();
            return t.insert("INSERT INTO shop_orders (company_id, order_seq, order_no, token, branch_id, name, phone, address, mode, notes, lines_json, pieces, total, status, "
                    + "handled_by, source, taken_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'accepted', ?, 'staff', ?, ?, ?)",
                    cid, seq, "ORD-" + String.format("%04d", seq), token, branch, name, phone, address, mode, clip(in.get("notes"), 300),
                    toJson(lines), pieces, BigDecimal.valueOf(tot), by, by, now, now);
        });
        return obj("order", orderRow(db.one("SELECT o.*, br.name AS branch_name FROM shop_orders o LEFT JOIN branches br ON br.company_id = o.company_id AND br.id = o.branch_id WHERE o.id = ?", id), true));
    }

    private Map<String, Object> orderRow(Map<String, Object> r, boolean staff) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (staff) n.put("id", lng(r.get("id")));
        n.put("no", str(r.get("order_no")));
        if (staff) n.put("token", str(r.get("token")));
        n.put("at", iso(ldt(r.get("created_at"))));
        n.put("updatedAt", iso(ldt(r.get("updated_at"))));
        n.put("status", str(r.get("status")));
        n.put("name", str(r.get("name")));
        n.put("phone", staff ? str(r.get("phone")) : "******" + str(r.get("phone")).substring(6));
        n.put("address", str(r.get("address")));
        n.put("mode", str(r.get("mode")));
        n.put("notes", str(r.get("notes")));
        n.put("branchId", str(r.get("branch_id")));
        n.put("branch", str(r.get("branch_name")));
        n.put("pieces", lng(r.get("pieces")));
        n.put("total", round2(dbl(r.get("total"))));
        n.put("billNo", str(r.get("bill_no")));
        n.put("handledBy", str(r.get("handled_by")));
        n.put("statusNote", str(r.get("status_note")));
        n.put("source", r.get("source") == null ? "online" : str(r.get("source")));
        if (staff) n.put("takenBy", str(r.get("taken_by")));
        n.put("lines", jsonList(r.get("lines_json")));
        return n;
    }

    /** the customer's status page (by the secret token in their link) */
    public Map<String, Object> shopOrderStatus(String code, String token) {
        String cid = shopCompany(code);
        Map<String, Object> r = db.one("SELECT o.*, br.name AS branch_name FROM shop_orders o LEFT JOIN branches br ON br.company_id = o.company_id AND br.id = o.branch_id "
                + "WHERE o.company_id = ? AND o.token = ?", cid, str(token));
        if (r == null) throw new ApiException(404, "ஆர்டர் இல்லை");
        return obj("order", orderRow(r, false));
    }

    /** staff: orders (sales / stock: their branch; admin / owner: all) */
    public Map<String, Object> orders(String auth, String status) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        String br = c.admin() || c.owner() ? null : userBranch(c, cid);
        String st = blank(status) ? "open" : status.trim();
        String sf = switch (st) {
            case "open" -> " AND o.status IN ('new','accepted','billed','out')";
            case "done" -> " AND o.status IN ('delivered','cancelled')";
            default -> "";
        };
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT o.*, br.name AS branch_name FROM shop_orders o LEFT JOIN branches br ON br.company_id = o.company_id AND br.id = o.branch_id "
                + "WHERE o.company_id = ?" + (br == null ? "" : " AND o.branch_id = ?") + sf + " ORDER BY o.id DESC LIMIT 200", br == null ? new Object[]{cid} : new Object[]{cid, br}))
            out.add(orderRow(r, true));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("orders", out);
        o.put("newCount", lng(db.one("SELECT COUNT(*) AS n FROM shop_orders WHERE company_id = ? AND status = 'new'" + (br == null ? "" : " AND branch_id = ?"),
                br == null ? new Object[]{cid} : new Object[]{cid, br}).get("n")));
        o.put("canAct", !c.owner() && !c.warehouse() || c.admin());
        return o;
    }

    /** staff: {status: accepted|out|delivered|cancelled, note} or {billNo} when the order was turned into a bill */
    public Map<String, Object> orderUpdate(String auth, long id, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        if (c.owner()) throw new ApiException(403, "Owner: பார்க்க மட்டும்");
        Map<String, Object> r = db.one("SELECT * FROM shop_orders WHERE company_id = ? AND id = ?", cid, id);
        if (r == null) throw new ApiException(404, "ஆர்டர் இல்லை");
        if (!c.admin() && !str(r.get("branch_id")).equals(userBranch(c, cid))) throw new ApiException(403, "வேறு கிளையின் ஆர்டர்");
        String cur = str(r.get("status"));
        if (cur.equals("delivered") || cur.equals("cancelled")) throw new ApiException(409, "இந்த ஆர்டர் முடிந்துவிட்டது");
        String by = str(c.user.get("full_name"));
        String bill = clip(in.get("billNo"), 30).toUpperCase(Locale.ROOT);
        String next;
        if (!bill.isEmpty()) {
            if (db.one("SELECT id FROM bills WHERE company_id = ? AND bill_no = ?", cid, bill) == null) throw new ApiException(404, "பில் இல்லை");
            next = "billed";
        } else {
            next = str(in.get("status"));
            if (!Set.of("accepted", "out", "delivered", "cancelled").contains(next)) throw new ApiException(400, "நிலை தவறு");
            if (next.equals("delivered") && str(r.get("bill_no")).isEmpty()) throw new ApiException(400, "முதலில் பில்லாக மாற்றவும்");
        }
        db.update("UPDATE shop_orders SET status = ?, status_note = ?, handled_by = ?, updated_at = ?" + (bill.isEmpty() ? "" : ", bill_no = '" + bill.replace("'", "") + "'")
                + " WHERE company_id = ? AND id = ?", next, clip(in.get("note"), 120), by, now(), cid, id);
        return obj("order", orderRow(db.one("SELECT o.*, br.name AS branch_name FROM shop_orders o LEFT JOIN branches br ON br.company_id = o.company_id AND br.id = o.branch_id WHERE o.id = ?", id), true));
    }

    /** admin / owner: switch online ordering on or off */
    public Map<String, Object> shopSettings(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireCompany(c);
        if (!c.admin() && !c.owner()) throw new ApiException(403, "Owner / Admin மட்டும்");
        if (in.get("enabled") instanceof Boolean) db.update("UPDATE companies SET shop_enabled = ? WHERE id = ?", (Boolean) in.get("enabled"), cid);
        if (Boolean.TRUE.equals(in.get("newCode"))) db.update("UPDATE companies SET shop_code = ? WHERE id = ?", hex(bytes(5)), cid);
        if (in.containsKey("slug")) {
            String sl = clip(in.get("slug"), 40).toLowerCase(Locale.ROOT);
            if (sl.isEmpty()) sl = newSlug(str(db.one("SELECT name FROM companies WHERE id = ?", cid).get("name")), cid);
            else if (!CatalogImport.validSlug(sl)) throw new ApiException(400, "Shop address: a-z, 0-9, '-' மட்டும் (3 – 40 எழுத்து, '--' கூடாது)");
            else if (!slugFree(sl, cid)) throw new ApiException(409, "இந்த shop address ஏற்கனவே வேறு கடைக்கு உள்ளது — வேறு பெயர் தரவும்");
            db.update("UPDATE companies SET shop_slug = ? WHERE id = ?", sl, cid);
        }
        if (in.containsKey("wa")) setShopWa(cid, in.get("wa"));
        return obj("company", pubCompany(db.one("SELECT * FROM companies WHERE id = ?", cid)));
    }

    /** web address word of a company: name based and unique, e.g. "Dry Fruit Palace" -> dry-fruit-palace (-2, -3 ... when taken) */
    private String newSlug(String name, String cid) {
        String base = CatalogImport.slugBase(name);
        if (base.length() < 3 || !CatalogImport.validSlug(base)) base = "shop-" + hex(bytes(3));
        String sl = base;
        int n = 2;
        while (!slugFree(sl, cid)) sl = base + "-" + (n++);
        return sl;
    }

    private boolean slugFree(String sl, String cid) {
        if (!CatalogImport.validSlug(sl)) return false;
        Map<String, Object> r = db.one("SELECT id FROM companies WHERE shop_slug = ? OR shop_code = ?", sl, sl);
        return r == null || str(r.get("id")).equals(cid);
    }

    /** WhatsApp number that receives the online orders (blank = the company phone) */
    private void setShopWa(String cid, Object raw) {
        String w = str(raw).replaceAll("[^0-9]", "");
        if (w.length() > 10) w = w.substring(w.length() - 10);
        if (!w.isEmpty() && !MOBILE.matcher(w).matches()) throw new ApiException(400, "WhatsApp எண்: சரியான 10 இலக்க மொபைல் எண் தேவை");
        db.update("UPDATE companies SET shop_wa = ? WHERE id = ?", w.isEmpty() ? null : w, cid);
    }

    private static String shopWa(Map<String, Object> co) {
        String w = str(co.get("shop_wa"));
        return w.isEmpty() ? str(co.get("phone")) : w;
    }

    // ================================================================ master data: ready-made templates + CSV import
    /** id, title, classpath resource. A template is just a CSV file in the import format (static/templates). */
    private static final String[][] TEMPLATES = {
            {"dryfruits", "Dry Fruit Palace — நட்ஸ், பேரீச்சை, உலர் பழம், விதை, பருப்பு, எண்ணெய், அரிசி, மசாலா, மளிகை", "/static/templates/dryfruits.csv"}};

    private static String templateCsv(String id) {
        for (String[] t : TEMPLATES) {
            if (!t[0].equals(id)) continue;
            try (java.io.InputStream is = BillingService.class.getResourceAsStream(t[2])) {
                if (is == null) throw new ApiException(500, "Template கோப்பு இல்லை");
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new ApiException(500, "Template படிக்க முடியவில்லை");
            }
        }
        throw new ApiException(400, "அந்த template இல்லை");
    }

    /** admin: the ready-made master data sets that can be loaded into a company */
    public Map<String, Object> templates(String auth) {
        admin(auth);
        List<Object> out = new ArrayList<>();
        for (String[] t : TEMPLATES) {
            CatalogImport.Result r = CatalogImport.merge(templateCsv(t[0]), null, true);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", t[0]);
            n.put("name", t[1]);
            n.put("groups", r.cats.size());
            n.put("items", r.items.size());
            out.add(n);
        }
        return obj("templates", out);
    }

    /** admin: load a template into one company (merge by name; replace = start from an empty catalog). stock = opening stock per size in every shop (0 = none). */
    public Map<String, Object> loadTemplate(String auth, String companyId, Map<String, Object> in) {
        ownCompany(admin(auth), companyId);
        if (db.one("SELECT id FROM companies WHERE id = ? AND archived = 0", companyId) == null) throw new ApiException(404, "கம்பெனி இல்லை");
        return applyImport(companyId, templateCsv(str(in.get("template")).trim()), bool(in.get("replace")), tplStock(in, "stock"), null, "template", false);
    }

    private static long tplStock(Map<String, Object> in, String key) {
        if (!in.containsKey(key) || in.get(key) == null || str(in.get(key)).isEmpty()) return 100;
        long q = lng(in.get(key));
        return Math.max(0, Math.min(100000, q));
    }

    /** used by saveCompany / saveUser: a template problem must not undo the company / user that was just saved */
    private Map<String, Object> tryTemplate(String cid, String tpl, Map<String, Object> in) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("template", applyImport(cid, templateCsv(tpl), bool(in.get("templateReplace")), tplStock(in, "templateStock"), null, "template", false));
        } catch (ApiException e) {
            m.put("templateError", e.getMessage());
        }
        return m;
    }

    /** admin / owner-less staff (admin, stock, warehouse): import items from CSV text into the company of the session */
    public Map<String, Object> importCatalog(String auth, Map<String, Object> in) {
        Ctx c = ctx(auth);
        String cid = requireBilling(c);
        if (c.sales()) throw new ApiException(403, "Sales user-க்கு import அனுமதி இல்லை");
        String csv = in.get("csv") instanceof String ? (String) in.get("csv") : "";
        if (csv.length() > 4_000_000) throw new ApiException(400, "CSV மிகப் பெரியது");
        String br = str(in.get("branchId"));
        if (br.isEmpty()) br = userBranch(c, cid);
        else if (branchRow(cid, br) == null) throw new ApiException(400, "கிளை தவறு");
        String by = str(c.user.get("full_name"));
        if (by.isEmpty()) by = str(c.user.get("username"));
        if (bool(in.get("pricesOnly"))) return applyImport(cid, csv, false, 0, null, by, true, true, bool(in.get("preview")));
        return applyImport(cid, csv, bool(in.get("replace")) && c.admin(), 0, br, by, true);
    }

    /**
     * Merges (or replaces) the catalog of one company with CSV rows, in one transaction.
     * Opening stock: the CSV "stock" column sets the stock of the rows at branchId; openingStock (> 0, templates) adds that qty per size to
     * every shop of the company where the item has no stock row yet.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyImport(String cid, String csv, boolean replace, long openingStock, String branchId, String by, boolean withCatalog) {
        return applyImport(cid, csv, replace, openingStock, branchId, by, withCatalog, false, false);
    }

    private Map<String, Object> applyImport(String cid, String csv, boolean replace, long openingStock, String branchId, String by, boolean withCatalog, boolean pricesOnly, boolean preview) {
        return db.tx(t -> {
            Map<String, Object> co = t.one("SELECT catalog_version FROM companies WHERE id = ? FOR UPDATE", cid);
            if (co == null) throw new ApiException(404, "கம்பெனி இல்லை");
            long version = lng(co.get("catalog_version"));
            CatalogImport.Result r = pricesOnly ? CatalogImport.mergePrices(csv, loadCatalog(t, cid, true)) : CatalogImport.merge(csv, replace ? null : loadCatalog(t, cid, true), replace);
            if (pricesOnly) {                                   // price list: only rate / wholesale / cost of existing sizes change
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("rows", r.rows);
                o.put("changed", r.changed);
                o.put("unchanged", r.unchanged);
                o.put("skipped", r.skipped);
                o.put("errors", new ArrayList<Object>(r.errors));
                o.put("changes", r.changes);
                o.put("preview", preview);
                if (!preview && r.changed > 0) {
                    Map<String, Object> cin = new LinkedHashMap<>();
                    cin.put("cats", r.cats);
                    cin.put("items", r.items);
                    CleanCatalog clean = cleanCatalog(cin);
                    t.update("DELETE FROM item_groups WHERE company_id = ?", cid);
                    t.batch("INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", clean.groups(cid));
                    t.batch("INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order, sold_by, wt_unit) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.items(cid));
                    t.batch("INSERT INTO item_sizes (company_id, item_id, size_label, rate, sort_order, barcode, cost, wrate) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.sizes(cid));
                    t.update("UPDATE companies SET catalog_version = ?, updated_at = ? WHERE id = ?", version + 1, now(), cid);
                }
                if (withCatalog && !preview) o.put("catalog", loadCatalog(t, cid, true));
                return o;
            }
            if (r.added + r.updated == 0)
                throw new ApiException(400, "இறக்குமதி செய்ய சரியான வரிகள் இல்லை" + (r.errors.isEmpty() ? "" : " — " + r.errors.get(0)));
            Map<String, Object> cin = new LinkedHashMap<>();
            cin.put("cats", r.cats);
            cin.put("items", r.items);
            CleanCatalog clean = cleanCatalog(cin);
            t.update("DELETE FROM item_groups WHERE company_id = ?", cid);   // cascades to items and item_sizes
            t.batch("INSERT INTO item_groups (company_id, id, name_ta, name_en, emoji, color, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?)", clean.groups(cid));
            t.batch("INSERT INTO items (company_id, id, group_id, name_ta, name_en, sort_order, sold_by, wt_unit) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.items(cid));
            t.batch("INSERT INTO item_sizes (company_id, item_id, size_label, rate, sort_order, barcode, cost, wrate) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", clean.sizes(cid));
            LocalDateTime now = now();
            t.update("UPDATE companies SET catalog_version = ?, catalog_setup = 1, updated_at = ? WHERE id = ?", version + 1, now, cid);

            Set<String> ok = new HashSet<>();
            for (Object[] z : clean.sizes) ok.add(z[0] + "|" + z[1]);
            int stockRows = 0;
            List<Object[]> setRows = new ArrayList<>(), addRows = new ArrayList<>();
            Set<String> explicit = new HashSet<>();
            if (branchId != null) {
                for (Object[] z : r.stock) {
                    String k = z[0] + "|" + z[1];
                    if (!ok.contains(k)) continue;
                    explicit.add(k);
                    setRows.add(new Object[]{cid, branchId, z[0], z[1], z[2], now, by, z[2], now, by});
                }
            }
            if (openingStock > 0) {
                List<String> shops = new ArrayList<>();
                for (Object b : branchList(cid, "shop")) shops.add(str(((Map<String, Object>) b).get("id")));
                for (Object[] z : r.touchedSizes) {
                    String k = z[0] + "|" + z[1];
                    if (!ok.contains(k) || explicit.contains(k)) continue;
                    for (String sb : shops) addRows.add(new Object[]{cid, sb, z[0], z[1], openingStock, now, by});
                }
            }
            if (!setRows.isEmpty())
                t.batch("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = ?, updated_at = ?, updated_by = ?", setRows);
            if (!addRows.isEmpty())
                t.batch("INSERT INTO stock (company_id, branch_id, item_id, size_label, qty, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE qty = qty", addRows);
            stockRows = setRows.size() + addRows.size();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", r.rows);
            out.put("added", r.added);
            out.put("updated", r.updated);
            out.put("skipped", r.skipped);
            out.put("stockRows", stockRows);
            out.put("errors", new ArrayList<Object>(r.errors));
            if (withCatalog) out.put("catalog", loadCatalog(t, cid, true));
            return out;
        });
    }

    // ================================================================ passwords / crypto
    private static String hash(String pass, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 120000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean checkPassword(Map<String, Object> u, String p) {
        try {
            byte[] salt = Base64.getDecoder().decode(str(u.get("pass_salt")));
            return MessageDigest.isEqual(hash(p, salt).getBytes(StandardCharsets.UTF_8), str(u.get("pass_hash")).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean dummyCheck(String p) {
        hash(p, new byte[16]);
        return false;
    }

    static void validPassword(String p) {
        if (p.length() < 6 || p.length() > 100) throw new ApiException(400, "கடவுச்சொல் குறைந்தது 6 எழுத்து இருக்க வேண்டும்");
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private String newId(String prefix) { return prefix + hex(bytes(6)); }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static String sha(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ helpers
    private LocalDateTime now() { return LocalDateTime.now(zone).truncatedTo(ChronoUnit.SECONDS); }

    private String iso(LocalDateTime t) { return t == null ? "" : t.atZone(zone).toOffsetDateTime().format(ISO); }

    static LocalDateTime ldt(Object o) {
        if (o instanceof LocalDateTime) return (LocalDateTime) o;
        if (o instanceof Timestamp) return ((Timestamp) o).toLocalDateTime();
        if (o instanceof String && !((String) o).isEmpty()) return LocalDateTime.parse(((String) o).replace(' ', 'T'));
        return null;
    }

    static Object[] concat(Object[] a, Object... b) {
        Object[] r = new Object[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    static String str(Object o) { return o == null ? "" : o.toString(); }

    static String nz(Object o) { return o == null ? null : o.toString(); }

    static String rawStr(Object o) {
        String s = o instanceof String ? (String) o : "";
        if (s.length() > 200) throw new ApiException(400, "Too long");
        return s;
    }

    /** trimmed, control characters removed, at most max code points */
    static String clip(Object o, int max) {
        String s = o instanceof String || o instanceof Number ? o.toString() : "";
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replace('\n', ' ').trim();
        if (s.codePointCount(0, s.length()) > max) s = s.substring(0, s.offsetByCodePoints(0, max)).trim();
        return s;
    }

    static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return "1".equals(str(o)) || "true".equalsIgnoreCase(str(o));
    }

    static long lng(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return 0;
        try { return Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { throw new ApiException(400, "Number expected"); }
    }

    static double dbl(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) { try { return Double.parseDouble(((String) o).trim()); } catch (NumberFormatException e) { return Double.NaN; } }
        return Double.NaN;
    }

    static double round2(double d) { return Math.round(d * 100) / 100.0; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) throw new ApiException(400, "Invalid data");
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    static Map<String, Object> obj(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
