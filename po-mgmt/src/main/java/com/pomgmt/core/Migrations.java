package com.pomgmt.core;

/**
 * Column / data changes for already-created databases. schema.sql only runs CREATE TABLE IF NOT EXISTS,
 * so anything that must change an existing table goes here (idempotent — runs on every start).
 */
public class Migrations {
    private final Db db;

    public Migrations(Db db) {
        this.db = db;
    }

    private boolean hasColumn(String table, String col) {
        return db.one("SELECT 1 AS x FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                table, col) != null;
    }

    private boolean hasIndex(String table, String index) {
        return db.one("SELECT 1 AS x FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                table, index) != null;
    }

    private boolean indexHasColumn(String table, String index, String col) {
        return db.one("SELECT 1 AS x FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? AND column_name = ?",
                table, index, col) != null;
    }

    public void run() {
        // branch type (warehouse / branch / shop) — added for branch management
        if (!hasColumn("branches", "type")) {
            db.update("ALTER TABLE branches ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'branch'");
        }
        // created_by: which user created each user (admin hierarchy isolation). Added for user-type system.
        if (!hasColumn("users", "created_by")) {
            db.update("ALTER TABLE users ADD COLUMN created_by BIGINT NULL");
            // backfill existing operational users onto the single admin so they stay visible after upgrade
            java.util.List<java.util.Map<String, Object>> admins = db.query("SELECT id FROM users WHERE role = 'admin'");
            if (admins.size() == 1) {
                db.update("UPDATE users SET created_by = ? WHERE created_by IS NULL AND role NOT IN ('admin','system')",
                        U.lng(admins.get(0).get("id")));
            }
        }
        // multi-tenant data isolation: tenant_id on every data table (= owning admin). Added for full per-admin separation.
        java.util.List<java.util.Map<String, Object>> admins2 = db.query("SELECT id FROM users WHERE role = 'admin'");
        Long singleTenant = admins2.size() == 1 ? U.lng(admins2.get(0).get("id")) : null;
        for (String tbl : new String[]{"branches", "suppliers", "items", "budgets", "purchase_entries", "purchase_orders"}) {
            if (!hasColumn(tbl, "tenant_id")) {
                db.update("ALTER TABLE " + tbl + " ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0");
                if (singleTenant != null) db.update("UPDATE " + tbl + " SET tenant_id = ? WHERE tenant_id = 0", singleTenant);
            }
        }
        // per-tenant unique codes: two admins may each have their own 'R1' branch / same item codes
        if (hasIndex("branches", "uk_branch_code") && !indexHasColumn("branches", "uk_branch_code", "tenant_id")) {
            db.update("ALTER TABLE branches DROP INDEX uk_branch_code, ADD UNIQUE KEY uk_branch_code (tenant_id, code)");
        }
        if (hasIndex("items", "uk_item_code") && !indexHasColumn("items", "uk_item_code", "tenant_id")) {
            db.update("ALTER TABLE items DROP INDEX uk_item_code, ADD UNIQUE KEY uk_item_code (tenant_id, item_code)");
        }
    }
}
