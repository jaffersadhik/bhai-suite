package com.mschool.core;

/**
 * Changes for databases that already exist. schema.sql only creates missing tables, so a new column on an old
 * table is added here (MySQL has no ADD COLUMN IF NOT EXISTS). Nothing to migrate in version 1.
 */
public class Migrations {
    private final Db db;

    public Migrations(Db db) {
        this.db = db;
    }

    public void run() {
        // Example for a later version:
        // addColumn("students", "blood_group", "VARCHAR(5) NULL");
    }

    @SuppressWarnings("unused")
    private void addColumn(String table, String column, String definition) {
        if (db.one("SELECT 1 x FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?", table, column) == null) {
            db.update("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
