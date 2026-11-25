package com.mhotel.core;

/** Who is calling, resolved from the X-Auth token. Never taken from the request body. */
public record Ctx(long userId, String role, String name, String token) {

    public boolean admin() {
        return "admin".equals(role);
    }

    public boolean cashier() {
        return "cashier".equals(role);
    }

    public boolean waiter() {
        return "waiter".equals(role);
    }

    /** admin or cashier: billing, payments, menu / table setup. */
    public boolean bill() {
        return admin() || cashier();
    }

    public void needAdmin() {
        if (!admin()) {
            throw new ApiException(403, "இதற்கு அனுமதி இல்லை (Admin only)");
        }
    }

    public void needBill() {
        if (!bill()) {
            throw new ApiException(403, "Billing அனுமதி இல்லை (Admin / Cashier only)");
        }
    }
}
