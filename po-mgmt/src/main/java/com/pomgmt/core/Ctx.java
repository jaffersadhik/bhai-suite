package com.pomgmt.core;

/** Who is calling, resolved from the X-Auth token. Roles per the spec.
 *  tenant = the owning admin's id (admin: own id; operational user: the admin who created them; system: 0 = all). */
public record Ctx(long userId, String role, String name, String token, long tenant) {

    public boolean admin() {
        return "admin".equals(role);
    }

    /** top-level user-management-only role: can manage users (incl. admins) but has no PO / budget powers. */
    public boolean system() {
        return "system".equals(role);
    }

    public boolean management() {
        return "management".equals(role);
    }

    /** admin or system may manage users. */
    public boolean canManageUsers() {
        return admin() || system();
    }

    public void needManageUsers() {
        if (!canManageUsers()) {
            throw new ApiException(403, "User management அனுமதி இல்லை (Admin / System only)");
        }
    }

    /** admin, purchase_executive or purchase_manager may create / edit purchase entries. */
    public boolean canCreate() {
        return admin() || "purchase_executive".equals(role) || "purchase_manager".equals(role);
    }

    /** admin or approver may approve / reject POs and budgets. */
    public boolean canApprove() {
        return admin() || "approver".equals(role);
    }

    public void needCreate() {
        if (!canCreate()) {
            throw new ApiException(403, "PO உருவாக்க அனுமதி இல்லை (Executive / Manager / Admin only)");
        }
    }

    public void needApprove() {
        if (!canApprove()) {
            throw new ApiException(403, "Approve அனுமதி இல்லை (Approver / Admin only)");
        }
    }

    public void needAdmin() {
        if (!admin()) {
            throw new ApiException(403, "Admin அனுமதி இல்லை (Admin only)");
        }
    }
}
