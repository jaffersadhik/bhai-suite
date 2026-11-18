package com.mschool.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Students (with their class per academic year) and staff (teachers and non-teaching). */
public class PeopleService extends Base {

    public PeopleService(Db db, ZoneId zone) {
        super(db, zone);
    }

    // ---------------------------------------------------------------- students

    public List<Map<String, Object>> students(Ctx c, Map<String, String> q) {
        long inst = c.inst();
        Map<String, Object> y = currentYear(inst);
        long yearId = y == null ? 0 : U.n(y, "id");
        List<Object> args = new ArrayList<>();
        args.add(yearId);
        StringBuilder sql = new StringBuilder(
                "SELECT st.id, st.adm_no, st.name, st.gender, st.phone, st.status, e.class_id, e.section_id, e.roll_no, e.result, "
                        + "cl.name class_name, sc.name section_name FROM students st "
                        + "LEFT JOIN enrollments e ON e.student_id = st.id AND e.year_id = ? "
                        + "LEFT JOIN classes cl ON cl.id = e.class_id LEFT JOIN sections sc ON sc.id = e.section_id "
                        + "WHERE st.inst_id = ?");
        args.add(inst);
        String status = q.getOrDefault("status", "active");
        if (!status.isEmpty() && !status.equals("all")) {
            sql.append(" AND st.status = ?");
            args.add(status);
        }
        if (U.s(q.get("classId")) != null) {
            sql.append(" AND e.class_id = ?");
            args.add(U.l(q.get("classId"), 0));
        }
        if (U.s(q.get("sectionId")) != null) {
            sql.append(" AND e.section_id = ?");
            args.add(U.l(q.get("sectionId"), 0));
        }
        String text = U.s(q.get("q"));
        if (text != null) {
            sql.append(" AND (st.name LIKE ? OR st.adm_no LIKE ? OR st.phone LIKE ? OR st.father_name LIKE ?)");
            String p = U.like(text);
            args.add(p);
            args.add(p);
            args.add(p);
            args.add(p);
        }
        sql.append(" ORDER BY cl.sort_order, sc.name, e.roll_no, st.name LIMIT 600");
        return db.query(sql.toString(), args.toArray());
    }

    public Map<String, Object> student(Ctx c, long id) {
        long inst = c.inst();
        Map<String, Object> st = mustOne("மாணவர்",
                "SELECT id, adm_no, name, gender, dob, father_name, mother_name, phone, phone2, address, joined_on, status, notes FROM students WHERE id = ? AND inst_id = ?", id, inst);
        List<Map<String, Object>> history = db.query(
                "SELECT e.year_id, y.name year_name, e.class_id, cl.name class_name, e.section_id, sc.name section_name, e.roll_no, e.result "
                        + "FROM enrollments e JOIN academic_years y ON y.id = e.year_id JOIN classes cl ON cl.id = e.class_id "
                        + "LEFT JOIN sections sc ON sc.id = e.section_id WHERE e.student_id = ? ORDER BY y.start_date DESC", id);
        Map<String, Object> out = U.map("student", st, "history", history);
        Map<String, Object> y = currentYear(inst);
        long p = 0;
        long a = 0;
        long l = 0;
        if (y != null) {
            for (Map<String, Object> r : db.query("SELECT status, COUNT(*) n FROM student_attendance WHERE student_id = ? AND att_date BETWEEN ? AND ? GROUP BY status",
                    id, U.date(y.get("start_date")), U.date(y.get("end_date")))) {
                String s = (String) r.get("status");
                long n = U.n(r, "n");
                if ("P".equals(s)) {
                    p = n;
                } else if ("A".equals(s)) {
                    a = n;
                } else if ("L".equals(s)) {
                    l = n;
                }
            }
        }
        out.put("attendance", U.map("present", p, "absent", a, "leave", l));
        return out;
    }

    private static String phone(Object o, String label) {
        String t = U.s(o, 20, label);
        if (t != null && !t.matches("[0-9+()\\- ]{6,20}")) {
            throw new ApiException(400, label + " தவறு");
        }
        return t;
    }

