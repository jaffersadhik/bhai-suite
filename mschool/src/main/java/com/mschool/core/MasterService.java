package com.mschool.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Academic years, classes, sections and rooms (the set-up of an institution). */
public class MasterService extends Base {

    public MasterService(Db db, ZoneId zone) {
        super(db, zone);
    }

    /** Everything the app needs after login, in one call. */
    public Map<String, Object> data(Ctx c) {
        long inst = c.inst();
        Map<String, Object> out = U.map();
        out.put("inst", db.one("SELECT id, name, kind, phone, address, weekly_off FROM institutions WHERE id = ?", inst));
        out.put("years", db.query("SELECT id, name, start_date, end_date, is_current FROM academic_years WHERE inst_id = ? ORDER BY start_date DESC", inst));
        out.put("classes", db.query("SELECT id, name, sort_order, next_class_id FROM classes WHERE inst_id = ? ORDER BY sort_order, name", inst));
        out.put("sections", db.query(
                "SELECT s.id, s.class_id, s.name, s.room_id, s.class_teacher_id, r.name room_name, t.name teacher_name "
                        + "FROM sections s LEFT JOIN rooms r ON r.id = s.room_id LEFT JOIN staff t ON t.id = s.class_teacher_id "
                        + "WHERE s.inst_id = ? ORDER BY s.class_id, s.name", inst));
        out.put("rooms", db.query("SELECT id, name, building, floor_no, capacity, kind, status FROM rooms WHERE inst_id = ? ORDER BY name", inst));
        out.put("heads", db.query("SELECT id, name, sort_order FROM fee_heads WHERE inst_id = ? ORDER BY sort_order, id", inst));
        out.put("teachers", db.query("SELECT id, emp_no, name FROM staff WHERE inst_id = ? AND kind = 'teacher' AND status = 'active' ORDER BY name", inst));
        return out;
    }

    // ---------------------------------------------------------------- academic years

