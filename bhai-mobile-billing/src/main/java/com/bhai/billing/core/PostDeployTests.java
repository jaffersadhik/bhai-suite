package com.bhai.billing.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-deploy (live, post-production) tests.
 * Runs after every start-up (= every deploy) and on demand from the admin screen. Read-only: it never changes shop data.
 *   1. schema   : every table of schema.sql exists
 *   2. web      : the pages and scripts are served over HTTP
 *   3. api      : every role of every company calls the read APIs (real HTTP, short-lived test login);
 *                 no server error, permission rules hold, and the status is the same as in the previous build
 *   4. data     : money / stock ledgers are consistent; record counts did not go down since the previous run
 * Results are kept (test_runs / test_results). A failure is shown to the admin as an alert, with the option to roll the build back.
 */
public class PostDeployTests {
    private static final Logger log = LoggerFactory.getLogger(PostDeployTests.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final Db db;
    private final ZoneId zone;
    private final String build;
    private final Path ctl;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int port = envPort();

    /** WAR in Tomcat: the app does not know its port - BMB_SELFTEST_PORT (default 8080, the Tomcat connector of this app) */
    private static int envPort() {
        try { String v = System.getenv("BMB_SELFTEST_PORT"); return v == null || v.isBlank() ? 8080 : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return 8080; }
    }

    public PostDeployTests(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
        this.build = buildId();
        String c = System.getenv("BMB_CTL");
        this.ctl = Path.of(c == null || c.isBlank() ? "/ctl" : c);
    }

    public String build() { return build; }

    public void setPort(int port) { this.port = port; }

    public boolean isRunning() { return running.get(); }

    private static String buildId() {
        String b = System.getenv("BMB_BUILD");
        if (b != null && !b.isBlank()) return b.trim();
        try {        // local / dev run: time of the compiled code
            Path p = Path.of(PostDeployTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Instant t = Files.getLastModifiedTime(p.toFile().isDirectory() ? p.resolve("com/bhai/billing/core/PostDeployTests.class") : p).toInstant();
            return "dev-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Asia/Kolkata")).format(t);
        } catch (Exception e) {
            return "dev";
        }
    }

    // ------------------------------------------------------------------ run
    private record Res(String code, String cat, String name, String status, String detail, Double metric, long ms) {}