    private long nextRoll(long yearId, long sectionId) {
        return U.n(db.one("SELECT COALESCE(MAX(roll_no), 0) + 1 n FROM enrollments WHERE year_id = ? AND section_id = ?", yearId, sectionId), "n");
    }

    /** Puts the student in a class and section for the year (insert or move). */
    private void place(long inst, long yearId, long studentId, long classId, long sectionId, Integer rollNo) {
        Map<String, Object> sec = mustOne("பிரிவு", "SELECT id FROM sections WHERE id = ? AND class_id = ? AND inst_id = ?", sectionId, classId, inst);
        Map<String, Object> ex = db.one("SELECT id, section_id, roll_no FROM enrollments WHERE year_id = ? AND student_id = ?", yearId, studentId);
        if (ex == null) {
            long roll = rollNo != null ? rollNo : nextRoll(yearId, U.n(sec, "id"));
            db.insert("INSERT INTO enrollments (inst_id, year_id, student_id, class_id, section_id, roll_no) VALUES (?,?,?,?,?,?)",
                    inst, yearId, studentId, classId, sectionId, roll);
            return;
        }
        long roll = U.n(ex, "roll_no");
        if (rollNo != null) {
            roll = rollNo;
        } else if (U.n(ex, "section_id") != sectionId) {
            roll = nextRoll(yearId, sectionId);
        }
        db.update("UPDATE enrollments SET class_id = ?, section_id = ?, roll_no = ? WHERE id = ?", classId, sectionId, roll, U.n(ex, "id"));
    }

