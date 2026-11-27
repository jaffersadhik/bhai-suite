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
    }
}
