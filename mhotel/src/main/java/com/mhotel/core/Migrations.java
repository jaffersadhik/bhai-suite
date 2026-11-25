package com.mhotel.core;

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
        // Example for future changes:
        // if (!hasColumn("orders", "note")) db.update("ALTER TABLE orders ADD COLUMN note VARCHAR(200) NOT NULL DEFAULT ''");
    }
}
