package com.mschool.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monthly salary. Salary = basic + allowance - fixed deduction - loss of pay (LOP) + bonus - other deduction.
 * LOP days = absent days + half of the half-days, from staff attendance. Unmarked days count as present.
 */
public class PayrollService extends Base {

    public PayrollService(Db db, ZoneId zone) {
        super(db, zone);
    }

    private static long net(long basic, long allowance, long deduction, long lop, long bonus, long other) {
        return Math.max(0, basic + allowance - deduction - lop + bonus - other);
    }

    static int workingDays(YearMonth ym, int weeklyOff) {
        int n = 0;
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            DayOfWeek dow = ym.atDay(d).getDayOfWeek();
            if (weeklyOff == 0 || dow.getValue() != weeklyOff) {
                n++;
            }
        }
        return n;
    }

    public Map<String, Object> list(Ctx c, String month) {
        c.needHead();
        long inst = c.inst();
        YearMonth ym = AttendanceService.month(month);
        List<Map<String, Object>> rows = db.query(
                "SELECT id, staff_id, staff_name, designation, working_days, lop_days, basic, allowance, deduction, bonus, other_deduct, lop_amount, net, status, paid_on, pay_mode, note "
                        + "FROM payroll WHERE inst_id = ? AND pay_month = ? ORDER BY staff_name", inst, ym.toString());
        long total = 0;
        long paid = 0;
        for (Map<String, Object> r : rows) {
            total += U.n(r, "net");
            if ("paid".equals(r.get("status"))) {
                paid += U.n(r, "net");
            }
        }
        long missing = U.n(db.one("SELECT COUNT(*) n FROM staff s WHERE s.inst_id = ? AND s.status = 'active' "
                + "AND NOT EXISTS (SELECT 1 FROM payroll p WHERE p.staff_id = s.id AND p.pay_month = ?)", inst, ym.toString()), "n");
        return U.map("month", ym.toString(), "rows", rows,
                "totals", U.map("net", total, "paid", paid, "due", total - paid, "count", rows.size()), "notGenerated", missing);
    }

    /** Creates salary rows for staff who have none yet; regen recalculates unpaid rows. */
    public Map<String, Object> generate(Ctx c, Map<String, Object> b) {
        c.needHead();
        long inst = c.inst();
        YearMonth ym = AttendanceService.month(b.get("month"));
        if (ym.isAfter(YearMonth.from(today()))) {
            throw new ApiException(400, "எதிர்கால மாதத்திற்கு சம்பளம் உருவாக்க முடியாது");
        }
        boolean regen = U.b(b.get("regen"));
        int weeklyOff = (int) U.n(mustOne("நிறுவனம்", "SELECT weekly_off FROM institutions WHERE id = ?", inst), "weekly_off");
        int working = workingDays(ym, weeklyOff);
        Map<Long, double[]> lop = new HashMap<>();
        for (Map<String, Object> r : db.query(
                "SELECT staff_id, SUM(CASE WHEN status = 'A' THEN 1 ELSE 0 END) a, SUM(CASE WHEN status = 'H' THEN 1 ELSE 0 END) h "
                        + "FROM staff_attendance WHERE inst_id = ? AND att_date BETWEEN ? AND ? GROUP BY staff_id", inst, ym.atDay(1), ym.atEndOfMonth())) {
            lop.put(U.n(r, "staff_id"), new double[]{U.n(r, "a") + 0.5 * U.n(r, "h")});
        }
        LocalDate last = ym.atEndOfMonth();
        List<Map<String, Object>> staff = db.query(
                "SELECT id, name, designation, basic, allowance, deduction, joined_on FROM staff WHERE inst_id = ? AND status = 'active' ORDER BY name", inst);
        return db.tx(() -> {
            int created = 0;
            int updated = 0;
            for (Map<String, Object> s : staff) {
                LocalDate joined = U.date(s.get("joined_on"));
                if (joined != null && joined.isAfter(last)) {
                    continue;
                }
                long sid = U.n(s, "id");
                long basic = U.n(s, "basic");
                long allowance = U.n(s, "allowance");
                long deduction = U.n(s, "deduction");
                double lopDays = lop.containsKey(sid) ? Math.min(lop.get(sid)[0], working) : 0;
                long lopAmount = working > 0 ? Math.round((basic + allowance) * lopDays / working) : 0;
                Map<String, Object> ex = db.one("SELECT id, status, bonus, other_deduct FROM payroll WHERE inst_id = ? AND pay_month = ? AND staff_id = ?", inst, ym.toString(), sid);
                if (ex == null) {
                    db.insert("INSERT INTO payroll (inst_id, pay_month, staff_id, staff_name, designation, working_days, lop_days, basic, allowance, deduction, bonus, other_deduct, lop_amount, net, status) "
                                    + "VALUES (?,?,?,?,?,?,?,?,?,?,0,0,?,?, 'draft')",
                            inst, ym.toString(), sid, s.get("name"), s.get("designation"), working, lopDays, basic, allowance, deduction, lopAmount,
                            net(basic, allowance, deduction, lopAmount, 0, 0));
                    created++;
                } else if (regen && "draft".equals(ex.get("status"))) {
                    long bonus = U.n(ex, "bonus");
                    long other = U.n(ex, "other_deduct");
                    db.update("UPDATE payroll SET staff_name = ?, designation = ?, working_days = ?, lop_days = ?, basic = ?, allowance = ?, deduction = ?, lop_amount = ?, net = ? WHERE id = ?",
                            s.get("name"), s.get("designation"), working, lopDays, basic, allowance, deduction, lopAmount,
                            net(basic, allowance, deduction, lopAmount, bonus, other), U.n(ex, "id"));
                    updated++;
                }
            }
            return U.map("created", created, "updated", updated, "workingDays", working);
        });
    }

    public void update(Ctx c, long id, Map<String, Object> b) {
        c.needHead();
        Map<String, Object> r = mustOne("சம்பள பதிவு",
                "SELECT id, status, basic, allowance, lop_amount FROM payroll WHERE id = ? AND inst_id = ?", id, c.inst());
        if (!"draft".equals(r.get("status"))) {
            throw new ApiException(409, "செலுத்தப்பட்ட சம்பளத்தை மாற்ற முடியாது");
        }
        long deduction = Math.max(0, U.l(b.get("deduction"), 0));
        long bonus = Math.max(0, U.l(b.get("bonus"), 0));
        long other = Math.max(0, U.l(b.get("otherDeduct"), 0));
        String note = U.s(b.get("note"), 255, "குறிப்பு");
        db.update("UPDATE payroll SET deduction = ?, bonus = ?, other_deduct = ?, note = ?, net = ? WHERE id = ?",
                deduction, bonus, other, note, net(U.n(r, "basic"), U.n(r, "allowance"), deduction, U.n(r, "lop_amount"), bonus, other), id);
    }

    public void pay(Ctx c, long id, String mode) {
        c.needHead();
        String m = U.pick(mode, "Cash", "முறை", FeeService.MODES);
        Map<String, Object> r = mustOne("சம்பள பதிவு", "SELECT status FROM payroll WHERE id = ? AND inst_id = ?", id, c.inst());
        if ("paid".equals(r.get("status"))) {
            throw new ApiException(409, "ஏற்கனவே செலுத்தப்பட்டது");
        }
        db.update("UPDATE payroll SET status = 'paid', paid_on = ?, pay_mode = ? WHERE id = ?", today(), m, id);
    }

    public Map<String, Object> payAll(Ctx c, String month, String mode) {
        c.needHead();
        String m = U.pick(mode, "Cash", "முறை", FeeService.MODES);
        YearMonth ym = AttendanceService.month(month);
        int n = db.update("UPDATE payroll SET status = 'paid', paid_on = ?, pay_mode = ? WHERE inst_id = ? AND pay_month = ? AND status = 'draft'",
                today(), m, c.inst(), ym.toString());
        return U.map("paid", n);
    }

    public void delete(Ctx c, long id) {
        c.needHead();
        Map<String, Object> r = mustOne("சம்பள பதிவு", "SELECT status FROM payroll WHERE id = ? AND inst_id = ?", id, c.inst());
        if (!"draft".equals(r.get("status"))) {
            throw new ApiException(409, "செலுத்தப்பட்ட சம்பளத்தை நீக்க முடியாது");
        }
        db.update("DELETE FROM payroll WHERE id = ?", id);
    }
}