    public Map<String, Object> saveYear(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String name = U.req(b.get("name"), 20, "கல்வியாண்டு பெயர்");
        LocalDate start = U.reqDate(b.get("start"), "தொடக்க தேதி");
        LocalDate end = U.reqDate(b.get("end"), "முடிவு தேதி");
        if (!end.isAfter(start)) {
            throw new ApiException(400, "முடிவு தேதி தொடக்க தேதிக்கு பின் இருக்க வேண்டும்");
        }
        boolean current = U.b(b.get("current"));
        Map<String, Object> clash = db.one("SELECT id FROM academic_years WHERE inst_id = ? AND name = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, name, id} : new Object[]{inst, name});
        if (clash != null) {
            throw new ApiException(409, "இந்த கல்வியாண்டு ஏற்கனவே உள்ளது");
        }
        return db.tx(() -> {
            long yid;
            if (id == null) {
                yid = db.insert("INSERT INTO academic_years (inst_id, name, start_date, end_date, is_current) VALUES (?,?,?,?,0)", inst, name, start, end);
            } else {
                belongs("academic_years", id, inst, "கல்வியாண்டு");
                db.update("UPDATE academic_years SET name = ?, start_date = ?, end_date = ? WHERE id = ?", name, start, end, id);
                yid = id;
            }
            if (current) {
                db.update("UPDATE academic_years SET is_current = 0 WHERE inst_id = ?", inst);
                db.update("UPDATE academic_years SET is_current = 1 WHERE id = ?", yid);
            }
            return U.map("id", yid);
        });
    }

    // ---------------------------------------------------------------- classes and sections

    public Map<String, Object> saveClass(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String name = U.req(b.get("name"), 60, "வகுப்பின் பெயர்");
        Long next = U.lo(b.get("nextClassId"));
        if (next != null) {
            belongs("classes", next, inst, "அடுத்த வகுப்பு");
            if (next.equals(id)) {
                throw new ApiException(400, "அடுத்த வகுப்பு இதே வகுப்பாக இருக்க முடியாது");
            }
        }
        Map<String, Object> clash = db.one("SELECT id FROM classes WHERE inst_id = ? AND name = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, name, id} : new Object[]{inst, name});
        if (clash != null) {
            throw new ApiException(409, "இந்த பெயரில் வகுப்பு ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            Map<String, Object> m = db.one("SELECT COALESCE(MAX(sort_order), 0) + 1 n FROM classes WHERE inst_id = ?", inst);
            int order = b.get("sortOrder") == null ? (int) U.n(m, "n") : U.i(b.get("sortOrder"), 0);
            return db.tx(() -> {
                long nid = db.insert("INSERT INTO classes (inst_id, name, sort_order, next_class_id) VALUES (?,?,?,?)", inst, name, order, next);
                db.insert("INSERT INTO sections (inst_id, class_id, name) VALUES (?,?,?)", inst, nid, "A");
                return U.map("id", nid);
            });
        }
        belongs("classes", id, inst, "வகுப்பு");
        int order = U.i(b.get("sortOrder"), 0);
        db.update("UPDATE classes SET name = ?, sort_order = ?, next_class_id = ? WHERE id = ?", name, order, next, id);
        return U.map("id", id);
    }

    public void deleteClass(Ctx c, long id) {
        c.needHead();
        long inst = c.inst();
        belongs("classes", id, inst, "வகுப்பு");
        if (db.one("SELECT id FROM enrollments WHERE class_id = ? LIMIT 1", id) != null) {
            throw new ApiException(409, "இந்த வகுப்பில் மாணவர் பதிவுகள் உள்ளன, நீக்க முடியாது");
        }
        db.tx(() -> {
            db.update("UPDATE classes SET next_class_id = NULL WHERE inst_id = ? AND next_class_id = ?", inst, id);
            db.update("DELETE FROM fee_structure WHERE inst_id = ? AND class_id = ?", inst, id);
            db.update("DELETE FROM classes WHERE id = ?", id);
            return null;
        });
    }

    public Map<String, Object> saveSection(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        long classId = U.l(b.get("classId"), 0);
        belongs("classes", classId, inst, "வகுப்பு");
        String name = U.req(b.get("name"), 20, "பிரிவின் பெயர்");
        Long roomId = U.lo(b.get("roomId"));
        Long teacherId = U.lo(b.get("teacherId"));
        if (roomId != null) {
            belongs("rooms", roomId, inst, "அறை");
        }
        if (teacherId != null) {
            belongs("staff", teacherId, inst, "ஆசிரியர்");
        }
        Map<String, Object> clash = db.one("SELECT id FROM sections WHERE class_id = ? AND name = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{classId, name, id} : new Object[]{classId, name});
        if (clash != null) {
            throw new ApiException(409, "இந்த பிரிவு ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            long nid = db.insert("INSERT INTO sections (inst_id, class_id, name, room_id, class_teacher_id) VALUES (?,?,?,?,?)",
                    inst, classId, name, roomId, teacherId);
            return U.map("id", nid);
        }
        belongs("sections", id, inst, "பிரிவு");
        db.update("UPDATE sections SET name = ?, room_id = ?, class_teacher_id = ? WHERE id = ?", name, roomId, teacherId, id);
        return U.map("id", id);
    }

    public void deleteSection(Ctx c, long id) {
        c.needHead();
        long inst = c.inst();
        belongs("sections", id, inst, "பிரிவு");
        if (db.one("SELECT id FROM enrollments WHERE section_id = ? LIMIT 1", id) != null) {
            throw new ApiException(409, "இந்த பிரிவில் மாணவர் பதிவுகள் உள்ளன, நீக்க முடியாது");
        }
        db.update("DELETE FROM sections WHERE id = ?", id);
    }

    /** One-tap starting set of classes, each with section A, chained for promotion. */
    public Map<String, Object> applyTemplate(Ctx c, String template) {
        c.needHead();
        long inst = c.inst();
        if (db.one("SELECT id FROM classes WHERE inst_id = ? LIMIT 1", inst) != null) {
            throw new ApiException(409, "வகுப்புகள் ஏற்கனவே உள்ளன");
        }
        List<String> names = new ArrayList<>();
        switch (template == null ? "" : template) {
            case "school" -> {
                names.add("LKG");
                names.add("UKG");
                for (int i = 1; i <= 12; i++) {
                    names.add(i + "ஆம் வகுப்பு");
                }
            }
            case "college3" -> names.addAll(List.of("I Year", "II Year", "III Year"));
            case "college4" -> names.addAll(List.of("I Year", "II Year", "III Year", "IV Year"));
            default -> throw new ApiException(400, "மாதிரி தவறு");
        }
        return db.tx(() -> {
            long[] ids = new long[names.size()];
            for (int i = 0; i < names.size(); i++) {
                ids[i] = db.insert("INSERT INTO classes (inst_id, name, sort_order) VALUES (?,?,?)", inst, names.get(i), i + 1);
                db.insert("INSERT INTO sections (inst_id, class_id, name) VALUES (?,?,?)", inst, ids[i], "A");
            }
            for (int i = 0; i + 1 < ids.length; i++) {
                db.update("UPDATE classes SET next_class_id = ? WHERE id = ?", ids[i + 1], ids[i]);
            }
            return U.map("created", names.size());
        });
    }

    // ---------------------------------------------------------------- rooms

    public List<Map<String, Object>> rooms(Ctx c) {
        long inst = c.inst();
        Map<String, Object> y = currentYear(inst);
        long yearId = y == null ? 0 : U.n(y, "id");
        return db.query(
                "SELECT r.id, r.name, r.building, r.floor_no, r.capacity, r.kind, r.status, r.notes, "
                        + "(SELECT COUNT(*) FROM room_maintenance m WHERE m.room_id = r.id AND m.status = 'open') open_issues, "
                        + "(SELECT GROUP_CONCAT(CONCAT(cl.name, ' ', s.name) SEPARATOR ', ') FROM sections s JOIN classes cl ON cl.id = s.class_id WHERE s.room_id = r.id) used_by, "
                        + "(SELECT COUNT(*) FROM enrollments e JOIN sections s ON s.id = e.section_id JOIN students st ON st.id = e.student_id "
                        + "WHERE s.room_id = r.id AND e.year_id = ? AND st.status = 'active') students "
                        + "FROM rooms r WHERE r.inst_id = ? ORDER BY r.building, r.floor_no, r.name", yearId, inst);
    }

    public Map<String, Object> room(Ctx c, long id) {
        long inst = c.inst();
        Map<String, Object> r = mustOne("அறை", "SELECT id, name, building, floor_no, capacity, kind, status, notes FROM rooms WHERE id = ? AND inst_id = ?", id, inst);
        r.put("maintenance", db.query("SELECT id, issue, reported_on, cost, status, done_on, note FROM room_maintenance WHERE room_id = ? ORDER BY status = 'open' DESC, reported_on DESC, id DESC", id));
        return r;
    }

    public Map<String, Object> saveRoom(Ctx c, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        Long id = U.lo(b.get("id"));
        String name = U.req(b.get("name"), 40, "அறை எண் / பெயர்");
        String building = U.s(b.get("building"), 60, "கட்டிடம்");
        String floor = U.s(b.get("floorNo"), 20, "தளம்");
        int capacity = U.i(b.get("capacity"), 0);
        if (capacity < 0 || capacity > 5000) {
            throw new ApiException(400, "இருக்கை எண்ணிக்கை தவறு");
        }
        String kind = U.pick(b.get("kind"), "classroom", "வகை", "classroom", "lab", "hall", "library", "office", "other");
        String status = U.pick(b.get("status"), "active", "நிலை", "active", "maintenance", "closed");
        String notes = U.s(b.get("notes"), 255, "குறிப்பு");
        Map<String, Object> clash = db.one("SELECT id FROM rooms WHERE inst_id = ? AND name = ?" + (id != null ? " AND id <> ?" : ""),
                id != null ? new Object[]{inst, name, id} : new Object[]{inst, name});
        if (clash != null) {
            throw new ApiException(409, "இந்த அறை ஏற்கனவே உள்ளது");
        }
        if (id == null) {
            return U.map("id", db.insert("INSERT INTO rooms (inst_id, name, building, floor_no, capacity, kind, status, notes) VALUES (?,?,?,?,?,?,?,?)",
                    inst, name, building, floor, capacity, kind, status, notes));
        }
        belongs("rooms", id, inst, "அறை");
        db.update("UPDATE rooms SET name = ?, building = ?, floor_no = ?, capacity = ?, kind = ?, status = ?, notes = ? WHERE id = ?",
                name, building, floor, capacity, kind, status, notes, id);
        return U.map("id", id);
    }

    public void deleteRoom(Ctx c, long id) {
        c.needDesk();
        long inst = c.inst();
        belongs("rooms", id, inst, "அறை");
        db.tx(() -> {
            db.update("UPDATE sections SET room_id = NULL WHERE inst_id = ? AND room_id = ?", inst, id);
            db.update("DELETE FROM rooms WHERE id = ?", id);
            return null;
        });
    }

    public Map<String, Object> addMaintenance(Ctx c, long roomId, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        belongs("rooms", roomId, inst, "அறை");
        String issue = U.req(b.get("issue"), 255, "பழுது / வேலை விவரம்");
        long cost = Math.max(0, U.l(b.get("cost"), 0));
        LocalDate on = b.get("date") == null ? today() : U.reqDate(b.get("date"), "தேதி");
        long id = db.insert("INSERT INTO room_maintenance (inst_id, room_id, issue, reported_on, cost, status) VALUES (?,?,?,?,?, 'open')",
                inst, roomId, issue, on, cost);
        return U.map("id", id);
    }

    public void finishMaintenance(Ctx c, long id, Map<String, Object> b) {
        c.needDesk();
        long inst = c.inst();
        mustOne("பதிவு", "SELECT id FROM room_maintenance WHERE id = ? AND inst_id = ?", id, inst);
        long cost = Math.max(0, U.l(b.get("cost"), 0));
        String note = U.s(b.get("note"), 255, "குறிப்பு");
        db.update("UPDATE room_maintenance SET status = 'done', done_on = ?, cost = ?, note = ? WHERE id = ?", today(), cost, note, id);
    }

    public void deleteMaintenance(Ctx c, long id) {
        c.needDesk();
        mustOne("பதிவு", "SELECT id FROM room_maintenance WHERE id = ? AND inst_id = ?", id, c.inst());
        db.update("DELETE FROM room_maintenance WHERE id = ?", id);
    }
}