    /** starts a run in the background; false when one is already running */
    public boolean startAsync(String trigger) {
        if (!running.compareAndSet(false, true)) return false;
        Thread t = new Thread(() -> {
            try { run(trigger); } catch (Exception e) { log.error("Post-deploy tests failed to run", e); } finally { running.set(false); }
        }, "post-deploy-tests");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Tomcat starts its HTTP connector only after all webapps are deployed, so wait (max 3 min) until /api/ping answers */
    private void waitUntilUp() {
        long end = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < end) {
            try { if (get("/api/ping", null).statusCode() == 200) return; } catch (Exception ignore) { /* not up yet */ }
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void run(String trigger) {
        waitUntilUp();
        LocalDateTime start = LocalDateTime.now(zone);
        long runId = db.insert("INSERT INTO test_runs (build, trigger_by, started_at, status) VALUES (?, ?, ?, 'running')", build, trigger, start);
        // previous results to compare with: the last finished run of an older build (else the last finished run)
        Map<String, Object> prev = db.one("SELECT id, build FROM test_runs WHERE status = 'done' AND build <> ? ORDER BY id DESC LIMIT 1", build);
        if (prev == null) prev = db.one("SELECT id, build FROM test_runs WHERE status = 'done' AND id < ? ORDER BY id DESC LIMIT 1", runId);
        Map<String, Map<String, Object>> before = new HashMap<>();
        Map<String, Object> lastRun = db.one("SELECT id FROM test_runs WHERE status = 'done' AND id < ? ORDER BY id DESC LIMIT 1", runId);
        Map<String, Map<String, Object>> lastVals = new HashMap<>();
        if (prev != null) for (Map<String, Object> r : db.query("SELECT code, status, detail, metric FROM test_results WHERE run_id = ?", prev.get("id"))) before.put(s(r.get("code")), r);
        if (lastRun != null) for (Map<String, Object> r : db.query("SELECT code, metric FROM test_results WHERE run_id = ?", lastRun.get("id"))) lastVals.put(s(r.get("code")), r);

        List<Res> out = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        try {
            schema(out);
            web(out);
            api(out, before, tokens);
            data(out, lastVals);
        } catch (Exception e) {
            out.add(new Res("runner", "runner", "Test runner", "fail", "Test இயக்கத்தில் பிழை: " + e, null, 0));
        } finally {
            for (String h : tokens) try { db.update("DELETE FROM auth_tokens WHERE token_hash = ?", h); } catch (Exception ignore) { /* best effort */ }
        }
        int pass = 0, fail = 0, warn = 0, reg = 0;
        List<Object[]> rows = new ArrayList<>();
        for (Res r : out) {
            Map<String, Object> b = before.get(r.code());
            String ps = b == null ? null : s(b.get("status"));
            boolean regression = "fail".equals(r.status()) && "pass".equals(ps);
            if ("pass".equals(r.status())) pass++; else if ("fail".equals(r.status())) fail++; else warn++;
            if (regression) reg++;
            rows.add(new Object[]{runId, r.code(), r.cat(), clip(r.name(), 160), r.status(), clip(r.detail(), 500), r.metric(), r.ms(), ps, regression});
        }
        if (!rows.isEmpty())
            db.batch("INSERT INTO test_results (run_id, code, category, name, status, detail, metric, ms, prev_status, regression) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        db.update("UPDATE test_runs SET finished_at = ?, status = 'done', total = ?, passed = ?, failed = ?, warned = ?, regressions = ?, prev_build = ? WHERE id = ?",
                LocalDateTime.now(zone), out.size(), pass, fail, warn, reg, prev == null ? null : s(prev.get("build")), runId);
        db.update("DELETE FROM test_runs WHERE id < ? AND id NOT IN (SELECT id FROM (SELECT id FROM test_runs ORDER BY id DESC LIMIT 60) k)", runId);
        log.info("MBill post-deploy tests (build {}): {} pass, {} fail, {} warn, {} regressions", build, pass, fail, warn, reg);
    }

    // ------------------------------------------------------------------ 1. schema
    private void schema(List<Res> out) throws IOException {
        long t = System.currentTimeMillis();
        String sql;
        try (InputStream in = PostDeployTests.class.getResourceAsStream("/db/schema.sql")) {
            sql = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE).matcher(sql);
        Set<String> want = new LinkedHashSet<>();
        while (m.find()) want.add(m.group(1).toLowerCase(Locale.ROOT));
        Set<String> have = new LinkedHashSet<>();
        for (Map<String, Object> r : db.query("SELECT LOWER(table_name) AS t FROM information_schema.tables WHERE table_schema = DATABASE()")) have.add(s(r.get("t")));
        List<String> miss = new ArrayList<>();
        for (String w : want) if (!have.contains(w)) miss.add(w);
        out.add(new Res("schema.tables", "schema", "Database: எல்லா table-களும் உள்ளன (" + want.size() + ")",
                miss.isEmpty() ? "pass" : "fail", miss.isEmpty() ? want.size() + " tables OK" : "இல்லாதவை: " + String.join(", ", miss), (double) want.size(), System.currentTimeMillis() - t));
    }

    // ------------------------------------------------------------------ 2. web
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30)).GET();
        if (token != null) b.header("X-Auth", token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void web(List<Res> out) {
        String[][] pages = {
                {"/", "MBill", "முகப்பு பக்கம் (App)"},
                {"/shop.html", "<html", "Online shop பக்கம்"},
                {"/s/dry-fruit-palace", "<base href", "Company website address (/s/<name>)"},
                {"/vendor/zxing.min.js", "", "Barcode scanner script"},
                {"/vendor/jspdf.umd.min.js", "", "PDF script (jsPDF)"},
                {"/vendor/html2canvas.min.js", "", "PDF script (html2canvas)"},
                {"/api/ping", "\"ok\":true", "API ping"}};
        for (String[] p : pages) {
            long t = System.currentTimeMillis();
            String st, det;
            try {
                HttpResponse<String> r = get(p[0], null);
                boolean ok = r.statusCode() == 200 && (p[1].isEmpty() || r.body().contains(p[1]));
                st = ok ? "pass" : "fail";
                det = "HTTP " + r.statusCode() + " · " + r.body().length() + " bytes";
            } catch (Exception e) {
                st = "fail";
                det = "அணுக முடியவில்லை: " + e.getMessage();
            }
            out.add(new Res("web" + p[0], "web", p[2] + " — " + p[0], st, det, null, System.currentTimeMillis() - t));
        }
        // public shop pages of the companies that have the online shop on
        for (Map<String, Object> c : db.query("SELECT id, name, shop_code, shop_slug FROM companies WHERE shop_enabled = 1 AND shop_code IS NOT NULL AND shop_code <> '' AND archived = 0 ORDER BY name")) {
            long t = System.currentTimeMillis();
            String code = s(c.get("shop_slug")).isEmpty() ? s(c.get("shop_code")) : s(c.get("shop_slug")), st, det;
            try {
                HttpResponse<String> r = get("/api/shop/" + code, null);
                boolean ok = r.statusCode() == 200 && r.body().contains("\"items\"");
                st = ok ? "pass" : "fail";
                det = "HTTP " + r.statusCode();
            } catch (Exception e) { st = "fail"; det = e.getMessage(); }
            out.add(new Res("web.shop." + c.get("id"), "web", "Online shop: " + s(c.get("name")) + " (" + code + ")", st, det, null, System.currentTimeMillis() - t));
        }
    }

    // ------------------------------------------------------------------ 3. api, per role
    /** read endpoints called for every role. Rules: never 5xx; where a rule is given, the status must match it. */
    private static final String[] PATHS = {"/api/data", "/api/me", "/api/bills", "/api/report", "/api/stock", "/api/stock/report", "/api/po", "/api/suppliers",
            "/api/transfers", "/api/pending", "/api/cancel-bills", "/api/orders", "/api/parties", "/api/receivables", "/api/payables", "/api/invoices", "/api/shop-settings"};
    /** fixed permission rules: role|path -> expected HTTP status */
    private static final Map<String, Integer> RULES = Map.ofEntries(
            Map.entry("sales|/api/report", 403), Map.entry("sales|/api/parties", 403), Map.entry("sales|/api/receivables", 403),
            Map.entry("warehouse|/api/bills", 403), Map.entry("owner|/api/bills", 403),
            Map.entry("sales|/api/data", 200), Map.entry("stock|/api/data", 200), Map.entry("warehouse|/api/data", 200),
            Map.entry("sales|/api/bills", 200), Map.entry("stock|/api/bills", 200), Map.entry("warehouse|/api/parties", 200), Map.entry("owner|/api/report", 200));

    private static final SecureRandom RND = new SecureRandom();

    private String token(String userId, String companyId, List<String> tokens) {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        String raw = BillingService.hex(b), h = BillingService.sha(raw);
        LocalDateTime now = LocalDateTime.now(zone);
        db.update("INSERT INTO auth_tokens (token_hash, user_id, company_id, expires_at, created_at) VALUES (?, ?, ?, ?, ?)", h, userId, companyId, now.plusMinutes(15), now);
        tokens.add(h);
        return raw;
    }

    private void api(List<Res> out, Map<String, Map<String, Object>> before, List<String> tokens) {
        // admin-wide screens
        Map<String, Object> admin = db.one("SELECT id, user_type FROM users WHERE user_type IN ('system', 'admin') AND active = 1 ORDER BY user_type = 'system' DESC, created_at LIMIT 1");   // System user first; it works without any plain admin
        if (admin == null) { out.add(new Res("api.admin", "api", "Admin கணக்கு", "fail", "Active System / Admin இல்லை", null, 0)); return; }
        String at = token(s(admin.get("id")), null, tokens);
        for (String p : "system".equals(s(admin.get("user_type"))) ? new String[]{"/api/admin/overview", "/api/admin/invoices", "/api/me"} : new String[]{"/api/admin/overview", "/api/me"}) call(out, before, "api.admin" + p, "Admin · " + p, p, at, 200);

        for (Map<String, Object> c : db.query("SELECT id, name FROM companies ORDER BY name")) {
            String cid = s(c.get("id")), cn = s(c.get("name"));
            // admin inside the company
            String act = token(s(admin.get("id")), cid, tokens);
            for (String p : new String[]{"/api/data", "/api/report", "/api/stock/report", "/api/parties", "/api/payables", "/api/receivables"})
                call(out, before, "api." + cid + ".admin" + p, cn + " · admin · " + p, p, act, null);
            // one active user of every role
            for (Map<String, Object> u : db.query("SELECT u.id, u.user_type, u.username FROM users u JOIN (SELECT user_type, MIN(username) AS un FROM users "
                    + "WHERE company_id = ? AND active = 1 GROUP BY user_type) f ON f.user_type = u.user_type AND f.un = u.username WHERE u.company_id = ?", cid, cid)) {
                String role = s(u.get("user_type")), tk = token(s(u.get("id")), null, tokens);
                for (String p : PATHS)
                    call(out, before, "api." + cid + "." + role + p, cn + " · " + role + " (" + s(u.get("username")) + ") · " + p, p, tk, RULES.get(role + "|" + p));
            }
        }
    }

    private void call(List<Res> out, Map<String, Map<String, Object>> before, String code, String name, String path, String token, Integer expect) {
        long t = System.currentTimeMillis();
        int sc;
        String body;
        try {
            HttpResponse<String> r = get(path, token);
            sc = r.statusCode();
            body = r.body();
        } catch (Exception e) {
            out.add(new Res(code, "api", name, "fail", "அணுக முடியவில்லை: " + e.getMessage(), null, System.currentTimeMillis() - t));
            return;
        }
        long ms = System.currentTimeMillis() - t;
        Map<String, Object> b = before.get(code);
        Integer was = b == null || b.get("metric") == null ? null : ((Number) b.get("metric")).intValue();
        String st = "pass", det = "HTTP " + sc + " · " + ms + " ms";
        if (sc >= 500) { st = "fail"; det = "சர்வர் பிழை HTTP " + sc + " — " + clip(body, 160); }
        else if (sc == 401) { st = "fail"; det = "Login ஏற்கப்படவில்லை (HTTP 401)"; }
        else if (expect != null && sc != expect) { st = "fail"; det = "அனுமதி விதி மீறல்: எதிர்பார்த்தது HTTP " + expect + ", வந்தது HTTP " + sc; }
        else if (was != null && was != sc) { st = "fail"; det = "முந்தைய build-ல் HTTP " + was + ", இப்போது HTTP " + sc + " — நடத்தை மாறியுள்ளது"; }
        else if (sc == 200 && !(body.startsWith("{") || body.startsWith("["))) { st = "fail"; det = "JSON இல்லை"; }
        else if (ms > 5000) { st = "warn"; det = "மெதுவாக உள்ளது: " + ms + " ms"; }
        out.add(new Res(code, "api", name, st, det, (double) sc, ms));
    }

    // ------------------------------------------------------------------ 4. data
    private void data(List<Res> out, Map<String, Map<String, Object>> lastVals) {
        check(out, "data.inv.paid", "Wholesale invoice: செலுத்தியது = பணம் வரவு பதிவுகளின் கூட்டு",
                "SELECT COUNT(*) AS n FROM bills b LEFT JOIN (SELECT bill_id, SUM(amount) AS s FROM party_payments GROUP BY bill_id) p ON p.bill_id = b.id "
                        + "WHERE b.bill_kind = 'invoice' AND ABS(b.paid_amount - COALESCE(p.s, 0)) > 0.01", "invoice-களில் வேறுபாடு");
        check(out, "data.inv.over", "Wholesale invoice: செலுத்தியது ≤ invoice தொகை",
                "SELECT COUNT(*) AS n FROM bills WHERE bill_kind = 'invoice' AND paid_amount > total + 0.01", "invoice-களில் அதிகம்");
        check(out, "data.bill.lines", "பில் தொகை = ஐட்டங்களின் கூட்டு (+GST)",
                "SELECT COUNT(*) AS n FROM bills b JOIN (SELECT bill_id, SUM(amount) AS s FROM bill_lines GROUP BY bill_id) l ON l.bill_id = b.id "
                        + "WHERE ABS(b.total - l.s - b.gst_amount) > 1", "பில்களில் வேறுபாடு");
        check(out, "data.bill.nolines", "ஐட்டம் இல்லாத பில் இல்லை",
                "SELECT COUNT(*) AS n FROM bills b WHERE NOT EXISTS (SELECT 1 FROM bill_lines l WHERE l.bill_id = b.id)", "பில்கள் ஐட்டம் இல்லாமல்");
        check(out, "data.po.paid", "PO: செலுத்தியது ≤ PO தொகை",
                "SELECT COUNT(*) AS n FROM purchase_orders WHERE paid > total + 0.01", "PO-க்களில் அதிகம்");
        check(out, "data.bill.dup", "பில் எண் இரட்டிப்பு இல்லை",
                "SELECT COUNT(*) AS n FROM (SELECT company_id, bill_no FROM bills GROUP BY company_id, bill_no HAVING COUNT(*) > 1) d", "இரட்டிப்பு பில் எண்கள்");
        check(out, "data.users.admin", "குறைந்தது ஒரு active System / admin",
                "SELECT CASE WHEN COUNT(*) > 0 THEN 0 ELSE 1 END AS n FROM users WHERE user_type IN ('system', 'admin') AND active = 1", "System / admin இல்லை");
        // record counts must not go down (data loss after a deploy / migration)
        String[][] counts = {{"companies", "நிறுவனங்கள்"}, {"users", "பயனர்கள்"}, {"bills", "பில்கள்"}, {"cancel_bills", "ரத்து பில்கள்"},
                {"party_payments", "Retailer பணம் வரவு"}, {"purchase_orders", "Purchase orders"}, {"platform_invoices", "Software invoices"}};
        for (String[] c : counts) {
            long t = System.currentTimeMillis();
            String code = "data.count." + c[0];
            try {
                long n = ((Number) db.one("SELECT COUNT(*) AS n FROM " + c[0]).get("n")).longValue();
                Map<String, Object> was = lastVals.get(code);
                Long w = was == null || was.get("metric") == null ? null : ((Number) was.get("metric")).longValue();
                boolean down = w != null && n < w;
                out.add(new Res(code, "data", "பதிவுகள் குறையவில்லை: " + c[1], down ? (c[0].equals("companies") || c[0].equals("users") ? "fail" : "warn") : "pass",
                        (w == null ? "" : "முன்பு " + w + " → ") + "இப்போது " + n + (down ? " — குறைந்துள்ளது! (Demo மீண்டும் ஏற்றியிருந்தால் சரி)" : ""), (double) n, System.currentTimeMillis() - t));
            } catch (Exception e) {
                out.add(new Res(code, "data", "பதிவுகள்: " + c[1], "fail", e.getMessage(), null, 0));
            }
        }
    }

    private void check(List<Res> out, String code, String name, String sql, String what) {
        long t = System.currentTimeMillis();
        try {
            long n = ((Number) db.one(sql).get("n")).longValue();
            out.add(new Res(code, "data", name, n == 0 ? "pass" : "fail", n == 0 ? "சரி" : n + " " + what, (double) n, System.currentTimeMillis() - t));
        } catch (Exception e) {
            out.add(new Res(code, "data", name, "fail", "Query பிழை: " + e.getMessage(), null, System.currentTimeMillis() - t));
        }
    }

    // ------------------------------------------------------------------ admin view + rollback
    public Map<String, Object> overview(Long runId) {
        List<Object> runs = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM test_runs ORDER BY id DESC LIMIT 30")) runs.add(runRow(r));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("build", build);
        o.put("running", running.get());
        o.put("runs", runs);
        Map<String, Object> sel = runId != null ? db.one("SELECT * FROM test_runs WHERE id = ?", runId)
                : db.one("SELECT * FROM test_runs WHERE status = 'done' ORDER BY id DESC LIMIT 1");
        if (sel != null) {
            o.put("run", runRow(sel));
            List<Object> res = new ArrayList<>();
            for (Map<String, Object> r : db.query("SELECT * FROM test_results WHERE run_id = ? ORDER BY FIELD(status, 'fail', 'warn', 'pass'), regression DESC, category, code", sel.get("id"))) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("code", s(r.get("code")));
                n.put("cat", s(r.get("category")));
                n.put("name", s(r.get("name")));
                n.put("status", s(r.get("status")));
                n.put("detail", s(r.get("detail")));
                n.put("prev", r.get("prev_status") == null ? null : s(r.get("prev_status")));
                Object rg = r.get("regression");
                n.put("regression", rg instanceof Boolean b ? b : rg instanceof Number x && x.intValue() == 1);
                n.put("ms", r.get("ms"));
                res.add(n);
            }
            o.put("results", res);
        }
        o.put("deploy", deployInfo());
        return o;
    }

