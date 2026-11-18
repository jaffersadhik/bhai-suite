package com.mschool.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fee heads, per-class fee structure, concessions, receipts, dues. All amounts are whole rupees. */
public class FeeService extends Base {
    static final String[] MODES = {"Cash", "UPI", "Card", "Cheque", "Bank"};

    public FeeService(Db db, ZoneId zone) {
        super(db, zone);
    }

    // ---------------------------------------------------------------- heads and structure

    public Map<String, Object> saveHead(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String name = U.req(b.get("name"), 60, "கட்டண வகை");
        Map<String, Object> clash = db.one("SELECT id FROM fee_heads WHERE inst_id = ? AND name = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, name, id} : new Object[]{inst, name});
        if (clash != null) {
            throw new ApiException(409, "இந்த கட்டண வகை ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            long order = U.n(db.one("SELECT COALESCE(MAX(sort_order), 0) + 1 n FROM fee_heads WHERE inst_id = ?", inst), "n");
            return U.map("id", db.insert("INSERT INTO fee_heads (inst_id, name, sort_order) VALUES (?,?,?)", inst, name, order));
        }
        belongs("fee_heads", id, inst, "கட்டண வகை");
        db.update("UPDATE fee_heads SET name = ? WHERE id = ?", name, id);
        db.update("UPDATE fee_receipt_lines SET head_name = ? WHERE head_id = ?", name, id);
        return U.map("id", id);
    }

    public void deleteHead(Ctx c, long id) {
        c.needHead();
        long inst = c.inst();
        belongs("fee_heads", id, inst, "கட்டண வகை");
        if (db.one("SELECT head_id FROM fee_structure WHERE head_id = ? LIMIT 1", id) != null
                || db.one("SELECT head_id FROM fee_receipt_lines WHERE head_id = ? LIMIT 1", id) != null) {
            throw new ApiException(409, "இந்த கட்டண வகை பயன்பாட்டில் உள்ளது, நீக்க முடியாது");
        }
        db.update("DELETE FROM fee_heads WHERE id = ?", id);
    }

    public List<Map<String, Object>> structure(Ctx c, long yearId, long classId) {
        c.needDesk();
        long inst = c.inst();
        belongs("academic_years", yearId, inst, "கல்வியாண்டு");
        belongs("classes", classId, inst, "வகுப்பு");
        return db.query("SELECT h.id head_id, h.name, COALESCE(fs.amount, 0) amount FROM fee_heads h "
                + "LEFT JOIN fee_structure fs ON fs.head_id = h.id AND fs.year_id = ? AND fs.class_id = ? "
                + "WHERE h.inst_id = ? ORDER BY h.sort_order, h.id", yearId, classId, inst);
    }

    /** Sets the fees of a class for a year; alsoClassIds copies the same fees to other classes. */
    public Map<String, Object> saveStructure(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        long yearId = U.l(b.get("yearId"), 0);
        long classId = U.l(b.get("classId"), 0);
        belongs("academic_years", yearId, inst, "கல்வியாண்டு");
        belongs("classes", classId, inst, "வகுப்பு");
        Set<Long> targets = new LinkedHashSet<>();
        targets.add(classId);
        for (Long x : U.longs(b.get("alsoClassIds"))) {
            belongs("classes", x, inst, "வகுப்பு");
            targets.add(x);
        }
        List<long[]> items = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Map<String, Object> m : U.list(b.get("items"))) {
            long head = U.l(m.get("headId"), 0);
            long amount = U.l(m.get("amount"), 0);
            if (amount < 0 || amount > 100_000_000L) {
                throw new ApiException(400, "தொகை தவறு");
            }
            belongs("fee_heads", head, inst, "கட்டண வகை");
            if (!seen.add(head)) {
                throw new ApiException(400, "ஒரே கட்டண வகை இரு முறை உள்ளது");
            }
            if (amount > 0) {
                items.add(new long[]{head, amount});
            }
        }
        db.tx(() -> {
            for (Long cid : targets) {
                db.update("DELETE FROM fee_structure WHERE inst_id = ? AND year_id = ? AND class_id = ?", inst, yearId, cid);
                List<Object[]> rows = new ArrayList<>();
                for (long[] it : items) {
                    rows.add(new Object[]{inst, yearId, cid, it[0], it[1]});
                }
                db.batch("INSERT INTO fee_structure (inst_id, year_id, class_id, head_id, amount) VALUES (?,?,?,?,?)", rows);
            }
            return null;
        });
        return U.map("classes", targets.size());
    }

    // ---------------------------------------------------------------- student statement

    private List<Map<String, Object>> balances(long inst, long studentId, long yearId) {
        Map<String, Object> enr = db.one("SELECT class_id FROM enrollments WHERE year_id = ? AND student_id = ?", yearId, studentId);
        if (enr == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> rows = db.query(
                "SELECT h.id head_id, h.name, fs.amount fee, COALESCE(cn.amount, 0) concession, "
                        + "COALESCE((SELECT SUM(l.amount) FROM fee_receipt_lines l JOIN fee_receipts r ON r.id = l.receipt_id "
                        + "WHERE r.student_id = ? AND r.year_id = ? AND r.status = 'ok' AND l.head_id = h.id), 0) paid "
                        + "FROM fee_structure fs JOIN fee_heads h ON h.id = fs.head_id "
                        + "LEFT JOIN fee_concessions cn ON cn.year_id = fs.year_id AND cn.student_id = ? AND cn.head_id = fs.head_id "
                        + "WHERE fs.year_id = ? AND fs.class_id = ? AND fs.inst_id = ? ORDER BY h.sort_order, h.id",
                studentId, yearId, studentId, yearId, U.n(enr, "class_id"), inst);
        for (Map<String, Object> r : rows) {
            long bal = U.n(r, "fee") - U.n(r, "concession") - U.n(r, "paid");
            r.put("balance", Math.max(0, bal));
        }
        return rows;
    }

    public Map<String, Object> statement(Ctx c, long studentId, Long yearOpt) {
        c.needDesk();
        long inst = c.inst();
        Map<String, Object> st = mustOne("மாணவர்", "SELECT id, adm_no, name, phone, father_name FROM students WHERE id = ? AND inst_id = ?", studentId, inst);
        long yearId = yearOpt != null ? yearOpt : needYear(inst);
        Map<String, Object> year = mustOne("கல்வியாண்டு", "SELECT id, name FROM academic_years WHERE id = ? AND inst_id = ?", yearId, inst);
        Map<String, Object> enr = db.one(
                "SELECT cl.name class_name, sc.name section_name FROM enrollments e JOIN classes cl ON cl.id = e.class_id "
                        + "LEFT JOIN sections sc ON sc.id = e.section_id WHERE e.year_id = ? AND e.student_id = ?", yearId, studentId);
        List<Map<String, Object>> heads = balances(inst, studentId, yearId);
        long fee = 0;
        long conc = 0;
        long paid = 0;
        long bal = 0;
        for (Map<String, Object> r : heads) {
            fee += U.n(r, "fee");
            conc += U.n(r, "concession");
            paid += U.n(r, "paid");
            bal += U.n(r, "balance");
        }
        List<Map<String, Object>> receipts = db.query(
                "SELECT id, receipt_no, created_at, total, pay_mode, status FROM fee_receipts WHERE student_id = ? AND year_id = ? ORDER BY id DESC", studentId, yearId);
        return U.map("student", st, "year", year, "enrollment", enr, "heads", heads, "receipts", receipts,
                "totals", U.map("fee", fee, "concession", conc, "paid", paid, "balance", bal));
    }

    public Map<String, Object> saveConcession(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        long studentId = U.l(b.get("studentId"), 0);
        long headId = U.l(b.get("headId"), 0);
        long yearId = b.get("yearId") == null ? needYear(inst) : U.l(b.get("yearId"), 0);
        long amount = U.l(b.get("amount"), 0);
        belongs("students", studentId, inst, "மாணவர்");
        belongs("academic_years", yearId, inst, "கல்வியாண்டு");
        Map<String, Object> fee = db.one(
                "SELECT fs.amount FROM enrollments e JOIN fee_structure fs ON fs.year_id = e.year_id AND fs.class_id = e.class_id AND fs.head_id = ? "
                        + "WHERE e.year_id = ? AND e.student_id = ?", headId, yearId, studentId);
        if (fee == null) {
            throw new ApiException(400, "இந்த மாணவருக்கு இந்த கட்டண வகை இல்லை");
        }
        if (amount < 0 || amount > U.n(fee, "amount")) {
            throw new ApiException(400, "சலுகை தொகை 0 முதல் " + U.n(fee, "amount") + " வரை இருக்க வேண்டும்");
        }
        if (amount == 0) {
            db.update("DELETE FROM fee_concessions WHERE year_id = ? AND student_id = ? AND head_id = ?", yearId, studentId, headId);
        } else {
            db.update("INSERT INTO fee_concessions (inst_id, year_id, student_id, head_id, amount) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE amount = VALUES(amount)",
                    inst, yearId, studentId, headId, amount);
        }
        return U.map("amount", amount);
    }

    // ---------------------------------------------------------------- receipts

    public Map<String, Object> createReceipt(Ctx c, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        long studentId = U.l(b.get("studentId"), 0);
        long yearId = b.get("yearId") == null ? needYear(inst) : U.l(b.get("yearId"), 0);
        String mode = U.pick(b.get("mode"), "Cash", "பணம் செலுத்தும் முறை", MODES);
        String note = U.s(b.get("note"), 255, "குறிப்பு");
        belongs("students", studentId, inst, "மாணவர்");
        belongs("academic_years", yearId, inst, "கல்வியாண்டு");
        Map<Long, Long> wanted = new LinkedHashMap<>();
        for (Map<String, Object> m : U.list(b.get("lines"))) {
            long head = U.l(m.get("headId"), 0);
            long amount = U.l(m.get("amount"), 0);
            if (amount < 0) {
                throw new ApiException(400, "தொகை தவறு");
            }
            if (amount == 0) {
                continue;
            }
            if (wanted.put(head, amount) != null) {
                throw new ApiException(400, "ஒரே கட்டண வகை இரு முறை உள்ளது");
            }
        }
        if (wanted.isEmpty()) {
            throw new ApiException(400, "செலுத்த வேண்டிய தொகையை உள்ளிடவும்");
        }
        long receiptId = db.tx(() -> {
            db.one("SELECT id FROM students WHERE id = ? FOR UPDATE", studentId);
            Map<Long, Map<String, Object>> bal = new HashMap<>();
            for (Map<String, Object> r : balances(inst, studentId, yearId)) {
                bal.put(U.n(r, "head_id"), r);
            }
            long total = 0;
            for (Map.Entry<Long, Long> e : wanted.entrySet()) {
                Map<String, Object> r = bal.get(e.getKey());
                if (r == null) {
                    throw new ApiException(400, "இந்த மாணவருக்கு அந்த கட்டண வகை இல்லை");
                }
                if (e.getValue() > U.n(r, "balance")) {
                    throw new ApiException(400, r.get("name") + ": பாக்கி ₹" + U.n(r, "balance") + " மட்டுமே");
                }
                total += e.getValue();
            }
            db.update("UPDATE institutions SET receipt_seq = receipt_seq + 1 WHERE id = ?", inst);
            long seq = U.n(db.one("SELECT receipt_seq FROM institutions WHERE id = ?", inst), "receipt_seq");
            long rid = db.insert("INSERT INTO fee_receipts (inst_id, receipt_seq, receipt_no, year_id, student_id, created_at, total, pay_mode, note, created_by, status) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?, 'ok')",
                    inst, seq, "R-" + pad(seq, 6), yearId, studentId, LocalDateTime.now(zone), total, mode, note, c.name());
            int line = 1;
            for (Map.Entry<Long, Long> e : wanted.entrySet()) {
                db.update("INSERT INTO fee_receipt_lines (receipt_id, line_no, head_id, head_name, amount) VALUES (?,?,?,?,?)",
                        rid, line++, e.getKey(), bal.get(e.getKey()).get("name"), e.getValue());
            }
            return rid;
        });
        return receipt(c, receiptId);
    }

    public Map<String, Object> receipt(Ctx c, long id) {
        c.needDesk();
        Map<String, Object> r = mustOne("ரசீது",
                "SELECT r.id, r.receipt_no, r.created_at, r.total, r.pay_mode, r.note, r.created_by, r.status, r.cancel_reason, r.year_id, y.name year_name, "
                        + "st.id student_id, st.name student_name, st.adm_no, st.phone, st.father_name, cl.name class_name, sc.name section_name "
                        + "FROM fee_receipts r JOIN students st ON st.id = r.student_id JOIN academic_years y ON y.id = r.year_id "
                        + "LEFT JOIN enrollments e ON e.student_id = st.id AND e.year_id = r.year_id "
                        + "LEFT JOIN classes cl ON cl.id = e.class_id LEFT JOIN sections sc ON sc.id = e.section_id "
                        + "WHERE r.id = ? AND r.inst_id = ?", id, c.inst());
        r.put("lines", db.query("SELECT head_id, head_name, amount FROM fee_receipt_lines WHERE receipt_id = ? ORDER BY line_no", id));
        return r;
    }

    public List<Map<String, Object>> receipts(Ctx c, Map<String, String> q) {
        c.needDesk();
        List<Object> args = new ArrayList<>();
        args.add(c.inst());
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.receipt_no, r.created_at, r.total, r.pay_mode, r.status, r.created_by, st.id student_id, st.name student_name, st.adm_no "
                        + "FROM fee_receipts r JOIN students st ON st.id = r.student_id WHERE r.inst_id = ?");
        LocalDate d = U.date(q.get("date"));
        if (d != null) {
            sql.append(" AND r.created_at >= ? AND r.created_at < ?");
            args.add(d.atStartOfDay());
            args.add(d.plusDays(1).atStartOfDay());
        }
        if (U.s(q.get("studentId")) != null) {
            sql.append(" AND r.student_id = ?");
            args.add(U.l(q.get("studentId"), 0));
        }
        if (U.s(q.get("yearId")) != null) {
            sql.append(" AND r.year_id = ?");
            args.add(U.l(q.get("yearId"), 0));
        }
        sql.append(" ORDER BY r.id DESC LIMIT 300");
        return db.query(sql.toString(), args.toArray());
    }

    public void cancelReceipt(Ctx c, long id, String reason) {
        c.needDesk();
        String why = U.req(reason, 200, "ரத்து காரணம்");
        Map<String, Object> r = mustOne("ரசீது", "SELECT id, status FROM fee_receipts WHERE id = ? AND inst_id = ?", id, c.inst());
        if (!"ok".equals(r.get("status"))) {
            throw new ApiException(409, "இந்த ரசீது ஏற்கனவே ரத்து செய்யப்பட்டது");
        }
        db.update("UPDATE fee_receipts SET status = 'cancelled', cancelled_at = ?, cancel_reason = ? WHERE id = ?",
                LocalDateTime.now(zone), why + " (" + c.name() + ")", id);
    }

    // ---------------------------------------------------------------- dues and reports

    /** Students with a balance. yearId 0 = all years together (carried-over dues included). */
    public Map<String, Object> pending(Ctx c, Map<String, String> q) {
        c.needDesk();
        long inst = c.inst();
        long yearId = U.l(q.get("yearId"), -1);
        if (yearId < 0) {
            yearId = needYear(inst);
        }
        List<Object> args = new ArrayList<>();
        args.add(inst);
        StringBuilder where = new StringBuilder(" WHERE e.inst_id = ?");
        if (yearId > 0) {
            where.append(" AND e.year_id = ?");
            args.add(yearId);
        }
        if (U.s(q.get("classId")) != null) {
            where.append(" AND e.class_id = ?");
            args.add(U.l(q.get("classId"), 0));
        }
        if (U.s(q.get("sectionId")) != null) {
            where.append(" AND e.section_id = ?");
            args.add(U.l(q.get("sectionId"), 0));
        }
        List<Map<String, Object>> rows = db.query(
                "SELECT st.id, st.name, st.adm_no, st.phone, cl.name class_name, sc.name section_name, cl.sort_order, e.year_id, "
                        + "SUM(fs.amount) fee, SUM(COALESCE(cn.amount, 0)) concession "
                        + "FROM enrollments e JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "JOIN classes cl ON cl.id = e.class_id LEFT JOIN sections sc ON sc.id = e.section_id "
                        + "JOIN fee_structure fs ON fs.year_id = e.year_id AND fs.class_id = e.class_id "
                        + "LEFT JOIN fee_concessions cn ON cn.year_id = fs.year_id AND cn.student_id = st.id AND cn.head_id = fs.head_id"
                        + where + " GROUP BY st.id, st.name, st.adm_no, st.phone, cl.name, sc.name, cl.sort_order, e.year_id",
                args.toArray());
        Map<Long, Long> paidBy = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT student_id, SUM(total) paid FROM fee_receipts WHERE inst_id = ? AND status = 'ok'"
                + (yearId > 0 ? " AND year_id = ?" : "") + " GROUP BY student_id", yearId > 0 ? new Object[]{inst, yearId} : new Object[]{inst})) {
            paidBy.put(U.n(r, "student_id"), U.n(r, "paid"));
        }
        Map<Long, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            long sid = U.n(r, "id");
            Map<String, Object> m = merged.get(sid);
            if (m == null) {
                m = U.map("id", sid, "name", r.get("name"), "adm_no", r.get("adm_no"), "phone", r.get("phone"),
                        "class_name", r.get("class_name"), "section_name", r.get("section_name"),
                        "sort_order", U.n(r, "sort_order"), "year_id", U.n(r, "year_id"), "fee", 0L, "concession", 0L);
                merged.put(sid, m);
            } else if (U.n(r, "year_id") > U.n(m, "year_id")) {
                m.put("class_name", r.get("class_name"));
                m.put("section_name", r.get("section_name"));
                m.put("sort_order", U.n(r, "sort_order"));
                m.put("year_id", U.n(r, "year_id"));
            }
            m.put("fee", U.n(m, "fee") + U.n(r, "fee"));
            m.put("concession", U.n(m, "concession") + U.n(r, "concession"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        long total = 0;
        for (Map<String, Object> m : merged.values()) {
            long paid = paidBy.getOrDefault(U.n(m, "id"), 0L);
            long bal = U.n(m, "fee") - U.n(m, "concession") - paid;
            if (bal > 0) {
                m.put("paid", paid);
                m.put("balance", bal);
                total += bal;
                out.add(m);
            }
        }
        out.sort(Comparator.comparingLong((Map<String, Object> m) -> U.n(m, "sort_order"))
                .thenComparing(m -> String.valueOf(m.get("section_name")))
                .thenComparing(m -> String.valueOf(m.get("name"))));
        return U.map("rows", out, "total", total, "count", out.size());
    }

    public Map<String, Object> report(Ctx c, String date) {
        c.needDesk();
        long inst = c.inst();
        LocalDate d = U.s(date) == null ? today() : U.date(date);
        LocalDateTime from = d.atStartOfDay();
        LocalDateTime to = from.plusDays(1);
        List<Map<String, Object>> byMode = db.query(
                "SELECT pay_mode, COUNT(*) n, SUM(total) amount FROM fee_receipts WHERE inst_id = ? AND status = 'ok' AND created_at >= ? AND created_at < ? GROUP BY pay_mode ORDER BY pay_mode",
                inst, from, to);
        List<Map<String, Object>> byHead = db.query(
                "SELECT l.head_name, SUM(l.amount) amount FROM fee_receipt_lines l JOIN fee_receipts r ON r.id = l.receipt_id "
                        + "WHERE r.inst_id = ? AND r.status = 'ok' AND r.created_at >= ? AND r.created_at < ? GROUP BY l.head_name ORDER BY SUM(l.amount) DESC",
                inst, from, to);
        YearMonth ym = YearMonth.from(d);
        Map<String, Object> month = db.one(
                "SELECT COUNT(*) n, COALESCE(SUM(total), 0) amount FROM fee_receipts WHERE inst_id = ? AND status = 'ok' AND created_at >= ? AND created_at < ?",
                inst, ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay());
        long day = 0;
        long count = 0;
        for (Map<String, Object> r : byMode) {
            day += U.n(r, "amount");
            count += U.n(r, "n");
        }
        long cancelled = U.n(db.one("SELECT COUNT(*) n FROM fee_receipts WHERE inst_id = ? AND status = 'cancelled' AND cancelled_at >= ? AND cancelled_at < ?", inst, from, to), "n");
        return U.map("date", d.toString(), "day", U.map("amount", day, "count", count, "cancelled", cancelled),
                "month", U.map("amount", U.n(month, "amount"), "count", U.n(month, "n")), "byMode", byMode, "byHead", byHead);
    }
}
