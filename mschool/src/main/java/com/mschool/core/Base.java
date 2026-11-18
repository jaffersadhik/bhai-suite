package com.mschool.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Shared plumbing for the services. */
public abstract class Base {
    protected final Db db;
    protected final ZoneId zone;

    protected Base(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
    }

    protected LocalDate today() {
        return LocalDate.now(zone);
    }

    /** The current academic year of the institution, or null. */
    protected Map<String, Object> currentYear(long inst) {
        return db.one("SELECT id, name, start_date, end_date FROM academic_years WHERE inst_id = ? AND is_current = 1", inst);
    }

    /** Id of the current academic year; 400 when none was created yet. */
    protected long needYear(long inst) {
        Map<String, Object> y = currentYear(inst);
        if (y == null) {
            throw new ApiException(400, "முதலில் கல்வியாண்டை உருவாக்கவும் (அமைப்பு > கல்வியாண்டு)");
        }
        return U.n(y, "id");
    }

    protected Map<String, Object> mustOne(String what, String sql, Object... args) {
        Map<String, Object> r = db.one(sql, args);
        if (r == null) {
            throw new ApiException(404, what + " கிடைக்கவில்லை");
        }
        return r;
    }

    /** 404 unless the row with this id belongs to the institution. */
    protected void belongs(String table, long id, long inst, String what) {
        if (db.one("SELECT id FROM " + table + " WHERE id = ? AND inst_id = ?", id, inst) == null) {
            throw new ApiException(404, what + " கிடைக்கவில்லை");
        }
    }

    protected static String pad(long n, int width) {
        String s = Long.toString(n);
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }
}
