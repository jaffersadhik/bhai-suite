package com.mschool.core;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Daily attendance of students (per section) and of staff. */
public class AttendanceService extends Base {

    public AttendanceService(Db db, ZoneId zone) {
        super(db, zone);
    }

    static YearMonth month(Object o) {
        String t = U.s(o);
        if (t == null) {
            throw new ApiException(400, "மாதம் தேவை (yyyy-MM)");
        }
        try {
            return YearMonth.parse(t);
        } catch (Exception e) {
            throw new ApiException(400, "மாதம் தவறு: " + t);
        }
    }

    private LocalDate workDate(Object o) {
        LocalDate d = o == null || U.s(o) == null ? today() : U.date(o);
        if (d.isAfter(today())) {
            throw new ApiException(400, "எதிர்கால தேதிக்கு வருகை பதிவு செய்ய முடியாது");
        }
        return d;
    }

    /** Teachers mark only the sections where they are the class teacher. */
    private Map<String, Object> section(Ctx c, long sectionId) {
        Map<String, Object> s = mustOne("பிரிவு",
                "SELECT sc.id, sc.class_teacher_id, sc.name section_name, cl.name class_name FROM sections sc JOIN classes cl ON cl.id = sc.class_id WHERE sc.id = ? AND sc.inst_id = ?",
                sectionId, c.inst());
        if (c.teacher() && (c.staffId() == null || U.l(s.get("class_teacher_id"), -1) != c.staffId())) {
            throw new ApiException(403, "நீங்கள் வகுப்பாசிரியராக உள்ள பிரிவுக்கு மட்டும் வருகை பதிவு செய்யலாம்");
        }
        return s;
    }

    // ---------------------------------------------------------------- students

    public Map<String, Object> sheet(Ctx c, long sectionId, String date) {
        Map<String, Object> sec = section(c, sectionId);
        long inst = c.inst();
        long yearId = needYear(inst);
        LocalDate d = workDate(date);
        List<Map<String, Object>> students = db.query(
                "SELECT st.id, st.name, st.adm_no, st.phone, e.roll_no, a.status FROM enrollments e "
                        + "JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "LEFT JOIN student_attendance a ON a.student_id = st.id AND a.att_date = ? "
                        + "WHERE e.inst_id = ? AND e.year_id = ? AND e.section_id = ? ORDER BY e.roll_no, st.name", d, inst, yearId, sectionId);
        boolean marked = false;
        for (Map<String, Object> s : students) {
            if (s.get("status") != null) {
                marked = true;
                break;
            }
        }
        return U.map("section", sec, "date", d.toString(), "marked", marked, "students", students);
    }

    public Map<String, Object> saveSheet(Ctx c, Map<String, Object> b) {
        long sectionId = U.l(b.get("sectionId"), 0);
        section(c, sectionId);
        long inst = c.inst();
        long yearId = needYear(inst);
        LocalDate d = workDate(b.get("date"));
        Set<Long> allowed = new HashSet<>();
        for (Map<String, Object> r : db.query("SELECT e.student_id FROM enrollments e JOIN students st ON st.id = e.student_id AND st.status = 'active' WHERE e.year_id = ? AND e.section_id = ?", yearId, sectionId)) {
            allowed.add(U.n(r, "student_id"));
        }
        List<Object[]> rows = new ArrayList<>();
        int present = 0;
        int absent = 0;
        int leave = 0;
        for (Map<String, Object> m : U.list(b.get("marks"))) {
            long sid = U.l(m.get("studentId"), 0);
            if (!allowed.contains(sid)) {
                throw new ApiException(400, "இந்த பிரிவில் இல்லாத மாணவர் சேர்க்கப்பட்டுள்ளார்");
            }
            String st = U.pick(m.get("status"), null, "வருகை நிலை", "P", "A", "L");
            if (st == null) {
                throw new ApiException(400, "வருகை நிலை தேவை");
            }
            rows.add(new Object[]{d, sid, inst, sectionId, st, c.name()});
            switch (st) {
                case "P" -> present++;
                case "A" -> absent++;
                default -> leave++;
            }
        }
        if (rows.isEmpty()) {
            throw new ApiException(400, "பதிவு செய்ய மாணவர்கள் இல்லை");
        }
        db.batch("INSERT INTO student_attendance (att_date, student_id, inst_id, section_id, status, marked_by) VALUES (?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE status = VALUES(status), section_id = VALUES(section_id), marked_by = VALUES(marked_by)", rows);
        return U.map("present", present, "absent", absent, "leave", leave);
    }

