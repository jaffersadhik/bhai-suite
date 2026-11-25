package com.pomgmt.core;

/** Who is calling, resolved from the X-Auth token. Roles per the spec. */
public record Ctx(long userId, String role, String name, String token) {

    public boolean admin() {
        return "admin".equals(role);
    }

    public boolean management() {
        return "management".equals(role);
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
