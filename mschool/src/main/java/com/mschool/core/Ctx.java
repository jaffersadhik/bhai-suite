package com.mschool.core;

/**
 * Who is calling. instId comes from the user (principal, office, teacher) or, for the platform admin,
 * from the institution he opened. It is never taken from the request.
 */
public record Ctx(long userId, String type, Long instId, Long staffId, String name, String token) {

    public boolean admin() {
        return "admin".equals(type);
    }

    public boolean principal() {
        return "principal".equals(type);
    }

    public boolean office() {
        return "office".equals(type);
    }

    public boolean teacher() {
        return "teacher".equals(type);
    }

    /** admin or principal: setup, salary, discounts, promotion. */
    public boolean head() {
        return admin() || principal();
    }

    /** admin, principal or office: records, fees, staff attendance. */
    public boolean desk() {
        return admin() || principal() || office();
    }

    public long inst() {
        if (instId == null) {
            throw new ApiException(400, "முதலில் ஒரு நிறுவனத்தை திறக்கவும்");
        }
        return instId;
    }

    public void needHead() {
        if (!head()) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை");
        }
    }

    public void needDesk() {
        if (!desk()) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை");
        }
    }

    public void needAdmin() {
        if (!admin()) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை");
        }
    }
}