    /** Section-wise totals for a day plus the list of absentees (for phone calls / WhatsApp). */
    public Map<String, Object> day(Ctx c, String date) {
        long inst = c.inst();
        long yearId = needYear(inst);
        LocalDate d = workDate(date);
        String mine = c.teacher() ? " AND sc.class_teacher_id = ?" : "";
        List<Object> a1 = new ArrayList<>(List.of(yearId, d, inst));
        List<Object> a2 = new ArrayList<>(List.of(d, inst));
        if (c.teacher()) {
            long sid = c.staffId() == null ? -1 : c.staffId();
            a1.add(sid);
            a2.add(sid);
        }
        List<Map<String, Object>> sections = db.query(
                "SELECT sc.id section_id, cl.name class_name, sc.name section_name, COUNT(st.id) total, "
                        + "SUM(CASE WHEN a.status = 'P' THEN 1 ELSE 0 END) present, "
                        + "SUM(CASE WHEN a.status = 'A' THEN 1 ELSE 0 END) absent, "
                        + "SUM(CASE WHEN a.status = 'L' THEN 1 ELSE 0 END) leave_n "
                        + "FROM sections sc JOIN classes cl ON cl.id = sc.class_id "
                        + "LEFT JOIN enrollments e ON e.section_id = sc.id AND e.year_id = ? "
                        + "LEFT JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "LEFT JOIN student_attendance a ON a.student_id = st.id AND a.att_date = ? "
                        + "WHERE sc.inst_id = ?" + mine + " GROUP BY sc.id, cl.name, sc.name, cl.sort_order ORDER BY cl.sort_order, sc.name",
                a1.toArray());
        List<Map<String, Object>> absentees = db.query(
                "SELECT st.id, st.name, st.adm_no, st.phone, cl.name class_name, sc.name section_name FROM student_attendance a "
                        + "JOIN students st ON st.id = a.student_id JOIN sections sc ON sc.id = a.section_id JOIN classes cl ON cl.id = sc.class_id "
                        + "WHERE a.status = 'A' AND st.status = 'active' AND a.att_date = ? AND a.inst_id = ?" + mine
                        + " ORDER BY cl.sort_order, sc.name, st.name",
                a2.toArray());
        long total = 0;
        long present = 0;
        long absent = 0;
        for (Map<String, Object> s : sections) {
            total += U.n(s, "total");
            present += U.n(s, "present");
            absent += U.n(s, "absent");
        }
        return U.map("date", d.toString(), "sections", sections, "absentees", absentees,
                "totals", U.map("total", total, "present", present, "absent", absent));
    }

