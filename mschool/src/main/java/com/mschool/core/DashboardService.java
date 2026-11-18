package com.mschool.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Numbers for the home screen. Fee and salary figures only go to roles that may see them. */
public class DashboardService extends Base {
    private final AttendanceService attendance;
    private final FeeService fees;

    public DashboardService(Db db, ZoneId zone, AttendanceService attendance, FeeService fees) {
        super(db, zone);
        this.attendance = attendance;
        this.fees = fees;
    }

    public Map<String, Object> dashboard(Ctx c) {
        long inst = c.inst();
        LocalDate t = today();
        Map<String, Object> out = U.map("date", t.toString());
        Map<String, Object> year = currentYear(inst);
        out.put("year", year);
        out.put("classCount", U.n(db.one("SELECT COUNT(*) n FROM classes WHERE inst_id = ?", inst), "n"));
        out.put("students", U.n(db.one("SELECT COUNT(*) n FROM students WHERE inst_id = ? AND status = 'active'", inst), "n"));
        long teachers = 0;
        long others = 0;
        for (Map<String, Object> r : db.query("SELECT kind, COUNT(*) n FROM staff WHERE inst_id = ? AND status = 'active' GROUP BY kind", inst)) {
            if ("teacher".equals(r.get("kind"))) {
                teachers = U.n(r, "n");
            } else {
                others = U.n(r, "n");
            }
        }
        out.put("teachers", teachers);
        out.put("otherStaff", others);
        out.put("openIssues", U.n(db.one("SELECT COUNT(*) n FROM room_maintenance WHERE inst_id = ? AND status = 'open'", inst), "n"));
        if (year != null) {
            Map<String, Object> day = attendance.day(c, null);
            out.put("studentAttendance", day.get("totals"));
        }
        if (c.desk()) {
            long p = 0;
            long a = 0;
            long h = 0;
            long l = 0;
            for (Map<String, Object> r : db.query("SELECT status, COUNT(*) n FROM staff_attendance WHERE inst_id = ? AND att_date = ? GROUP BY status", inst, t)) {
                switch ((String) r.get("status")) {
                    case "P" -> p = U.n(r, "n");
                    case "A" -> a = U.n(r, "n");
                    case "H" -> h = U.n(r, "n");
                    default -> l = U.n(r, "n");
                }
            }
            out.put("staffAttendance", U.map("present", p, "absent", a, "half", h, "leave", l, "total", teachers + others));
            Map<String, Object> rep = fees.report(c, null);
            out.put("feeToday", rep.get("day"));
            out.put("feeMonth", rep.get("month"));
            if (year != null) {
                Map<String, Object> pend = fees.pending(c, Map.of("yearId", "0"));
                out.put("feePending", U.map("total", pend.get("total"), "count", pend.get("count")));
            }
        }
        if (c.head()) {
            String month = t.getYear() + "-" + pad(t.getMonthValue(), 2);
            Map<String, Object> pay = db.one("SELECT COALESCE(SUM(net), 0) due, COUNT(*) n FROM payroll WHERE inst_id = ? AND pay_month = ? AND status = 'draft'", inst, month);
            out.put("salaryDue", U.map("amount", U.n(pay, "due"), "count", U.n(pay, "n"), "month", month));
        }
        return out;
    }
}
