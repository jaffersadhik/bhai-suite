package com.pomgmt.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized Purchase Order Management domain (both proposals):
 *  - masters: branches (R1-R15), suppliers + agencies, items + size matrix
 *  - budget quantity control: set approved budgets, compare every PO against the balance
 *  - ONE purchase entry -> SEPARATE branch POs linked by a common reference
 *  - approval workflow, goods receiving, reports + dashboard
 */
public class PurchaseService {
    static final String COMMITTED = "('pending','approved','sent','partial','completed')";

    private final Db db;
    private final ZoneId zone;
    private final String enforcement;   // off | warn | strict

    public PurchaseService(Db db, ZoneId zone, String enforcement) {
        this.db = db;
        this.zone = zone;
        this.enforcement = enforcement;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    // ---------------------------------------------------------------- lookups
    private Map<String, Object> branch(Object codeOrId) {
        Map<String, Object> r = db.one("SELECT * FROM branches WHERE code = ? OR id = ?", U.str(codeOrId), U.lng(codeOrId));
        if (r == null) throw new ApiException(404, "Branch இல்லை: " + codeOrId);
        return r;
    }

    private Map<String, Object> supplier(long id) {
        Map<String, Object> r = db.one("SELECT * FROM suppliers WHERE id = ?", id);
        if (r == null) throw new ApiException(404, "Supplier இல்லை");
        return r;
    }

    private Map<String, Object> item(Object idOrCode) {
        Map<String, Object> r = db.one("SELECT * FROM items WHERE id = ? OR item_code = ?", U.lng(idOrCode), U.str(idOrCode));
        if (r == null) throw new ApiException(404, "Item இல்லை: " + idOrCode);
        return r;
    }

    long committedQty(long itemId, long branchId, String period) {
        Map<String, Object> r = db.one(
                "SELECT COALESCE(SUM(l.qty),0) AS q FROM po_lines l JOIN purchase_orders p ON p.id = l.po_id "
                + "JOIN purchase_entries e ON e.id = p.entry_id "
                + "WHERE l.item_id = ? AND p.branch_id = ? AND e.period = ? AND p.status IN " + COMMITTED,
                itemId, branchId, period);
        return U.lng(r.get("q"));
    }

    /** approved budget qty for item+branch+period, or null if none set. */
    Long budgetQty(long itemId, long branchId, String period) {
        Map<String, Object> r = db.one("SELECT COALESCE(SUM(budget_qty),0) AS q, COUNT(*) AS n FROM budgets "
                + "WHERE item_id = ? AND branch_id = ? AND period = ? AND status = 'approved'", itemId, branchId, period);
        return U.lng(r.get("n")) > 0 ? U.lng(r.get("q")) : null;
    }

    // ================================================================ masters
    public List<Object> branches() {
        return new ArrayList<>(db.query("SELECT id, code, name, address, active FROM branches ORDER BY id"));
    }

    public List<Object> suppliers() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : db.query("SELECT * FROM suppliers ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(s);
            m.put("agencies", db.query("SELECT id, name, location FROM agencies WHERE supplier_id = ?", U.lng(s.get("id"))));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> saveSupplier(Ctx c, Map<String, Object> in) {
        c.needCreate();
        long id = U.lng(in.get("id"));
        String name = U.clip(in.get("name"), 100);
        if (name.isEmpty()) throw new ApiException(400, "Supplier பெயர் தேவை");
        if (id == 0) {
            db.insert("INSERT INTO suppliers (name, gstin, address, contact_person, mobile, payment_terms, credit_days, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    name, U.clip(in.get("gstin"), 20), U.clip(in.get("address"), 200), U.clip(in.get("contactPerson"), 60),
                    U.digits(in.get("mobile")), U.clip(in.get("paymentTerms"), 60), U.intv(in.get("creditDays")), now());
        } else {
            db.update("UPDATE suppliers SET name=?, gstin=?, address=?, contact_person=?, mobile=?, payment_terms=?, credit_days=? WHERE id=?",
                    name, U.clip(in.get("gstin"), 20), U.clip(in.get("address"), 200), U.clip(in.get("contactPerson"), 60),
                    U.digits(in.get("mobile")), U.clip(in.get("paymentTerms"), 60), U.intv(in.get("creditDays")), id);
        }
        return obj("suppliers", suppliers());
    }

    public Map<String, Object> addAgency(Ctx c, Map<String, Object> in) {
        c.needCreate();
        long sid = U.lng(in.get("supplierId"));
        supplier(sid);
        String name = U.clip(in.get("name"), 100);
        if (name.isEmpty()) throw new ApiException(400, "Agency பெயர் தேவை");
        db.insert("INSERT INTO agencies (supplier_id, name, location) VALUES (?, ?, ?)", sid, name, U.clip(in.get("location"), 60));
        return obj("suppliers", suppliers());
    }

    public List<Object> items() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> it : db.query("SELECT * FROM items WHERE active = 1 ORDER BY item_code")) {
            Map<String, Object> m = new LinkedHashMap<>(it);
            List<String> sizes = new ArrayList<>();
            for (Map<String, Object> z : db.query("SELECT size FROM item_sizes WHERE item_id = ? ORDER BY sort_order", U.lng(it.get("id"))))
                sizes.add(U.str(z.get("size")));
            m.put("sizes", sizes);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> saveItem(Ctx c, Map<String, Object> in) {
        c.needCreate();
        long id = U.lng(in.get("id"));
        String code = U.clip(in.get("itemCode"), 40), desc = U.clip(in.get("description"), 120);
        if (code.isEmpty() || desc.isEmpty()) throw new ApiException(400, "Item code மற்றும் description தேவை");
        double gst = U.dbl(in.get("gstPct"));
        long iid;
        if (id == 0) {
            if (db.one("SELECT id FROM items WHERE item_code = ?", code) != null)
                throw new ApiException(400, "Item code ஏற்கனவே உள்ளது: " + code);
            iid = db.insert("INSERT INTO items (item_code, barcode, category, brand, description, colour, hsn, gst_pct, active) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)", code, U.clip(in.get("barcode"), 40), U.clip(in.get("category"), 40),
                    U.clip(in.get("brand"), 40), desc, U.clip(in.get("colour"), 30), U.clip(in.get("hsn"), 12), gst);
        } else {
            iid = id;
            db.update("UPDATE items SET item_code=?, barcode=?, category=?, brand=?, description=?, colour=?, hsn=?, gst_pct=? WHERE id=?",
                    code, U.clip(in.get("barcode"), 40), U.clip(in.get("category"), 40), U.clip(in.get("brand"), 40), desc,
                    U.clip(in.get("colour"), 30), U.clip(in.get("hsn"), 12), gst, id);
            db.update("DELETE FROM item_sizes WHERE item_id = ?", iid);
        }
        Object sizesRaw = in.get("sizes");
        if (sizesRaw instanceof List<?> list) {
            int i = 0;
            for (Object s : list) {
                String sz = U.clip(s, 12);
                if (!sz.isEmpty()) db.insert("INSERT INTO item_sizes (item_id, size, sort_order) VALUES (?, ?, ?)", iid, sz, i++);
            }
        }
        return obj("items", items());
    }

    // ================================================================ budget control
    public Map<String, Object> setBudget(Ctx c, Map<String, Object> in) {
        c.needApprove();
        String period = U.clip(in.get("period"), 40);
        if (period.isEmpty()) throw new ApiException(400, "Budget period தேவை (எ.கா. 2026-Q4)");
        Map<String, Object> b = branch(in.get("branch"));
        Map<String, Object> it = item(in.get("item"));
        String size = U.clip(in.get("size"), 12);
        int qty = U.intv(in.get("budgetQty"));
        if (qty < 0) throw new ApiException(400, "Budget qty தவறு");
        Map<String, Object> prev = db.one("SELECT * FROM budgets WHERE period=? AND branch_id=? AND item_id=? AND size=? AND status='approved'",
                period, U.lng(b.get("id")), U.lng(it.get("id")), size);
        int rev = prev == null ? 1 : U.intv(prev.get("revision")) + 1;
        if (prev != null) db.update("UPDATE budgets SET status='revised' WHERE id=?", U.lng(prev.get("id")));
        db.insert("INSERT INTO budgets (period, branch_id, item_id, size, budget_qty, prepared_by, approved_by, status, revision, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'approved', ?, ?)",
                period, U.lng(b.get("id")), U.lng(it.get("id")), size, qty, U.clip(in.get("preparedBy"), 40), c.name(), rev, now());
        audit("budget", 0, prev == null ? "create" : "revise", c, it.get("item_code") + " " + b.get("code") + " " + period + "=" + qty);
        return obj("budgets", listBudgets(U.str(in.get("period")), ""));
    }

    public List<Object> listBudgets(String period, String branchCode) {
        StringBuilder sql = new StringBuilder("SELECT bu.id, bu.period, br.code AS branch, it.item_code AS item, it.description, "
                + "bu.size, bu.budget_qty, bu.revision FROM budgets bu JOIN branches br ON br.id=bu.branch_id "
                + "JOIN items it ON it.id=bu.item_id WHERE bu.status='approved'");
        List<Object> args = new ArrayList<>();
        if (!U.blank(period)) { sql.append(" AND bu.period = ?"); args.add(period); }
        if (!U.blank(branchCode)) { sql.append(" AND br.code = ?"); args.add(branchCode); }
        sql.append(" ORDER BY bu.period, br.id, it.item_code");
        return new ArrayList<>(db.query(sql.toString(), args.toArray()));
    }

    public Map<String, Object> budgetBalance(Ctx c, String itemRef, String branchRef, String period) {
        Map<String, Object> b = branch(branchRef);
        Map<String, Object> it = item(itemRef);
        Long bq = budgetQty(U.lng(it.get("id")), U.lng(b.get("id")), period);
        long cq = committedQty(U.lng(it.get("id")), U.lng(b.get("id")), period);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("item", it.get("item_code"));
        o.put("branch", b.get("code"));
        o.put("period", period);
        o.put("budgetQty", bq);
        o.put("committedQty", cq);
        o.put("balance", bq == null ? null : bq - cq);
        o.put("hasBudget", bq != null);
        o.put("utilizationPct", (bq == null || bq == 0) ? null : U.round2(cq * 100.0 / bq));
        return o;
    }

    // ================================================================ purchase entry -> branch POs
    @SuppressWarnings("unchecked")
    public Map<String, Object> createEntry(Ctx c, Map<String, Object> in) {
        c.needCreate();
        Map<String, Object> sup = supplier(U.lng(in.get("supplierId")));
        Long agencyId = U.lng(in.get("agencyId")) == 0 ? null : U.lng(in.get("agencyId"));
        if (agencyId != null && db.one("SELECT id FROM agencies WHERE id=? AND supplier_id=?", agencyId, U.lng(sup.get("id"))) == null)
            throw new ApiException(400, "Agency அந்த supplier-ஐ சேர்ந்தது இல்ல");
        String period = U.clip(in.get("period"), 40);
        List<?> branchCodes = in.get("branches") instanceof List<?> l ? l : List.of();
        if (branchCodes.isEmpty()) throw new ApiException(400, "குறைந்தது ஒரு branch தேர்வு செய்யவும்");
        List<?> rawLines = in.get("lines") instanceof List<?> l ? l : List.of();
        if (rawLines.isEmpty()) throw new ApiException(400, "குறைந்தது ஒரு item வரி தேவை");
        boolean allowOver = Boolean.TRUE.equals(in.get("allowOverBudget"));
        String excReason = U.clip(in.get("exceptionReason"), 200);
        Map<String, Object> factor = in.get("branchQtyFactor") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        // build the plan per branch, run budget checks
        List<Object[]> plan = new ArrayList<>();   // [branchRow, List<Object[]{itemRow,size,qty,rate}>, overBudget(boolean)]
        List<String> blocks = new ArrayList<>(), warnings = new ArrayList<>();
        for (Object bc : branchCodes) {
            Map<String, Object> b = branch(bc);
            double f = factor.containsKey(U.str(b.get("code"))) ? U.dbl(factor.get(U.str(b.get("code")))) : 1.0;
            if (f <= 0) f = 1.0;
            List<Object[]> blines = new ArrayList<>();
            Map<Long, Long> perItem = new LinkedHashMap<>();
            for (Object lo : rawLines) {
                Map<String, Object> l = (Map<String, Object>) lo;
                Map<String, Object> it = item(l.get("item"));
                long q = Math.max(1, Math.round(U.dbl(l.get("qty")) * f));
                double rate = U.dbl(l.get("rate"));
                blines.add(new Object[]{it, U.clip(l.get("size"), 12), q, rate});
                perItem.merge(U.lng(it.get("id")), q, Long::sum);
            }
            boolean over = false;
            if (!period.isEmpty() && !"off".equals(enforcement)) {
                for (Map.Entry<Long, Long> en : perItem.entrySet()) {
                    Long bq = budgetQty(en.getKey(), U.lng(b.get("id")), period);
                    long q = en.getValue();
                    if (bq == null) {
                        if ("strict".equals(enforcement)) blocks.add(b.get("code") + ": item " + en.getKey() + "-க்கு " + period + " budget இல்ல");
                        else warnings.add(b.get("code") + ": item " + en.getKey() + " budget இல்ல (warn)");
                        continue;
                    }
                    long cq = committedQty(en.getKey(), U.lng(b.get("id")), period);
                    if (cq + q > bq) {
                        over = true;
                        long excess = cq + q - bq;
                        String msg = b.get("code") + " item " + en.getKey() + ": budget மீறல் " + excess + " (budget " + bq + ", committed " + cq + ", இந்த PO " + q + ")";
                        if (allowOver) warnings.add(msg + " [exception approved]");
                        else blocks.add(msg);
                    }
                }
            }
            plan.add(new Object[]{b, blines, over});
        }
        if (!blocks.isEmpty() && !allowOver)
            throw new ApiException(409, "Budget சரிபார்ப்பு தோல்வி (எதுவும் உருவாக்கப்படவில்லை). Budget அதிகரிக்கவும், qty குறைக்கவும், இல்ல allowOverBudget + exceptionReason கொடுக்கவும்.\n- " + String.join("\n- ", blocks));
        if (allowOver && plan.stream().anyMatch(p -> (Boolean) p[2]) && excReason.isEmpty())
            throw new ApiException(400, "Over-budget exception-க்கு காரணம் (exceptionReason) தேவை");

        long eseq = nextCounter("entry", 0);
        String cref = "PE-" + String.format("%04d", eseq);
        List<Long> poSeqs = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) poSeqs.add(nextCounter("po", 10000));

        List<Object> made = new ArrayList<>();
        long entryId = db.tx(() -> {
            long eid = db.insert("INSERT INTO purchase_entries (common_ref, supplier_id, agency_id, period, order_book_ref, order_book_file, ordered_by, remarks, delivery_date, status, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)",
                    cref, U.lng(sup.get("id")), agencyId, period, U.clip(in.get("orderBookRef"), 60), U.clip(in.get("orderBookFile"), 255),
                    c.name(), U.clip(in.get("remarks"), 255), U.clip(in.get("deliveryDate"), 12), now());
            for (int i = 0; i < plan.size(); i++) {
                Object[] pb = plan.get(i);
                Map<String, Object> b = (Map<String, Object>) pb[0];
                List<Object[]> blines = (List<Object[]>) pb[1];
                boolean over = (Boolean) pb[2];
                String pono = poSeqs.get(i) + "/" + b.get("code");
                double sub = 0;
                long tot = 0;
                for (Object[] bl : blines) sub += U.lng(bl[2]) * U.dbl(bl[3]);
                long pid = db.insert("INSERT INTO purchase_orders (po_number, entry_id, branch_id, status, ordered_by, over_budget, exception_reason, subtotal, created_at) "
                        + "VALUES (?, ?, ?, 'pending', ?, ?, ?, ?, ?)",
                        pono, eid, U.lng(b.get("id")), c.name(), over ? 1 : 0, over ? excReason : "", U.money(sub), now());
                for (Object[] bl : blines) {
                    Map<String, Object> it = (Map<String, Object>) bl[0];
                    long q = U.lng(bl[2]);
                    double rate = U.dbl(bl[3]);
                    db.insert("INSERT INTO po_lines (po_id, item_id, size, qty, rate, amount, received_qty) VALUES (?, ?, ?, ?, ?, ?, 0)",
                            pid, U.lng(it.get("id")), U.str(bl[1]), q, U.money(rate), U.money(q * rate));
                    tot += q;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("poNumber", pono);
                m.put("branch", b.get("code"));
                m.put("totalQty", tot);
                m.put("amount", U.round2(sub));
                m.put("overBudget", over);
                made.add(m);
            }
            return eid;
        });
        audit("entry", entryId, "create", c, cref + " " + made.size() + " branch POs");
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", true);
        o.put("commonRef", cref);
        o.put("entryId", entryId);
        o.put("branchPos", made);
        o.put("warnings", warnings);
        o.put("totalQty", made.stream().mapToLong(m -> U.lng(((Map<?, ?>) m).get("totalQty"))).sum());
        return o;
    }

    // ================================================================ list / detail / approve / receive
    public Map<String, Object> listPos(String status, String branch, String commonRef) {
        StringBuilder sql = new StringBuilder("SELECT p.id, p.po_number, e.common_ref, br.code AS branch, s.name AS supplier, p.status, "
                + "p.subtotal, p.over_budget, p.ordered_by, p.approved_by, "
                + "(SELECT COALESCE(SUM(qty),0) FROM po_lines WHERE po_id=p.id) AS total_qty, "
                + "(SELECT COALESCE(SUM(received_qty),0) FROM po_lines WHERE po_id=p.id) AS received_qty "
                + "FROM purchase_orders p JOIN purchase_entries e ON e.id=p.entry_id JOIN branches br ON br.id=p.branch_id "
                + "JOIN suppliers s ON s.id=e.supplier_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!U.blank(status)) { sql.append(" AND p.status = ?"); args.add(status); }
        if (!U.blank(branch)) { sql.append(" AND br.code = ?"); args.add(branch); }
        if (!U.blank(commonRef)) { sql.append(" AND e.common_ref = ?"); args.add(commonRef); }
        sql.append(" ORDER BY p.id DESC LIMIT 500");
        return obj("purchaseOrders", new ArrayList<>(db.query(sql.toString(), args.toArray())));
    }

    public Map<String, Object> poDetail(String poNumber) {
        Map<String, Object> p = db.one("SELECT p.*, e.common_ref, e.order_book_ref, e.delivery_date, e.remarks, e.period, "
                + "s.name AS supplier, br.code AS branch, br.name AS branch_name FROM purchase_orders p "
                + "JOIN purchase_entries e ON e.id=p.entry_id JOIN suppliers s ON s.id=e.supplier_id "
                + "JOIN branches br ON br.id=p.branch_id WHERE p.po_number = ?", poNumber);
        if (p == null) throw new ApiException(404, "PO இல்லை: " + poNumber);
        Map<String, Object> o = new LinkedHashMap<>(p);
        o.put("lines", db.query("SELECT l.id, it.item_code, it.description, l.size, l.qty, l.rate, l.amount, l.received_qty, "
                + "(l.qty - l.received_qty) AS pending_qty FROM po_lines l JOIN items it ON it.id=l.item_id WHERE l.po_id=? ORDER BY l.id",
                U.lng(p.get("id"))));
        return obj("po", o);
    }

    public Map<String, Object> approvePo(Ctx c, Map<String, Object> in) {
        c.needApprove();
        String pono = U.str(in.get("poNumber"));
        String decision = U.str(in.get("decision"));
        Map<String, Object> p = db.one("SELECT * FROM purchase_orders WHERE po_number = ?", pono);
        if (p == null) throw new ApiException(404, "PO இல்லை: " + pono);
        if (!U.str(p.get("status")).equals("pending") && !U.str(p.get("status")).equals("draft"))
            throw new ApiException(409, "PO '" + p.get("status") + "' — pending/draft மட்டுமே approve/reject செய்யலாம்");
        if (decision.equals("approve")) {
            db.update("UPDATE purchase_orders SET status='approved', approved_by=?, approved_at=? WHERE id=?", c.name(), now(), U.lng(p.get("id")));
        } else if (decision.equals("reject")) {
            String reason = U.clip(in.get("reason"), 200);
            if (reason.isEmpty()) throw new ApiException(400, "Reject காரணம் தேவை");
            db.update("UPDATE purchase_orders SET status='rejected', approved_by=?, approved_at=?, reject_reason=? WHERE id=?",
                    c.name(), now(), reason, U.lng(p.get("id")));
        } else {
            throw new ApiException(400, "decision: approve / reject");
        }
        audit("po", U.lng(p.get("id")), decision, c, pono);
        return poDetail(pono);
    }

    public Map<String, Object> cancelPo(Ctx c, String poNumber, String reason) {
        c.needCreate();
        Map<String, Object> p = db.one("SELECT * FROM purchase_orders WHERE po_number = ?", poNumber);
        if (p == null) throw new ApiException(404, "PO இல்லை");
        if (U.str(p.get("status")).equals("completed") || U.str(p.get("status")).equals("cancelled"))
            throw new ApiException(409, "PO '" + p.get("status") + "' — ரத்து செய்ய முடியாது");
        db.update("UPDATE purchase_orders SET status='cancelled', reject_reason=? WHERE id=?", U.clip(reason, 200), U.lng(p.get("id")));
        audit("po", U.lng(p.get("id")), "cancel", c, poNumber + " " + reason);
        return poDetail(poNumber);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> receiveGoods(Ctx c, Map<String, Object> in) {
        c.needCreate();
        String pono = U.str(in.get("poNumber"));
        Map<String, Object> p = db.one("SELECT * FROM purchase_orders WHERE po_number = ?", pono);
        if (p == null) throw new ApiException(404, "PO இல்லை");
        if (!List.of("approved", "sent", "partial").contains(U.str(p.get("status"))))
            throw new ApiException(409, "Approved/sent/partial PO-க்கு மட்டுமே receive செய்யலாம்");
        Object raw = in.get("receipts");
        if (!(raw instanceof List<?> recs)) throw new ApiException(400, "Receipts இல்லை");
        for (Object ro : recs) {
            Map<String, Object> rc = (Map<String, Object>) ro;
            String size = U.str(rc.get("size"));
            int qty = U.intv(rc.get("qty"));
            Map<String, Object> line = db.one("SELECT * FROM po_lines WHERE po_id=? AND size=?", U.lng(p.get("id")), size);
            if (line == null) throw new ApiException(400, "Size '" + size + "' வரி இல்ல");
            long newr = Math.min(U.lng(line.get("qty")), U.lng(line.get("received_qty")) + qty);
            db.update("UPDATE po_lines SET received_qty=? WHERE id=?", newr, U.lng(line.get("id")));
        }
        Map<String, Object> agg = db.one("SELECT COALESCE(SUM(qty),0) AS q, COALESCE(SUM(received_qty),0) AS r FROM po_lines WHERE po_id=?", U.lng(p.get("id")));
        String status = U.lng(agg.get("r")) >= U.lng(agg.get("q")) ? "completed" : "partial";
        db.update("UPDATE purchase_orders SET status=? WHERE id=?", status, U.lng(p.get("id")));
        audit("po", U.lng(p.get("id")), "receive", c, pono + " " + agg.get("r") + "/" + agg.get("q"));
        return poDetail(pono);
    }

    // ================================================================ reports + dashboard
    public Map<String, Object> report(String report, String period) {
        List<Map<String, Object>> rows;
        switch (report) {
            case "supplier_wise" -> rows = db.query("SELECT s.name AS supplier, COUNT(DISTINCT p.id) AS pos, COALESCE(SUM(l.qty),0) AS qty, "
                    + "COALESCE(SUM(l.amount),0) AS value FROM purchase_orders p JOIN purchase_entries e ON e.id=p.entry_id "
                    + "JOIN suppliers s ON s.id=e.supplier_id JOIN po_lines l ON l.po_id=p.id WHERE p.status IN " + COMMITTED + " GROUP BY s.name ORDER BY value DESC");
            case "item_wise" -> rows = db.query("SELECT it.item_code, it.description, COALESCE(SUM(l.qty),0) AS qty, COALESCE(SUM(l.amount),0) AS value "
                    + "FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id JOIN items it ON it.id=l.item_id WHERE p.status IN " + COMMITTED + " GROUP BY it.item_code, it.description ORDER BY qty DESC");
            case "branch_wise" -> rows = db.query("SELECT br.code AS branch, br.name, COUNT(DISTINCT p.id) AS pos, COALESCE(SUM(l.qty),0) AS qty, "
                    + "COALESCE(SUM(l.amount),0) AS value FROM purchase_orders p JOIN branches br ON br.id=p.branch_id JOIN po_lines l ON l.po_id=p.id "
                    + "WHERE p.status IN " + COMMITTED + " GROUP BY br.code, br.name ORDER BY br.id");
            case "size_wise" -> rows = db.query("SELECT l.size, COALESCE(SUM(l.qty),0) AS qty FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id "
                    + "WHERE p.status IN " + COMMITTED + " GROUP BY l.size ORDER BY l.size");
            case "rate_comparison" -> rows = db.query("SELECT it.item_code, s.name AS supplier, MIN(l.rate) AS min_rate, MAX(l.rate) AS max_rate, "
                    + "ROUND(AVG(l.rate),2) AS avg_rate FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id JOIN purchase_entries e ON e.id=p.entry_id "
                    + "JOIN suppliers s ON s.id=e.supplier_id JOIN items it ON it.id=l.item_id GROUP BY it.item_code, s.name ORDER BY it.item_code, avg_rate");
            case "ordered_vs_received" -> rows = db.query("SELECT p.po_number, br.code AS branch, COALESCE(SUM(l.qty),0) AS ordered, "
                    + "COALESCE(SUM(l.received_qty),0) AS received, COALESCE(SUM(l.qty-l.received_qty),0) AS pending, p.status "
                    + "FROM purchase_orders p JOIN branches br ON br.id=p.branch_id JOIN po_lines l ON l.po_id=p.id WHERE p.status IN " + COMMITTED
                    + " GROUP BY p.po_number, br.code, p.status HAVING pending > 0 ORDER BY p.po_number");
            case "user_wise" -> rows = db.query("SELECT ordered_by AS user, COUNT(*) AS pos FROM purchase_orders GROUP BY ordered_by ORDER BY pos DESC");
            case "approval" -> rows = db.query("SELECT status, COUNT(*) AS pos FROM purchase_orders GROUP BY status ORDER BY status");
            case "cancelled" -> rows = db.query("SELECT po_number, reject_reason, ordered_by FROM purchase_orders WHERE status='cancelled' ORDER BY id DESC");
            case "budget_vs_ordered" -> {
                rows = db.query("SELECT bu.period, br.code AS branch, it.item_code AS item, bu.budget_qty, "
                        + "(SELECT COALESCE(SUM(l.qty),0) FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id JOIN purchase_entries e ON e.id=p.entry_id "
                        + " WHERE l.item_id=bu.item_id AND p.branch_id=bu.branch_id AND e.period=bu.period AND p.status IN " + COMMITTED + ") AS committed "
                        + "FROM budgets bu JOIN branches br ON br.id=bu.branch_id JOIN items it ON it.id=bu.item_id WHERE bu.status='approved'"
                        + (U.blank(period) ? "" : " AND bu.period = ?") + " ORDER BY bu.period, br.id",
                        U.blank(period) ? new Object[]{} : new Object[]{period});
                for (Map<String, Object> r : rows) {
                    long bq = U.lng(r.get("budget_qty")), cq = U.lng(r.get("committed"));
                    r.put("balance", bq - cq);
                    r.put("utilizationPct", bq == 0 ? null : U.round2(cq * 100.0 / bq));
                    r.put("status", bq - cq < 0 ? "OVER BUDGET" : (bq - cq == 0 ? "At limit" : "Within budget"));
                }
            }
            default -> throw new ApiException(400, "Unknown report: " + report);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("report", report);
        o.put("period", U.blank(period) ? null : period);
        o.put("rows", rows);
        return o;
    }

    public Map<String, Object> dashboard(Ctx c) {
        String today = today().toString();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("todayEntries", U.lng(db.one("SELECT COUNT(*) AS n FROM purchase_entries WHERE created_at LIKE ?", today + "%").get("n")));
        o.put("openPos", U.lng(db.one("SELECT COUNT(*) AS n FROM purchase_orders WHERE status IN " + COMMITTED).get("n")));
        o.put("pendingApprovals", U.lng(db.one("SELECT COUNT(*) AS n FROM purchase_orders WHERE status='pending'").get("n")));
        o.put("overBudgetPos", U.lng(db.one("SELECT COUNT(*) AS n FROM purchase_orders WHERE over_budget=1 AND status IN " + COMMITTED).get("n")));
        o.put("committedQty", U.lng(db.one("SELECT COALESCE(SUM(l.qty),0) AS q FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id WHERE p.status IN " + COMMITTED).get("q")));
        o.put("committedValue", U.round2(U.dbl(db.one("SELECT COALESCE(SUM(l.amount),0) AS v FROM po_lines l JOIN purchase_orders p ON p.id=l.po_id WHERE p.status IN " + COMMITTED).get("v"))));
        o.put("branches", U.lng(db.one("SELECT COUNT(*) AS n FROM branches").get("n")));
        o.put("suppliers", U.lng(db.one("SELECT COUNT(*) AS n FROM suppliers").get("n")));
        o.put("items", U.lng(db.one("SELECT COUNT(*) AS n FROM items").get("n")));
        return o;
    }

    /** bootstrap for the app. */
    public Map<String, Object> data(Ctx c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("branches", branches());
        o.put("suppliers", suppliers());
        o.put("items", items());
        o.put("canCreate", c.canCreate());
        o.put("canApprove", c.canApprove());
        o.put("isAdmin", c.admin());
        o.put("budgetEnforcement", enforcement);
        return o;
    }

    // ---------------------------------------------------------------- helpers
    private Map<String, Object> obj(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private synchronized long nextCounter(String name, long start) {
        Map<String, Object> r = db.one("SELECT val FROM counters WHERE name = ?", name);
        long val = r == null ? start + 1 : U.lng(r.get("val")) + 1;
        if (r == null) db.update("INSERT INTO counters (name, val) VALUES (?, ?)", name, val);
        else db.update("UPDATE counters SET val = ? WHERE name = ?", val, name);
        return val;
    }

    private void audit(String entity, long id, String action, Ctx c, String detail) {
        db.update("INSERT INTO audit_history (entity, entity_id, action, actor, detail, at) VALUES (?, ?, ?, ?, ?, ?)",
                entity, id, action, c.name(), U.clip(detail, 255), now());
    }
}