    /** Per-student present / absent / leave counts for a month in one section. */
    public Map<String, Object> monthly(Ctx c, long sectionId, String month) {
        Map<String, Object> sec = section(c, sectionId);
        long inst = c.inst();
        long yearId = needYear(inst);
        YearMonth ym = month(month);
        List<Map<String, Object>> rows = db.query(
                "SELECT st.id, st.name, e.roll_no, "
                        + "SUM(CASE WHEN a.status = 'P' THEN 1 ELSE 0 END) present, "
                        + "SUM(CASE WHEN a.status = 'A' THEN 1 ELSE 0 END) absent, "
                        + "SUM(CASE WHEN a.status = 'L' THEN 1 ELSE 0 END) leave_n "
                        + "FROM enrollments e JOIN students st ON st.id = e.student_id AND st.status = 'active' "
                        + "LEFT JOIN student_attendance a ON a.student_id = st.id AND a.att_date BETWEEN ? AND ? "
                        + "WHERE e.inst_id = ? AND e.year_id = ? AND e.section_id = ? GROUP BY st.id, st.name, e.roll_no ORDER BY e.roll_no, st.name",
                ym.atDay(1), ym.atEndOfMonth(), inst, yearId, sectionId);
        return U.map("section", sec, "month", ym.toString(), "students", rows);
    }

    // ---------------------------------------------------------------- staff

    public Map<String, Object> staffSheet(Ctx c, String date) {
        c.needDesk();
        long inst = c.inst();
        LocalDate d = workDate(date);
        List<Map<String, Object>> rows = db.query(
                "SELECT s.id, s.emp_no, s.name, s.kind, s.designation, a.status, a.note FROM staff s "
                        + "LEFT JOIN staff_attendance a ON a.staff_id = s.id AND a.att_date = ? "
                        + "WHERE s.inst_id = ? AND s.status = 'active' ORDER BY s.kind DESC, s.name", d, inst);
        boolean marked = false;
        for (Map<String, Object> r : rows) {
            if (r.get("status") != null) {
                marked = true;
                break;
            }
        }
        return U.map("date", d.toString(), "marked", marked, "staff", rows);
    }

    public Map<String, Object> saveStaffSheet(Ctx c, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        LocalDate d = workDate(b.get("date"));
        Set<Long> allowed = new HashSet<>();
        for (Map<String, Object> r : db.query("SELECT id FROM staff WHERE inst_id = ? AND status = 'active'", inst)) {
            allowed.add(U.n(r, "id"));
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> m : U.list(b.get("marks"))) {
            long sid = U.l(m.get("staffId"), 0);
            if (!allowed.contains(sid)) {
                throw new ApiException(400, "தவறான ஊழியர் பதிவு");
            }
            String st = U.pick(m.get("status"), null, "வருகை நிலை", "P", "A", "H", "L");
            if (st == null) {
                throw new ApiException(400, "வருகை நிலை தேவை");
            }
            rows.add(new Object[]{d, sid, inst, st, U.s(m.get("note"), 120, "குறிப்பு")});
        }
        if (rows.isEmpty()) {
            throw new ApiException(400, "பதிவு செய்ய ஊழியர்கள் இல்லை");
        }
        db.batch("INSERT INTO staff_attendance (att_date, staff_id, inst_id, status, note) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE status = VALUES(status), note = VALUES(note)", rows);
        return U.map("saved", rows.size());
    }

    public Map<String, Object> staffMonthly(Ctx c, String month) {
        c.needDesk();
        YearMonth ym = month(month);
        List<Map<String, Object>> rows = db.query(
                "SELECT s.id, s.emp_no, s.name, s.kind, "
                        + "SUM(CASE WHEN a.status = 'P' THEN 1 ELSE 0 END) present, "
                        + "SUM(CASE WHEN a.status = 'A' THEN 1 ELSE 0 END) absent, "
                        + "SUM(CASE WHEN a.status = 'H' THEN 1 ELSE 0 END) half_n, "
                        + "SUM(CASE WHEN a.status = 'L' THEN 1 ELSE 0 END) leave_n "
                        + "FROM staff s LEFT JOIN staff_attendance a ON a.staff_id = s.id AND a.att_date BETWEEN ? AND ? "
                        + "WHERE s.inst_id = ? AND s.status = 'active' GROUP BY s.id, s.emp_no, s.name, s.kind ORDER BY s.kind DESC, s.name",
                ym.atDay(1), ym.atEndOfMonth(), c.inst());
        return U.map("month", ym.toString(), "staff", rows);
    }
}
