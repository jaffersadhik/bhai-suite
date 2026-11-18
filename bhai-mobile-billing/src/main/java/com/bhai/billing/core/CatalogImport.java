package com.bhai.billing.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV / template import of groups, items and sizes. Pure functions (no database): the CSV text and the company's
 * current catalog go in, a new catalog in the same shape as BillingService.loadCatalog comes out
 * (cats [{id, ta, en, emoji, color}], items [{id, cat, ta, en, sizes [{size, rate, wrate, cost, code}]}]),
 * which BillingService then validates with its normal cleanCatalog() and saves.
 *
 * CSV columns (header row, any order; comma / tab / semicolon): group, group_en, emoji, color, item, item_en, size,
 * rate, wholesale, cost, barcode, stock, sold_by (count|weight), wt_unit (kg|g; weight items: rate per unit, stock in that unit).  Tamil / English aliases are accepted (see ALIAS).
 * Existing groups / items / sizes are matched by name, so loading the same file twice does not create duplicates.
 */
final class CatalogImport {
    private CatalogImport() {}

    static final int MAX_ROWS = 5000;
    static final int MAX_ERRORS = 15;

    static final class Result {
        final List<Object> cats = new ArrayList<>();
        final List<Object> items = new ArrayList<>();
        /** itemId, size, qty - from the "stock" column */
        final List<Object[]> stock = new ArrayList<>();
        /** itemId, size of every CSV row that was accepted (used for the opening stock of a template) */
        final List<Object[]> touchedSizes = new ArrayList<>();
        int rows, added, updated, skipped;
        /** price-list mode: sizes whose rate / wholesale / cost really changed, and those that already had the same prices */
        int changed, unchanged;
        /** price-list mode: first changes for the preview [item, size, field, old, new] */
        final List<Object> changes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        for (String s : new String[]{"item_id", "id"}) ALIAS.put(s, "item_id");
        for (String s : new String[]{"group", "category", "cat", "group_ta", "group_name", "குரூப்", "வகை"}) ALIAS.put(s, "group");
        for (String s : new String[]{"group_en", "category_en", "cat_en"}) ALIAS.put(s, "group_en");
        for (String s : new String[]{"emoji", "icon"}) ALIAS.put(s, "emoji");
        for (String s : new String[]{"color", "colour"}) ALIAS.put(s, "color");
        for (String s : new String[]{"item", "name", "item_ta", "item_name", "product", "ஐட்டம்", "பெயர்"}) ALIAS.put(s, "item");
        for (String s : new String[]{"item_en", "name_en", "product_en", "english"}) ALIAS.put(s, "item_en");
        for (String s : new String[]{"size", "pack", "unit", "weight", "சைஸ்"}) ALIAS.put(s, "size");   // (weight items: size is ignored)
        for (String s : new String[]{"rate", "price", "mrp", "selling_price", "retail", "விலை"}) ALIAS.put(s, "rate");
        for (String s : new String[]{"wholesale", "wrate", "wholesale_rate", "wholesale_price"}) ALIAS.put(s, "wholesale");
        for (String s : new String[]{"cost", "cost_price", "purchase", "purchase_price"}) ALIAS.put(s, "cost");
        for (String s : new String[]{"barcode", "code", "ean", "sku"}) ALIAS.put(s, "barcode");
        for (String s : new String[]{"stock", "qty", "quantity", "opening_stock"}) ALIAS.put(s, "stock");
        for (String s : new String[]{"sold_by", "by", "type", "item_type", "முறை"}) ALIAS.put(s, "sold_by");          // count (default) | weight
        for (String s : new String[]{"wt_unit", "weight_unit", "measure", "அளவு"}) ALIAS.put(s, "wt_unit");          // weight items: kg | g
    }