    /** alert for the admin home: latest finished run with failures */
    public Map<String, Object> alert() {
        Map<String, Object> r = db.one("SELECT * FROM test_runs WHERE status = 'done' ORDER BY id DESC LIMIT 1");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("build", build);
        o.put("running", running.get());
        if (r != null) o.put("last", runRow(r));
        return o;
    }

    private Map<String, Object> runRow(Map<String, Object> r) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", r.get("id"));
        n.put("build", s(r.get("build")));
        n.put("prevBuild", r.get("prev_build") == null ? null : s(r.get("prev_build")));
        n.put("trigger", s(r.get("trigger_by")));
        n.put("at", s(r.get("started_at")).replace(' ', 'T'));
        n.put("status", s(r.get("status")));
        for (String k : new String[]{"total", "passed", "failed", "warned", "regressions"}) n.put(k, r.get(k) == null ? 0 : ((Number) r.get(k)).intValue());
        return n;
    }

    /** written by deploy/oracle/update.sh and ctl-watch.sh on the server (folder shared with the container) */
    public Map<String, Object> deployInfo() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ctl", Files.isDirectory(ctl) && Files.isWritable(ctl));
        o.put("info", readJson(ctl.resolve("build.json")));
        o.put("rollback", readJson(ctl.resolve("rollback.status")));
        o.put("requested", Files.exists(ctl.resolve("rollback.request")));
        return o;
    }

    public Map<String, Object> requestRollback(String by, boolean withDb) {
        if (!Files.isDirectory(ctl) || !Files.isWritable(ctl))
            throw new ApiException(400, "Rollback வசதி இந்த server-ல் அமைக்கப்படவில்லை (deploy/oracle/setup.sh மீண்டும் இயக்கவும்)");
        Object info = readJson(ctl.resolve("build.json"));
        if (!(info instanceof Map<?, ?> m) || m.get("previous") == null || String.valueOf(m.get("previous")).isBlank())
            throw new ApiException(400, "முந்தைய build இல்லை — rollback செய்ய முடியாது");
        if (Files.exists(ctl.resolve("rollback.request"))) throw new ApiException(409, "Rollback ஏற்கனவே கோரப்பட்டுள்ளது — ஒரு நிமிடத்தில் நடக்கும்");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("by", by);
        req.put("at", LocalDateTime.now(zone).toString());
        req.put("from", build);
        req.put("to", m.get("previous"));
        req.put("withDb", withDb);
        try {
            Files.writeString(ctl.resolve("rollback.request"), JSON.writeValueAsString(req), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ApiException(500, "Rollback கோரிக்கையை எழுத முடியவில்லை: " + e.getMessage());
        }
        log.warn("MBill: rollback requested by {} ({} -> {}, withDb={})", by, build, m.get("previous"), withDb);
        return deployInfo();
    }

    private static Object readJson(Path p) {
        try {
            return Files.exists(p) ? JSON.readValue(Files.readString(p, StandardCharsets.UTF_8), Object.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }

    private static String clip(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }
}
