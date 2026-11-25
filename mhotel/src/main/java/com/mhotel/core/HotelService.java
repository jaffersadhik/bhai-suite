package com.mhotel.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hotel / restaurant domain: menu, dining tables, orders (dine-in / takeaway / delivery), billing,
 * payments, delivery-partner (Swiggy / Zomato) intake and WhatsApp bill text.
 */
public class HotelService {
    static final Set<String> TYPES = Set.of("dine_in", "takeaway", "delivery");
    static final Set<String> PARTNERS = Set.of("swiggy", "zomato", "other");

    private final Db db;
    private final ZoneId zone;

    public HotelService(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    // ================================================================ menu
    public List<Object> menu(Ctx c) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM menu_items WHERE active = 1 ORDER BY category, name")) {
            out.add(menuRow(r));
        }
        return out;
    }

    private Map<String, Object> menuRow(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", U.lng(r.get("id")));
        m.put("name", U.str(r.get("name")));
        m.put("nameTa", U.str(r.get("name_ta")));
        m.put("category", U.str(r.get("category")));
        m.put("price", U.round2(U.dbl(r.get("price"))));
        return m;
    }

    public Map<String, Object> saveMenu(Ctx c, Map<String, Object> in) {
        c.needBill();
        long id = U.lng(in.get("id"));
        String name = U.clip(in.get("name"), 60);
        if (name.isEmpty()) {
            throw new ApiException(400, "ஐட்டம் பெயர் தேவை");
        }
        String nameTa = U.clip(in.get("nameTa"), 60);
        String cat = U.clip(in.get("category"), 30);
        if (cat.isEmpty()) {
            cat = "General";
        }
        double price = U.dbl(in.get("price"));
        if (price < 0 || price > 999999) {
            throw new ApiException(400, "விலை தவறு");
        }
        if (id == 0) {
            db.insert("INSERT INTO menu_items (name, name_ta, category, price, active, created_at) VALUES (?, ?, ?, ?, 1, ?)",
                    name, nameTa, cat, U.money(price), now());
        } else {
            db.update("UPDATE menu_items SET name = ?, name_ta = ?, category = ?, price = ? WHERE id = ?",
                    name, nameTa, cat, U.money(price), id);
        }
        return menuWrap(c);
    }

    public Map<String, Object> deleteMenu(Ctx c, long id) {
        c.needBill();
        if (db.one("SELECT 1 AS x FROM order_lines WHERE item_id = ? LIMIT 1", id) != null) {
            db.update("UPDATE menu_items SET active = 0 WHERE id = ?", id);   // used in orders: keep history, just hide
        } else {
            db.update("DELETE FROM menu_items WHERE id = ?", id);
        }
        return menuWrap(c);
    }

    private Map<String, Object> menuWrap(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("menu", menu(c));
        return o;
    }

    // ================================================================ tables
    public List<Object> tables(Ctx c) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(
                "SELECT t.*, (SELECT o.id FROM orders o WHERE o.table_id = t.id AND o.status IN ('open','billed') ORDER BY o.id DESC LIMIT 1) AS order_id "
                + "FROM dining_tables t ORDER BY t.sort_order, t.id")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", U.lng(r.get("id")));
            m.put("label", U.str(r.get("label")));
            m.put("seats", U.intv(r.get("seats")));
            long oid = U.lng(r.get("order_id"));
            m.put("orderId", oid == 0 ? null : oid);
            m.put("busy", oid != 0);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> saveTable(Ctx c, Map<String, Object> in) {
        c.needBill();
        long id = U.lng(in.get("id"));
        String label = U.clip(in.get("label"), 20);
        if (label.isEmpty()) {
            throw new ApiException(400, "Table பெயர் / எண் தேவை");
        }
        int seats = U.intv(in.get("seats"));
        if (seats < 0 || seats > 99) {
            seats = 0;
        }
        if (id == 0) {
            db.insert("INSERT INTO dining_tables (label, seats, sort_order) VALUES (?, ?, ?)", label, seats, nextTableSort());
        } else {
            db.update("UPDATE dining_tables SET label = ?, seats = ? WHERE id = ?", label, seats, id);
        }
        return tablesWrap(c);
    }

    private int nextTableSort() {
        return U.intv(db.one("SELECT COALESCE(MAX(sort_order),0)+1 AS n FROM dining_tables").get("n"));
    }

    public Map<String, Object> deleteTable(Ctx c, long id) {
        c.needBill();
        if (db.one("SELECT 1 AS x FROM orders WHERE table_id = ? AND status IN ('open','billed') LIMIT 1", id) != null) {
            throw new ApiException(400, "இந்த Table-ல் open order உள்ளது — நீக்க முடியாது");
        }
        db.update("DELETE FROM dining_tables WHERE id = ?", id);
        return tablesWrap(c);
    }

    private Map<String, Object> tablesWrap(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("tables", tables(c));
        return o;
    }

    // ================================================================ orders
    public Map<String, Object> orders(Ctx c, String status, String type) {
        StringBuilder sb = new StringBuilder("SELECT o.*, t.label AS table_label FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String st = U.str(status);
        if (st.equals("open")) {
            sb.append(" AND o.status IN ('open','billed')");
        } else if (!st.isEmpty() && !st.equals("all")) {
            sb.append(" AND o.status = ?");
            args.add(st);
        }
        String ty = U.str(type);
        if (!ty.isEmpty() && !ty.equals("all")) {
            sb.append(" AND o.type = ?");
            args.add(ty);
        }
        sb.append(" ORDER BY o.id DESC LIMIT 300");
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : db.query(sb.toString(), args.toArray())) {
            out.add(orderHead(r));
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("orders", out);
        return o;
    }

    private Map<String, Object> orderHead(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        long id = U.lng(r.get("id"));
        m.put("id", id);
        m.put("no", "H" + String.format("%04d", id));
        m.put("type", U.str(r.get("type")));
        m.put("status", U.str(r.get("status")));
        m.put("tableId", r.get("table_id") == null ? null : U.lng(r.get("table_id")));
        m.put("table", U.str(r.get("table_label")));
        m.put("partner", U.str(r.get("partner")));
        m.put("partnerRef", U.str(r.get("partner_ref")));
        m.put("customer", U.str(r.get("customer_name")));
        m.put("phone", U.str(r.get("customer_phone")));
        m.put("subtotal", U.round2(U.dbl(r.get("subtotal"))));
        m.put("taxPct", U.round2(U.dbl(r.get("tax_pct"))));
        m.put("tax", U.round2(U.dbl(r.get("tax"))));
        m.put("discount", U.round2(U.dbl(r.get("discount"))));
        m.put("total", U.round2(U.dbl(r.get("total"))));
        m.put("billNo", U.str(r.get("bill_no")));
        m.put("by", U.str(r.get("created_by_name")));
        m.put("at", U.str(r.get("created_at")));
        return m;
    }

    public Map<String, Object> orderGet(Ctx c, long id) {
        Map<String, Object> r = db.one("SELECT o.*, t.label AS table_label FROM orders o LEFT JOIN dining_tables t ON t.id = o.table_id WHERE o.id = ?", id);
        if (r == null) {
            throw new ApiException(404, "Order இல்லை");
        }
        Map<String, Object> o = orderHead(r);
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> l : db.query("SELECT * FROM order_lines WHERE order_id = ? ORDER BY line_no", id)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("line", U.intv(l.get("line_no")));
            n.put("itemId", l.get("item_id") == null ? null : U.lng(l.get("item_id")));
            n.put("name", U.str(l.get("name")));
            n.put("qty", U.intv(l.get("qty")));
            n.put("price", U.round2(U.dbl(l.get("price"))));
            n.put("amount", U.round2(U.dbl(l.get("amount"))));
            lines.add(n);
        }
        o.put("lines", lines);
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("order", o);
        return wrap;
    }

    /** create an order. body: {type, tableId?, partner?, partnerRef?, customer?, phone?} */
    public Map<String, Object> createOrder(Ctx c, Map<String, Object> in) {
        String type = U.str(in.get("type"));
        if (!TYPES.contains(type)) {
            throw new ApiException(400, "Order வகை: dine_in / takeaway / delivery");
        }
        Long tableId = null;
        String partner = "", partnerRef = "";
        if (type.equals("dine_in")) {
            tableId = U.lng(in.get("tableId"));
            if (tableId == 0) {
                throw new ApiException(400, "Table-ஐ தேர்வு செய்யவும்");
            }
            if (db.one("SELECT id FROM dining_tables WHERE id = ?", tableId) == null) {
                throw new ApiException(404, "Table இல்லை");
            }
            if (db.one("SELECT 1 AS x FROM orders WHERE table_id = ? AND status IN ('open','billed') LIMIT 1", tableId) != null) {
                throw new ApiException(409, "இந்த Table ஏற்கனவே occupied");
            }
        } else if (type.equals("delivery")) {
            partner = U.str(in.get("partner")).toLowerCase();
            if (!PARTNERS.contains(partner)) {
                partner = "other";
            }
            partnerRef = U.clip(in.get("partnerRef"), 40);
        }
        String customer = U.clip(in.get("customer"), 60);
        String phone = U.digits(in.get("phone"));
        long id = db.insert(
                "INSERT INTO orders (type, table_id, partner, partner_ref, customer_name, customer_phone, status, "
                + "subtotal, tax_pct, tax, discount, total, created_at, created_by, created_by_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'open', 0, 5, 0, 0, 0, ?, ?, ?)",
                type, tableId, partner, partnerRef, customer, phone, now(), c.userId(), c.name());
        return orderGet(c, id);
    }

    /** replace the order's lines. body: {lines:[{itemId?, name, qty, price}]} - only while open. */
    public Map<String, Object> setLines(Ctx c, long id, Map<String, Object> in) {
        Map<String, Object> o = db.one("SELECT * FROM orders WHERE id = ?", id);
        if (o == null) {
            throw new ApiException(404, "Order இல்லை");
        }
        if (!U.str(o.get("status")).equals("open")) {
            throw new ApiException(409, "Bill ஆன / முடிந்த order-ஐ மாற்ற முடியாது");
        }
        Object raw = in.get("lines");
        if (!(raw instanceof List<?> list)) {
            throw new ApiException(400, "Items இல்லை");
        }
        List<Object[]> rows = new ArrayList<>();
        double subtotal = 0;
        int ln = 1;
        for (Object e : list) {
            if (!(e instanceof Map<?, ?> lm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> l = (Map<String, Object>) lm;
            String name = U.clip(l.get("name"), 60);
            int qty = U.intv(l.get("qty"));
            double price = U.dbl(l.get("price"));
            if (name.isEmpty() || qty < 1 || qty > 9999 || price < 0) {
                continue;
            }
            Long itemId = U.lng(l.get("itemId"));
            if (itemId == 0) {
                itemId = null;
            }
            double amt = U.round2(price * qty);
            subtotal += amt;
            rows.add(new Object[]{id, ln++, itemId, name, qty, U.money(price), U.money(amt)});
        }
        final double sub = U.round2(subtotal);
        db.tx(() -> {
            db.update("DELETE FROM order_lines WHERE order_id = ?", id);
            if (!rows.isEmpty()) {
                db.batch("INSERT INTO order_lines (order_id, line_no, item_id, name, qty, price, amount) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
            }
            db.update("UPDATE orders SET subtotal = ?, total = ? WHERE id = ?", U.money(sub), U.money(sub), id);
            return null;
        });
        return orderGet(c, id);
    }

    /** compute tax / discount and move to 'billed'. body: {taxPct?, discount?} */
    public Map<String, Object> billOrder(Ctx c, long id, Map<String, Object> in) {
        c.needBill();
        Map<String, Object> o = db.one("SELECT * FROM orders WHERE id = ?", id);
        if (o == null) {
            throw new ApiException(404, "Order இல்லை");
        }
        if (U.str(o.get("status")).equals("cancelled") || U.str(o.get("status")).equals("paid")) {
            throw new ApiException(409, "இந்த order-ஐ bill செய்ய முடியாது");
        }
        if (U.lng(db.one("SELECT COUNT(*) AS n FROM order_lines WHERE order_id = ?", id).get("n")) == 0) {
            throw new ApiException(400, "Order-ல் items இல்லை");
        }
        double subtotal = U.dbl(o.get("subtotal"));
        double taxPct = in.containsKey("taxPct") ? U.dbl(in.get("taxPct")) : U.dbl(o.get("tax_pct"));
        if (taxPct < 0 || taxPct > 28) {
            throw new ApiException(400, "Tax % தவறு");
        }
        double discount = U.dbl(in.get("discount"));
        if (discount < 0 || discount > subtotal) {
            throw new ApiException(400, "தள்ளுபடி தவறு");
        }
        double tax = U.round2((subtotal - discount) * taxPct / 100);
        double total = U.round2(subtotal - discount + tax);
        String billNo = U.str(o.get("bill_no"));
        if (billNo.isEmpty()) {
            billNo = "B" + String.format("%04d", id);
        }
        db.update("UPDATE orders SET status = 'billed', tax_pct = ?, tax = ?, discount = ?, total = ?, bill_no = ?, billed_at = ? WHERE id = ?",
                U.money(taxPct), U.money(tax), U.money(discount), U.money(total), billNo, now(), id);
        return orderGet(c, id);
    }

    /** record payment and close. body: {mode?} */
    public Map<String, Object> payOrder(Ctx c, long id, Map<String, Object> in) {
        c.needBill();
        Map<String, Object> o = db.one("SELECT * FROM orders WHERE id = ?", id);
        if (o == null) {
            throw new ApiException(404, "Order இல்லை");
        }
        if (!U.str(o.get("status")).equals("billed")) {
            throw new ApiException(409, "முதலில் bill செய்யவும்");
        }
        String mode = U.clip(in.get("mode"), 12);
        if (mode.isEmpty()) {
            mode = "cash";
        }
        final String m = mode;
        db.tx(() -> {
            db.insert("INSERT INTO payments (order_id, amount, mode, paid_at, by_name) VALUES (?, ?, ?, ?, ?)",
                    id, U.money(U.dbl(o.get("total"))), m, now(), c.name());
            db.update("UPDATE orders SET status = 'paid', pay_mode = ?, closed_at = ? WHERE id = ?", m, now(), id);
            return null;
        });
        return orderGet(c, id);
    }

    public Map<String, Object> cancelOrder(Ctx c, long id) {
        c.needBill();
        Map<String, Object> o = db.one("SELECT * FROM orders WHERE id = ?", id);
        if (o == null) {
            throw new ApiException(404, "Order இல்லை");
        }
        if (U.str(o.get("status")).equals("paid")) {
            throw new ApiException(409, "செலுத்திய order-ஐ ரத்து செய்ய முடியாது");
        }
        db.update("UPDATE orders SET status = 'cancelled', closed_at = ? WHERE id = ?", now(), id);
        return orderGet(c, id);
    }

    // ================================================================ WhatsApp bill
    public Map<String, Object> whatsapp(Ctx c, long id) {
        Map<String, Object> wrap = orderGet(c, id);
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) wrap.get("order");
        StringBuilder t = new StringBuilder();
        t.append("*").append(shopName()).append("*\n");
        t.append("Bill ").append(U.str(o.get("billNo")).isEmpty() ? U.str(o.get("no")) : U.str(o.get("billNo"))).append("\n");
        if (!U.str(o.get("table")).isEmpty()) {
            t.append("Table: ").append(o.get("table")).append("\n");
        }
        t.append("------------------------------\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) o.get("lines");
        for (Map<String, Object> l : lines) {
            t.append(l.get("qty")).append(" x ").append(l.get("name")).append("  ₹").append(l.get("amount")).append("\n");
        }
        t.append("------------------------------\n");
        t.append("Subtotal: ₹").append(o.get("subtotal")).append("\n");
        if (U.dbl(o.get("discount")) > 0) {
            t.append("Discount: -₹").append(o.get("discount")).append("\n");
        }
        if (U.dbl(o.get("tax")) > 0) {
            t.append("Tax (").append(o.get("taxPct")).append("%): ₹").append(o.get("tax")).append("\n");
        }
        t.append("*Total: ₹").append(o.get("total")).append("*\n");
        t.append("\nநன்றி! வருக வருக 🙏");
        String text = t.toString();
        String phone = U.str(o.get("phone"));
        String url = "https://wa.me/" + (phone.isEmpty() ? "" : (phone.length() == 10 ? "91" + phone : phone))
                + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("phone", phone);
        out.put("url", url);
        return out;
    }

    private String shopName() {
        Map<String, Object> r = db.one("SELECT val FROM settings WHERE k = 'shop_name'");
        return r == null || U.blank(r.get("val")) ? "mHotel" : U.str(r.get("val"));
    }

    // ================================================================ delivery-partner webhook (Swiggy / Zomato)
    /**
     * Create a delivery order from a partner webhook. There is NO logged-in user here, so the caller must
     * send the shared secret (MHOTEL_WEBHOOK_SECRET). Real Swiggy / Zomato integration needs their partner
     * API credentials; this accepts a normalised JSON body:
     *   {partner, ref, customer, phone, items:[{name, qty, price}]}
     */
    public Map<String, Object> webhookOrder(Map<String, Object> in) {
        String partner = U.str(in.get("partner")).toLowerCase();
        if (!PARTNERS.contains(partner)) {
            partner = "other";
        }
        String ref = U.clip(in.get("ref"), 40);
        String customer = U.clip(in.get("customer"), 60);
        String phone = U.digits(in.get("phone"));
        Object raw = in.get("items");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            throw new ApiException(400, "items required");
        }
        final String p = partner;
        long id = db.tx(() -> {
            long oid = db.insert(
                    "INSERT INTO orders (type, table_id, partner, partner_ref, customer_name, customer_phone, status, "
                    + "subtotal, tax_pct, tax, discount, total, created_at, created_by, created_by_name) "
                    + "VALUES ('delivery', NULL, ?, ?, ?, ?, 'open', 0, 5, 0, 0, 0, ?, 0, ?)",
                    p, ref, customer, phone, now(), p + " webhook");
            List<Object[]> rows = new ArrayList<>();
            double sub = 0;
            int ln = 1;
            for (Object e : items) {
                if (!(e instanceof Map<?, ?> lm)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) lm;
                String name = U.clip(l.get("name"), 60);
                int qty = Math.max(1, U.intv(l.get("qty")));
                double price = U.dbl(l.get("price"));
                if (name.isEmpty()) {
                    continue;
                }
                double amt = U.round2(price * qty);
                sub += amt;
                rows.add(new Object[]{oid, ln++, null, name, qty, U.money(price), U.money(amt)});
            }
            if (!rows.isEmpty()) {
                db.batch("INSERT INTO order_lines (order_id, line_no, item_id, name, qty, price, amount) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
            }
            double s = U.round2(sub);
            db.update("UPDATE orders SET subtotal = ?, total = ? WHERE id = ?", U.money(s), U.money(s), oid);
            return oid;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("orderId", id);
        out.put("no", "H" + String.format("%04d", id));
        return out;
    }

    // ================================================================ dashboard + bootstrap
    public Map<String, Object> dashboard(Ctx c) {
        LocalDate d = today();
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, Object> sales = db.one(
                "SELECT COUNT(*) AS n, COALESCE(SUM(total),0) AS t FROM orders WHERE status = 'paid' AND DATE(closed_at) = ?", d);
        o.put("todayOrders", U.lng(sales.get("n")));
        o.put("todaySales", U.round2(U.dbl(sales.get("t"))));
        o.put("openOrders", U.lng(db.one("SELECT COUNT(*) AS n FROM orders WHERE status IN ('open','billed')").get("n")));
        Map<String, Object> tb = db.one("SELECT COUNT(*) AS total FROM dining_tables");
        long totalTables = U.lng(tb.get("total"));
        long busy = U.lng(db.one("SELECT COUNT(DISTINCT table_id) AS n FROM orders WHERE table_id IS NOT NULL AND status IN ('open','billed')").get("n"));
        o.put("tablesTotal", totalTables);
        o.put("tablesBusy", busy);
        o.put("tablesFree", totalTables - busy);
        List<Object> byType = new ArrayList<>();
        for (Map<String, Object> r : db.query(
                "SELECT type, COUNT(*) AS n, COALESCE(SUM(total),0) AS t FROM orders WHERE status = 'paid' AND DATE(closed_at) = ? GROUP BY type", d)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", U.str(r.get("type")));
            m.put("count", U.lng(r.get("n")));
            m.put("total", U.round2(U.dbl(r.get("t"))));
            byType.add(m);
        }
        o.put("byType", byType);
        return o;
    }

    /** everything the app needs on load. */
    public Map<String, Object> data(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("menu", menu(c));
        o.put("tables", tables(c));
        o.put("shopName", shopName());
        o.put("canBill", c.bill());
        o.put("isAdmin", c.admin());
        return o;
    }

    public void setShopName(Ctx c, String name) {
        c.needAdmin();
        String v = U.clip(name, 60);
        db.update("INSERT INTO settings (k, val) VALUES ('shop_name', ?) ON DUPLICATE KEY UPDATE val = ?", v, v);
    }
}