    // ------------------------------------------------------------------ CSV
    /** RFC-4180-ish: quotes, doubled quotes, newlines inside quotes. The delimiter is guessed from the first line. */
    static List<List<String>> parseCsv(String text) {
        if (text == null) return new ArrayList<>();
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        char d = ',';
        int best = count(first, ',');
        if (count(first, '\t') > best) { d = '\t'; best = count(first, '\t'); }
        if (count(first, ';') > best) d = ';';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false;
                } else sb.append(c);
            } else if (c == '"' && sb.length() == 0) q = true;
            else if (c == d) { row.add(sb.toString()); sb.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(sb.toString()); sb.setLength(0);
                if (!(row.size() == 1 && row.get(0).trim().isEmpty())) out.add(row);
                row = new ArrayList<>();
            } else sb.append(c);
        }
        if (sb.length() > 0 || !row.isEmpty()) {
            row.add(sb.toString());
            if (!(row.size() == 1 && row.get(0).trim().isEmpty())) out.add(row);
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // ------------------------------------------------------------------ merge
    /**
     * @param existing the company's current catalog (loadCatalog shape, with cost) - ignored when replace = true
     */
    @SuppressWarnings("unchecked")
    static Result merge(String csv, Map<String, Object> existing, boolean replace) {
        Result res = new Result();
        List<List<String>> table = parseCsv(csv);
        if (table.isEmpty()) throw new ApiException(400, "CSV காலியாக உள்ளது");
        Map<String, Integer> col = new HashMap<>();
        List<String> head = table.get(0);
        for (int i = 0; i < head.size(); i++) {
            String h = head.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            String k = ALIAS.get(h);
            if (k != null && !col.containsKey(k)) col.put(k, i);
        }
        if (!col.containsKey("item") || !col.containsKey("rate"))
            throw new ApiException(400, "CSV-ல் 'item' மற்றும் 'rate' நெடுவரிசை (header) தேவை. Sample CSV-ஐ பதிவிறக்கவும்");
        if (table.size() - 1 > MAX_ROWS) throw new ApiException(400, "ஒரு CSV-ல் அதிகபட்சம் " + MAX_ROWS + " வரிகள்");

        // working copies of the existing catalog (maps are mutable copies)
        Map<String, Map<String, Object>> groupByName = new LinkedHashMap<>();   // norm(ta) -> group
        Map<String, Map<String, Object>> groupByEn = new HashMap<>();
        Map<String, Map<String, Object>> itemByKey = new LinkedHashMap<>();     // groupId|norm(ta) -> item
        Set<String> usedIds = new java.util.HashSet<>();
        if (!replace && existing != null) {
            for (Object o : (List<Object>) existing.getOrDefault("cats", new ArrayList<>())) {
                Map<String, Object> g = new LinkedHashMap<>((Map<String, Object>) o);
                res.cats.add(g);
                groupByName.put(norm(g.get("ta")), g);
                if (!norm(g.get("en")).isEmpty()) groupByEn.put(norm(g.get("en")), g);
                usedIds.add("g:" + g.get("id"));
            }
            for (Object o : (List<Object>) existing.getOrDefault("items", new ArrayList<>())) {
                Map<String, Object> x = new LinkedHashMap<>((Map<String, Object>) o);
                List<Object> sz = new ArrayList<>();
                for (Object so : (List<Object>) x.getOrDefault("sizes", new ArrayList<>())) sz.add(new LinkedHashMap<>((Map<String, Object>) so));
                x.put("sizes", sz);
                res.items.add(x);
                itemByKey.put(x.get("cat") + "|" + norm(x.get("ta")), x);
                usedIds.add("i:" + x.get("id"));
            }
        }
        java.util.Set<String> touched = new java.util.HashSet<>();   // items added / updated by this file (counted once)
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            int line = r + 1;
            res.rows++;
            String gTa = cell(row, col, "group"), gEn = cell(row, col, "group_en");
            String name = cell(row, col, "item"), nameEn = cell(row, col, "item_en");
            String size = cell(row, col, "size");
            String soldBy = cell(row, col, "sold_by").toLowerCase(Locale.ROOT), wtUnit = cell(row, col, "wt_unit").toLowerCase(Locale.ROOT);
            boolean byWt = soldBy.startsWith("w") || soldBy.equals("எடை");
            if (!soldBy.isEmpty() && !byWt && !(soldBy.startsWith("c") || soldBy.equals("எண்ணிக்கை"))) { res.skipped++; err(res, line, name + ": sold_by 'count' அல்லது 'weight' ஆக இருக்க வேண்டும்"); continue; }
            if (byWt) {
                if (wtUnit.isEmpty()) wtUnit = "kg";
                if (!wtUnit.equals("kg") && !wtUnit.equals("g")) { res.skipped++; err(res, line, name + ": wt_unit 'kg' அல்லது 'g'"); continue; }
                size = "1 " + wtUnit;                       // weight item: one size = the price of 1 kg / 1 g
            }
            if (name.isEmpty()) { res.skipped++; err(res, line, "ஐட்டம் பெயர் இல்லை"); continue; }
            if (size.isEmpty()) size = "1 pc";
            if (size.length() > 12 || size.indexOf('|') >= 0) { res.skipped++; err(res, line, "Size '" + shorten(size) + "' 12 எழுத்துக்குள் இருக்க வேண்டும்"); continue; }
            if (name.codePointCount(0, name.length()) > 60) { res.skipped++; err(res, line, "ஐட்டம் பெயர் 60 எழுத்துக்குள்"); continue; }
            Double rate = money(cell(row, col, "rate"));
            if (rate == null || !(rate > 0 && rate <= 999999)) { res.skipped++; err(res, line, name + ": rate தவறு"); continue; }
            Double wr = null, cost = null;
            String ws = cell(row, col, "wholesale"), cs = cell(row, col, "cost");
            if (!ws.isEmpty()) { wr = money(ws); if (wr == null || !(wr > 0 && wr <= 999999)) { res.skipped++; err(res, line, name + ": wholesale தவறு"); continue; } }
            if (!cs.isEmpty()) { cost = money(cs); if (cost == null || !(cost >= 0 && cost <= 999999)) { res.skipped++; err(res, line, name + ": cost தவறு"); continue; } }
            Long qty = null;
            String qs = cell(row, col, "stock");
            if (!qs.isEmpty()) {
                Double qd = money(qs);
                if (qd == null || qd < 0 || qd > 9999999 || (!byWt && qd != Math.floor(qd))) { res.skipped++; err(res, line, name + ": stock தவறு"); continue; }
                qty = byWt ? Math.round(qd * (wtUnit.equals("kg") ? 1000 : 1)) : qd.longValue();   // weight stock is kept in grams
            }

            // group
            if (gTa.isEmpty()) gTa = gEn.isEmpty() ? "மற்றவை" : gEn;
            if (gTa.codePointCount(0, gTa.length()) > 30) gTa = gTa.substring(0, gTa.offsetByCodePoints(0, 30)).trim();
            Map<String, Object> g = groupByName.get(norm(gTa));
            if (g == null && !gEn.isEmpty()) g = groupByEn.get(norm(gEn));
            if (g == null) {
                g = new LinkedHashMap<>();
                g.put("id", freeId("g", usedIds, norm(gTa)));
                g.put("ta", gTa);
                g.put("en", gEn.codePointCount(0, gEn.length()) > 30 ? gEn.substring(0, gEn.offsetByCodePoints(0, 30)) : gEn);
                String em = cell(row, col, "emoji"), colr = cell(row, col, "color");
                g.put("emoji", em.isEmpty() ? "🛍️" : em);
                g.put("color", colr.matches("#[0-9a-fA-F]{6}") ? colr : "#e8eef0");
                res.cats.add(g);
                groupByName.put(norm(gTa), g);
                if (!gEn.isEmpty()) groupByEn.put(norm(gEn), g);
            }
            String gid = String.valueOf(g.get("id"));
            // item
            String key = gid + "|" + norm(name);
            Map<String, Object> x = itemByKey.get(key);
            boolean fresh = false;
            if (x == null) {
                fresh = true;
                x = new LinkedHashMap<>();
                x.put("id", freeId("i", usedIds, gid + "|" + norm(name)));
                x.put("cat", gid);
                x.put("ta", name);
                x.put("en", nameEn.codePointCount(0, nameEn.length()) > 60 ? nameEn.substring(0, nameEn.offsetByCodePoints(0, 60)) : nameEn);
                x.put("sizes", new ArrayList<Object>());
                if (byWt) { x.put("by", "weight"); x.put("unit", wtUnit); }
                res.items.add(x);
                itemByKey.put(key, x);
            } else {
                boolean exWt = "weight".equals(x.get("by"));
                if (exWt != byWt || (byWt && !wtUnit.equals(String.valueOf(x.get("unit"))))) { res.skipped++; err(res, line, name + ": ஏற்கனவே உள்ள ஐட்டத்தின் விற்பனை முறை / அளவு மாற்ற முடியாது"); continue; }
                if (!nameEn.isEmpty() && String.valueOf(x.get("en")).isEmpty()) x.put("en", nameEn);
            }
            List<Object> sizes = (List<Object>) x.get("sizes");
            Map<String, Object> z = null;
            for (Object so : sizes) if (norm(((Map<String, Object>) so).get("size")).equals(norm(size))) { z = (Map<String, Object>) so; break; }
            if (z == null) {
                if (sizes.size() >= 20) { res.skipped++; err(res, line, name + ": ஒரு ஐட்டத்திற்கு 20 size வரை"); continue; }
                z = new LinkedHashMap<>();
                z.put("size", size);
                sizes.add(z);
            }
            z.put("rate", rate);
            if (wr != null) z.put("wrate", wr);
            if (cost != null) z.put("cost", cost);
            String code = cell(row, col, "barcode");
            if (!code.isEmpty()) z.put("code", code);
            if (touched.add(String.valueOf(x.get("id")))) { if (fresh) res.added++; else res.updated++; }
            res.touchedSizes.add(new Object[]{x.get("id"), z.get("size")});
            if (qty != null) res.stock.add(new Object[]{x.get("id"), z.get("size"), qty});
        }
        return res;
    }

    /**
     * Price-list update: ONLY rate / wholesale / cost of sizes that already exist change. Nothing is created or deleted, and names,
     * barcodes, stock and groups are never touched. Rows are matched by item_id (when the column is present) or by group + item + size.
     * Blank wholesale / cost / rate cells leave the current value unchanged.
     */
    @SuppressWarnings("unchecked")
    static Result mergePrices(String csv, Map<String, Object> existing) {
        Result res = new Result();
        List<List<String>> table = parseCsv(csv);
        if (table.isEmpty()) throw new ApiException(400, "CSV காலியாக உள்ளது");
        Map<String, Integer> col = new HashMap<>();
        List<String> head = table.get(0);
        for (int i = 0; i < head.size(); i++) {
            String k = ALIAS.get(head.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
            if (k != null && !col.containsKey(k)) col.put(k, i);
        }
        if (!(col.containsKey("item_id") || col.containsKey("item")) || !(col.containsKey("rate") || col.containsKey("wholesale") || col.containsKey("cost")))
            throw new ApiException(400, "CSV-ல் item_id (அல்லது item) மற்றும் rate / wholesale / cost நெடுவரிசை தேவை. விலைப்பட்டியலை Download செய்து திருத்தவும்");
        if (table.size() - 1 > MAX_ROWS) throw new ApiException(400, "ஒரு CSV-ல் அதிகபட்சம் " + MAX_ROWS + " வரிகள்");

        Map<String, Map<String, Object>> groupByName = new HashMap<>();
        Map<String, Map<String, Object>> itemById = new HashMap<>();
        Map<String, Map<String, Object>> itemByKey = new HashMap<>();
        for (Object o : (List<Object>) existing.getOrDefault("cats", new ArrayList<>())) {
            Map<String, Object> g = new LinkedHashMap<>((Map<String, Object>) o);
            res.cats.add(g);
            groupByName.put(norm(g.get("ta")), g);
        }
        for (Object o : (List<Object>) existing.getOrDefault("items", new ArrayList<>())) {
            Map<String, Object> x = new LinkedHashMap<>((Map<String, Object>) o);
            List<Object> sz = new ArrayList<>();
            for (Object so : (List<Object>) x.getOrDefault("sizes", new ArrayList<>())) sz.add(new LinkedHashMap<>((Map<String, Object>) so));
            x.put("sizes", sz);
            res.items.add(x);
            itemById.put(String.valueOf(x.get("id")), x);
            itemByKey.put(x.get("cat") + "|" + norm(x.get("ta")), x);
        }
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            int line = r + 1;
            res.rows++;
            String id = cell(row, col, "item_id"), name = cell(row, col, "item"), gTa = cell(row, col, "group"), size = cell(row, col, "size");
            if (id.isEmpty() && name.isEmpty()) { res.skipped++; err(res, line, "ஐட்டம் பெயர் / item_id இல்லை"); continue; }
            Map<String, Object> x = id.isEmpty() ? null : itemById.get(id);
            if (x == null) {
                Map<String, Object> g = gTa.isEmpty() ? null : groupByName.get(norm(gTa));
                if (g != null) x = itemByKey.get(g.get("id") + "|" + norm(name));
                else if (gTa.isEmpty() && !name.isEmpty()) {          // no group column: match by item name when it is unique
                    Map<String, Object> only = null; int n = 0;
                    for (Object io : res.items) { Map<String, Object> m = (Map<String, Object>) io; if (norm(m.get("ta")).equals(norm(name))) { only = m; n++; } }
                    if (n == 1) x = only;
                }
            }
            String label = name.isEmpty() ? id : name;
            if (x == null) { res.skipped++; err(res, line, label + ": ஐட்டம் கிடைக்கவில்லை (புதிய ஐட்டம் சேர்க்க Import பயன்படுத்தவும்)"); continue; }
            label = String.valueOf(x.get("ta"));
            boolean wt = "weight".equals(x.get("by"));
            if (wt) size = "1 " + x.get("unit");
            else if (size.isEmpty()) size = "1 pc";
            Map<String, Object> z = null;
            for (Object so : (List<Object>) x.get("sizes")) if (norm(((Map<String, Object>) so).get("size")).equals(norm(size))) { z = (Map<String, Object>) so; break; }
            if (z == null) { res.skipped++; err(res, line, label + ": size '" + shorten(size) + "' கிடைக்கவில்லை"); continue; }
            Double rate = null, wr = null, cost = null;
            String rs = cell(row, col, "rate"), ws = cell(row, col, "wholesale"), cs = cell(row, col, "cost");
            if (!rs.isEmpty()) { rate = money(rs); if (rate == null || !(rate > 0 && rate <= 999999)) { res.skipped++; err(res, line, label + ": rate தவறு"); continue; } }
            if (!ws.isEmpty()) { wr = money(ws); if (wr == null || !(wr > 0 && wr <= 999999)) { res.skipped++; err(res, line, label + ": wholesale தவறு"); continue; } }
            if (!cs.isEmpty()) { cost = money(cs); if (cost == null || !(cost >= 0 && cost <= 999999)) { res.skipped++; err(res, line, label + ": cost தவறு"); continue; } }
            boolean ch = false;
            ch |= setPrice(res, z, "rate", rate, label, size);
            ch |= setPrice(res, z, "wrate", wr, label, size);
            ch |= setPrice(res, z, "cost", cost, label, size);
            if (ch) res.changed++; else res.unchanged++;
        }
        return res;
    }

    private static boolean setPrice(Result res, Map<String, Object> z, String key, Double nv, String item, String size) {
        if (nv == null) return false;
        Object ov = z.get(key);
        Double o = ov instanceof Number ? ((Number) ov).doubleValue() : null;
        if (o != null && Math.abs(o - nv) < 0.005) return false;
        z.put(key, nv);
        if (res.changes.size() < 60) res.changes.add(new Object[]{item, size, key.equals("wrate") ? "wholesale" : key, o, nv});
        return true;
    }

    private static void err(Result r, int line, String msg) {
        if (r.errors.size() < MAX_ERRORS) r.errors.add("வரி " + line + ": " + msg);
    }

    private static String shorten(String s) { return s.length() > 16 ? s.substring(0, 16) + "…" : s; }

    private static String cell(List<String> row, Map<String, Integer> col, String k) {
        Integer i = col.get(k);
        if (i == null || i >= row.size()) return "";
        return row.get(i).replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    /** "₹1,250.50" -> 1250.5 ; null when not a number */
    static Double money(String s) {
        if (s == null) return null;
        String t = s.replace("₹", "").replace("Rs.", "").replace("Rs", "").replace(",", "").trim();
        if (t.isEmpty()) return null;
        try {
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return Math.round(d * 100) / 100.0;
        } catch (NumberFormatException e) { return null; }
    }

    static String norm(Object o) {
        return o == null ? "" : o.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Stable id from the name (same name -> same id, so re-loading a file updates instead of duplicating). */
    private static String freeId(String prefix, Set<String> used, String seed) {
        String h = sha1(seed).substring(0, 12);
        String id = prefix + h;
        int n = 1;
        while (used.contains(prefix + ":" + id)) id = prefix + h.substring(0, 10) + (n++);
        used.add(prefix + ":" + id);
        return id;
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ shop address (slug)
    static final java.util.regex.Pattern SLUG = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
    static final Set<String> RESERVED = Set.of("api", "admin", "static", "shop", "s", "i18n", "vendor", "templates", "index", "login", "app", "www");

    /** company name -> url word: ASCII letters / digits / '-', e.g. "Dry Fruit Palace" -> "dry-fruit-palace" (Tamil-only names give "") */
    static String slugBase(String name) {
        String n = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (n.length() > 30) n = n.substring(0, 30).replaceAll("-+$", "");
        return n;
    }

    static boolean validSlug(String s) { return s != null && SLUG.matcher(s).matches() && !s.contains("--") && !RESERVED.contains(s); }
}
