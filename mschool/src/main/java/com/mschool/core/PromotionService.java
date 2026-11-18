package com.mschool.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Year-end class change. Teachers / office mark pass or fail for each student; the head then runs the promotion:
 * pass -> next class of the class chain (or graduated when there is none), fail -> same class again.
 * A new academic year is created and becomes current. Last year's enrollments (with results) are kept as history.
 */
public class PromotionService extends Base {

    public PromotionService(Db db, ZoneId zone) {
        super(db, zone);
    }

    /** Class-wise totals of marked results for the current year. */
    public Map<String, Object> summary(Ctx c) {
        long inst = c.inst();
        long yearId = needYear(inst);
        List<Map<String, Object>> rows = db.query(
                "SELECT cl.id, cl.name, nx.name next_name, COUNT(st.id) total, "
                        + "SUM(CASE WHEN st.id IS NOT NULL AND e.result = 'pass' THEN 1 ELSE 0 END) pass_n, "
                        + "SUM(CASE WHEN st.id IS NOT NULL AND e.result = 'fail' THEN 1 ELSE 0 END) fail_n "
                        + "FROM classes cl LEFT JOIN classes nx ON nx.id = cl.next_class_id "
                        + "LEFT JOIN enrollments e ON e.class_id = cl.id AND e.year_id = ? "
                        + "LEFT JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "WHERE cl.inst_id = ? GROUP BY cl.id, cl.name, cl.sort_order, nx.name ORDER BY cl.sort_order, cl.name", yearId, inst);
        long total = 0;
        long marked = 0;
        for (Map<String, Object> r : rows) {
            total += U.n(r, "total");
            marked += U.n(r, "pass_n") + U.n(r, "fail_n");
        }
        return U.map("year", currentYear(inst), "classes", rows, "total", total, "marked", marked);
    }

    private void teacherOwns(Ctx c, Long classTeacherId) {
        if (c.teacher() && (c.staffId() == null || classTeacherId == null || !classTeacherId.equals(c.staffId()))) {
            throw new ApiException(403, "நீங்கள் வகுப்பாசிரியராக உள்ள பிரிவுக்கு மட்டும் முடிவு குறிக்கலாம்");
        }
    }

    public Map<String, Object> sheet(Ctx c, long sectionId) {
        long inst = c.inst();
        long yearId = needYear(inst);
        Map<String, Object> sec = mustOne("பிரிவு",
                "SELECT sc.id, sc.class_teacher_id, sc.name section_name, cl.name class_name, nx.name next_name "
                        + "FROM sections sc JOIN classes cl ON cl.id = sc.class_id LEFT JOIN classes nx ON nx.id = cl.next_class_id "
                        + "WHERE sc.id = ? AND sc.inst_id = ?", sectionId, inst);
        teacherOwns(c, U.lo(sec.get("class_teacher_id")));
        List<Map<String, Object>> students = db.query(
                "SELECT st.id, st.name, st.adm_no, e.roll_no, e.result FROM enrollments e "
                        + "JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "WHERE e.inst_id = ? AND e.year_id = ? AND e.section_id = ? ORDER BY e.roll_no, st.name", inst, yearId, sectionId);
        return U.map("section", sec, "students", students);
    }

    public Map<String, Object> setResults(Ctx c, Map<String, Object> b) {
        long inst = c.inst();
        long yearId = needYear(inst);
        int n = 0;
        for (Map<String, Object> m : U.list(b.get("marks"))) {
            long sid = U.l(m.get("studentId"), 0);
            String res = U.pick(m.get("result"), null, "முடிவு", "pass", "fail");
            Map<String, Object> e = db.one(
                    "SELECT e.id, sc.class_teacher_id FROM enrollments e JOIN sections sc ON sc.id = e.section_id WHERE e.inst_id = ? AND e.year_id = ? AND e.student_id = ?",
                    inst, yearId, sid);
            if (e == null) {
                throw new ApiException(404, "மாணவர் பதிவு கிடைக்கவில்லை");
            }
            teacherOwns(c, U.lo(e.get("class_teacher_id")));
            db.update("UPDATE enrollments SET result = ? WHERE id = ?", res, U.n(e, "id"));
            n++;
        }
        return U.map("saved", n);
    }

    public Map<String, Object> run(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Map<String, Object> cur = currentYear(inst);
        if (cur == null) {
            throw new ApiException(400, "தற்போதைய கல்வியாண்டு இல்லை");
        }
        long oldYear = U.n(cur, "id");
        String name = U.req(b.get("name"), 20, "புதிய கல்வியாண்டு பெயர்");
        LocalDate start = U.reqDate(b.get("start"), "தொடக்க தேதி");
        LocalDate end = U.reqDate(b.get("end"), "முடிவு தேதி");
        if (!end.isAfter(start)) {
            throw new ApiException(400, "முடிவு தேதி தொடக்க தேதிக்கு பின் இருக்க வேண்டும்");
        }
        if (db.one("SELECT id FROM academic_years WHERE inst_id = ? AND name = ?", inst, name) != null) {
            throw new ApiException(409, "இந்த கல்வியாண்டு ஏற்கனவே உள்ளது");
        }
        String defaultResult = U.pick(b.get("defaultResult"), null, "இயல்பு முடிவு", "pass", "fail");
        boolean copyFees = U.b(b.get("copyFees"));
        List<Map<String, Object>> list = db.query(
                "SELECT e.id, e.student_id, e.class_id, e.section_id, e.result, cl.next_class_id, sc.name section_name "
                        + "FROM enrollments e JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "JOIN classes cl ON cl.id = e.class_id JOIN sections sc ON sc.id = e.section_id "
                        + "WHERE e.inst_id = ? AND e.year_id = ? ORDER BY cl.sort_order, sc.name, e.roll_no, st.name", inst, oldYear);
        long unmarked = 0;
        for (Map<String, Object> e : list) {
            if (e.get("result") == null) {
                unmarked++;
            }
        }
        if (unmarked > 0 && defaultResult == null) {
            throw new ApiException(409, unmarked + " மாணவர்களுக்கு தேர்ச்சி / தோல்வி குறிக்கப்படவில்லை. அனைவரையும் குறிக்கவும் அல்லது இயல்பு முடிவை தேர்வு செய்யவும்");
        }
        return db.tx(() -> {
            long newYear = db.insert("INSERT INTO academic_years (inst_id, name, start_date, end_date, is_current) VALUES (?,?,?,?,0)", inst, name, start, end);
            // sections by class: name(lower) -> id, plus the first one
            Map<Long, Map<String, Long>> byName = new HashMap<>();
            Map<Long, Long> first = new HashMap<>();
            for (Map<String, Object> s : db.query("SELECT id, class_id, name FROM sections WHERE inst_id = ? ORDER BY name", inst)) {
                long cid = U.n(s, "class_id");
                byName.computeIfAbsent(cid, k -> new HashMap<>()).put(((String) s.get("name")).toLowerCase(), U.n(s, "id"));
                first.putIfAbsent(cid, U.n(s, "id"));
            }
            Map<Long, Integer> roll = new HashMap<>();
            int promoted = 0;
            int repeated = 0;
            int graduated = 0;
            for (Map<String, Object> e : list) {
                String res = e.get("result") == null ? defaultResult : (String) e.get("result");
                if (e.get("result") == null) {
                    db.update("UPDATE enrollments SET result = ? WHERE id = ?", res, U.n(e, "id"));
                }
                long sid = U.n(e, "student_id");
                long toClass;
                long toSection;
                if ("pass".equals(res)) {
                    Long next = U.lo(e.get("next_class_id"));
                    if (next == null) {
                        db.update("UPDATE students SET status = 'graduated' WHERE id = ?", sid);
                        graduated++;
                        continue;
                    }
                    toClass = next;
                    Long same = byName.getOrDefault(next, Map.of()).get(((String) e.get("section_name")).toLowerCase());
                    Long fallback = first.get(next);
                    if (same != null) {
                        toSection = same;
                    } else if (fallback != null) {
                        toSection = fallback;
                    } else {
                        toSection = db.insert("INSERT INTO sections (inst_id, class_id, name) VALUES (?,?,?)", inst, next, "A");
                        first.put(next, toSection);
                        byName.computeIfAbsent(next, k -> new HashMap<>()).put("a", toSection);
                    }
                    promoted++;
                } else {
                    toClass = U.n(e, "class_id");
                    toSection = U.n(e, "section_id");
                    repeated++;
                }
                int r = roll.merge(toSection, 1, Integer::sum);
                db.insert("INSERT INTO enrollments (inst_id, year_id, student_id, class_id, section_id, roll_no) VALUES (?,?,?,?,?,?)",
                        inst, newYear, sid, toClass, toSection, r);
            }
            if (copyFees) {
                db.update("INSERT INTO fee_structure (inst_id, year_id, class_id, head_id, amount) "
                        + "SELECT inst_id, ?, class_id, head_id, amount FROM fee_structure WHERE inst_id = ? AND year_id = ?", newYear, inst, oldYear);
            }
            db.update("UPDATE academic_years SET is_current = 0 WHERE inst_id = ?", inst);
            db.update("UPDATE academic_years SET is_current = 1 WHERE id = ?", newYear);
            return U.map("yearId", newYear, "promoted", promoted, "repeated", repeated, "graduated", graduated);
        });
    }
}