    public Map<String, Object> saveStudent(Ctx c, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        long yearId = needYear(inst);
        Long id = U.lo(b.get("id"));
        String adm = U.req(b.get("admNo"), 20, "சேர்க்கை எண்");
        String name = U.req(b.get("name"), 80, "மாணவர் பெயர்");
        String gender = U.pick(b.get("gender"), null, "பாலினம்", "M", "F", "O");
        var dob = U.date(b.get("dob"));
        String father = U.s(b.get("fatherName"), 80, "தந்தை பெயர்");
        String mother = U.s(b.get("motherName"), 80, "தாய் பெயர்");
        String phone = phone(b.get("phone"), "தொலைபேசி");
        String phone2 = phone(b.get("phone2"), "மாற்று தொலைபேசி");
        String address = U.s(b.get("address"), 255, "முகவரி");
        var joined = U.date(b.get("joinedOn"));
        String notes = U.s(b.get("notes"), 255, "குறிப்பு");
        Long classId = U.lo(b.get("classId"));
        Long sectionId = U.lo(b.get("sectionId"));
        Integer roll = U.s(b.get("rollNo")) == null ? null : Integer.valueOf(U.i(b.get("rollNo"), 0));
        if (id == null && (classId == null || sectionId == null)) {
            throw new ApiException(400, "வகுப்பு மற்றும் பிரிவை தேர்வு செய்யவும்");
        }
        Map<String, Object> clash = db.one("SELECT id FROM students WHERE inst_id = ? AND adm_no = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, adm, id} : new Object[]{inst, adm});
        if (clash != null) {
            throw new ApiException(409, "இந்த சேர்க்கை எண் ஏற்கனவே உள்ளது");
        }
        return db.tx(() -> {
            long sid;
            if (id == null) {
                sid = db.insert("INSERT INTO students (inst_id, adm_no, name, gender, dob, father_name, mother_name, phone, phone2, address, joined_on, status, notes) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?, 'active', ?)",
                        inst, adm, name, gender, dob, father, mother, phone, phone2, address, joined == null ? today() : joined, notes);
            } else {
                belongs("students", id, inst, "மாணவர்");
                db.update("UPDATE students SET adm_no = ?, name = ?, gender = ?, dob = ?, father_name = ?, mother_name = ?, phone = ?, phone2 = ?, address = ?, joined_on = ?, notes = ? WHERE id = ?",
                        adm, name, gender, dob, father, mother, phone, phone2, address, joined, notes, id);
                sid = id;
            }
            if (classId != null && sectionId != null) {
                place(inst, yearId, sid, classId, sectionId, roll);
            }
            return U.map("id", sid);
        });
    }

    public void setStudentStatus(Ctx c, long id, String status) {
        c.needDesk();
        String s = U.pick(status, null, "நிலை", "active", "left", "graduated");
        if (s == null) {
            throw new ApiException(400, "நிலை தேவை");
        }
        belongs("students", id, c.inst(), "மாணவர்");
        db.update("UPDATE students SET status = ? WHERE id = ?", s, id);
    }

    // ---------------------------------------------------------------- CSV import of students

    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static LocalDate flexDate(String t) {
        if (t == null || t.isBlank()) {
            return null;
        }
        String x = t.trim();
        try {
            if (x.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = x.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            if (x.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}")) {
                String[] p = x.split("[/-]");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Columns (header row): adm_no, name, gender, dob, father, mother, phone, address, class, section, roll.
     * Everything is checked first; nothing is saved when any row has a problem.
     */
    public Map<String, Object> importStudents(Ctx c, String csv) {
        c.needDesk();
        long inst = c.inst();
        long yearId = needYear(inst);
        if (csv == null || csv.isBlank()) {
            throw new ApiException(400, "CSV உள்ளடக்கம் தேவை");
        }
        List<List<String>> rows = parseCsv(csv);
        if (rows.size() < 2) {
            throw new ApiException(400, "தலைப்பு வரிக்கு பிறகு மாணவர் வரிகள் தேவை");
        }
        if (rows.size() > 3001) {
            throw new ApiException(400, "ஒரு முறைக்கு 3000 வரிகள் வரை மட்டும்");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> head = rows.get(0);
        for (int i = 0; i < head.size(); i++) {
            col.put(head.get(i).toLowerCase().replace(" ", "_"), i);
        }
        for (String need : new String[]{"adm_no", "name", "class"}) {
            if (!col.containsKey(need)) {
                throw new ApiException(400, "CSV-இல் '" + need + "' நெடுவரிசை தேவை");
            }
        }
        Map<String, Long> classIds = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT id, name FROM classes WHERE inst_id = ?", inst)) {
            classIds.put(((String) r.get("name")).toLowerCase().trim(), U.n(r, "id"));
        }
        Map<Long, Map<String, Long>> sectionIds = new HashMap<>();
        Map<Long, Long> firstSection = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT id, class_id, name FROM sections WHERE inst_id = ? ORDER BY name", inst)) {
            long cid = U.n(r, "class_id");
            sectionIds.computeIfAbsent(cid, k -> new HashMap<>()).put(((String) r.get("name")).toLowerCase().trim(), U.n(r, "id"));
            firstSection.putIfAbsent(cid, U.n(r, "id"));
        }
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            int line = i + 1;
            String adm = cell(r, col, "adm_no");
            String name = cell(r, col, "name");
            String cls = cell(r, col, "class");
            if (adm == null || name == null || cls == null) {
                errors.add("வரி " + line + ": adm_no, name, class தேவை");
                continue;
            }
            if (adm.length() > 20 || name.length() > 80) {
                errors.add("வரி " + line + ": adm_no அல்லது name நீளம் அதிகம்");
                continue;
            }
            if (!seen.add(adm.toLowerCase())) {
                errors.add("வரி " + line + ": adm_no " + adm + " கோப்பில் இரண்டு முறை உள்ளது");
                continue;
            }
            Long cid = classIds.get(cls.toLowerCase().trim());
            if (cid == null) {
                errors.add("வரி " + line + ": வகுப்பு '" + cls + "' இல்லை");
                continue;
            }
            String sec = cell(r, col, "section");
            Long sid = sec == null ? firstSection.get(cid) : sectionIds.getOrDefault(cid, Map.of()).get(sec.toLowerCase().trim());
            if (sid == null) {
                errors.add("வரி " + line + ": பிரிவு '" + (sec == null ? "" : sec) + "' இல்லை");
                continue;
            }
            String dobText = cell(r, col, "dob");
            LocalDate dob = flexDate(dobText);
            if (dobText != null && dob == null) {
                errors.add("வரி " + line + ": dob தேதி தவறு (" + dobText + ")");
                continue;
            }
            String gender = cell(r, col, "gender");
            if (gender != null) {
                gender = gender.substring(0, 1).toUpperCase();
                if (!gender.matches("[MFO]")) {
                    errors.add("வரி " + line + ": gender M / F / O ஆக இருக்க வேண்டும்");
                    continue;
                }
            }
            String ph = cell(r, col, "phone");
            if (ph != null && !ph.matches("[0-9+()\\- ]{6,20}")) {
                errors.add("வரி " + line + ": phone தவறு");
                continue;
            }
            String rollText = cell(r, col, "roll");
            Integer roll = null;
            if (rollText != null) {
                if (!rollText.matches("\\d{1,5}")) {
                    errors.add("வரி " + line + ": roll எண் தவறு");
                    continue;
                }
                roll = Integer.valueOf(rollText);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adm", adm);
            m.put("name", name);
            m.put("gender", gender);
            m.put("dob", dob);
            m.put("father", clip(cell(r, col, "father"), 80));
            m.put("mother", clip(cell(r, col, "mother"), 80));
            m.put("phone", ph);
            m.put("address", clip(cell(r, col, "address"), 255));
            m.put("cid", cid);
            m.put("sid", sid);
            m.put("roll", roll);
            items.add(m);
        }
        if (!errors.isEmpty()) {
            String shown = String.join("; ", errors.subList(0, Math.min(8, errors.size())));
            throw new ApiException(400, errors.size() + " பிழை(கள்): " + shown + (errors.size() > 8 ? " …" : ""));
        }
        return db.tx(() -> {
            int added = 0;
            int updated = 0;
            LocalDate t = today();
            for (Map<String, Object> m : items) {
                Map<String, Object> ex = db.one("SELECT id FROM students WHERE inst_id = ? AND adm_no = ?", inst, m.get("adm"));
                long sid;
                if (ex == null) {
                    sid = db.insert("INSERT INTO students (inst_id, adm_no, name, gender, dob, father_name, mother_name, phone, address, joined_on, status) VALUES (?,?,?,?,?,?,?,?,?,?, 'active')",
                            inst, m.get("adm"), m.get("name"), m.get("gender"), m.get("dob"), m.get("father"), m.get("mother"), m.get("phone"), m.get("address"), t);
                    added++;
                } else {
                    sid = U.n(ex, "id");
                    db.update("UPDATE students SET name = ?, gender = COALESCE(?, gender), dob = COALESCE(?, dob), father_name = COALESCE(?, father_name), "
                                    + "mother_name = COALESCE(?, mother_name), phone = COALESCE(?, phone), address = COALESCE(?, address) WHERE id = ?",
                            m.get("name"), m.get("gender"), m.get("dob"), m.get("father"), m.get("mother"), m.get("phone"), m.get("address"), sid);
                    updated++;
                }
                place(inst, yearId, sid, (Long) m.get("cid"), (Long) m.get("sid"), (Integer) m.get("roll"));
            }
            return U.map("added", added, "updated", updated);
        });
    }

    private static String cell(List<String> r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= r.size()) {
            return null;
        }
        String v = r.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    private static String clip(String s, int max) {
        return s == null ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    // ---------------------------------------------------------------- staff

    public List<Map<String, Object>> staff(Ctx c, Map<String, String> q) {
        long inst = c.inst();
        List<Object> args = new ArrayList<>();
        args.add(inst);
        String cols = c.head() ? ", basic, allowance, deduction" : "";
        StringBuilder sql = new StringBuilder("SELECT id, emp_no, name, kind, designation, subjects, phone, joined_on, status" + cols + " FROM staff WHERE inst_id = ?");
        String status = q.getOrDefault("status", "active");
        if (!status.isEmpty() && !status.equals("all")) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        String kind = U.s(q.get("kind"));
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        String text = U.s(q.get("q"));
        if (text != null) {
            sql.append(" AND (name LIKE ? OR emp_no LIKE ? OR phone LIKE ?)");
            String p = U.like(text);
            args.add(p);
            args.add(p);
            args.add(p);
        }
        sql.append(" ORDER BY kind DESC, name LIMIT 500");
        return db.query(sql.toString(), args.toArray());
    }

    public Map<String, Object> staffMember(Ctx c, long id) {
        long inst = c.inst();
        String cols = c.head() ? ", basic, allowance, deduction" : "";
        Map<String, Object> s = mustOne("ஊழியர்",
                "SELECT id, emp_no, name, kind, designation, subjects, phone, address, joined_on, status, notes" + cols + " FROM staff WHERE id = ? AND inst_id = ?", id, inst);
        List<Map<String, Object>> sections = db.query(
                "SELECT sc.id, cl.name class_name, sc.name section_name FROM sections sc JOIN classes cl ON cl.id = sc.class_id WHERE sc.class_teacher_id = ? ORDER BY cl.sort_order", id);
        LocalDate t = today();
        LocalDate first = t.withDayOfMonth(1);
        Map<String, Object> att = U.map("present", 0L, "absent", 0L, "half", 0L, "leave", 0L);
        for (Map<String, Object> r : db.query("SELECT status, COUNT(*) n FROM staff_attendance WHERE staff_id = ? AND att_date BETWEEN ? AND ? GROUP BY status", id, first, t.withDayOfMonth(t.lengthOfMonth()))) {
            String k = switch ((String) r.get("status")) {
                case "P" -> "present";
                case "A" -> "absent";
                case "H" -> "half";
                default -> "leave";
            };
            att.put(k, U.n(r, "n"));
        }
        return U.map("staff", s, "classTeacherOf", sections, "attendance", att);
    }

    public Map<String, Object> saveStaff(Ctx c, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String emp = U.req(b.get("empNo"), 20, "ஊழியர் எண்");
        String name = U.req(b.get("name"), 80, "பெயர்");
        String kind = U.pick(b.get("kind"), "teacher", "வகை", "teacher", "staff");
        String desig = U.s(b.get("designation"), 60, "பதவி");
        String subjects = U.s(b.get("subjects"), 120, "பாடங்கள்");
        String phone = phone(b.get("phone"), "தொலைபேசி");
        String address = U.s(b.get("address"), 255, "முகவரி");
        var joined = U.date(b.get("joinedOn"));
        String notes = U.s(b.get("notes"), 255, "குறிப்பு");
        long basic = Math.max(0, U.l(b.get("basic"), 0));
        long allowance = Math.max(0, U.l(b.get("allowance"), 0));
        long deduction = Math.max(0, U.l(b.get("deduction"), 0));
        Map<String, Object> clash = db.one("SELECT id FROM staff WHERE inst_id = ? AND emp_no = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, emp, id} : new Object[]{inst, emp});
        if (clash != null) {
            throw new ApiException(409, "இந்த ஊழியர் எண் ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            long nid = db.insert("INSERT INTO staff (inst_id, emp_no, name, kind, designation, subjects, phone, address, joined_on, basic, allowance, deduction, status, notes) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'active', ?)",
                    inst, emp, name, kind, desig, subjects, phone, address, joined == null ? today() : joined,
                    c.head() ? basic : 0, c.head() ? allowance : 0, c.head() ? deduction : 0, notes);
            return U.map("id", nid);
        }
        belongs("staff", id, inst, "ஊழியர்");
        db.update("UPDATE staff SET emp_no = ?, name = ?, kind = ?, designation = ?, subjects = ?, phone = ?, address = ?, joined_on = ?, notes = ? WHERE id = ?",
                emp, name, kind, desig, subjects, phone, address, joined, notes, id);
        if (c.head()) {
            db.update("UPDATE staff SET basic = ?, allowance = ?, deduction = ? WHERE id = ?", basic, allowance, deduction, id);
        }
        return U.map("id", id);
    }

    public void setStaffStatus(Ctx c, long id, String status) {
        c.needDesk();
        String s = U.pick(status, null, "நிலை", "active", "left");
        if (s == null) {
            throw new ApiException(400, "நிலை தேவை");
        }
        belongs("staff", id, c.inst(), "ஊழியர்");
        db.update("UPDATE staff SET status = ? WHERE id = ?", s, id);
    }
}
