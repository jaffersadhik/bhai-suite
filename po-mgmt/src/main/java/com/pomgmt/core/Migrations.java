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
    }
}
