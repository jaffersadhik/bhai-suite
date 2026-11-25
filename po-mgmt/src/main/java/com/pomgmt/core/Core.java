package com.pomgmt.core;

import java.time.ZoneId;

/** All services, wired once. No Spring types here, so the logic stays testable on its own. */
public class Core {
    public final Db db;
    public final ZoneId zone;
    public final String budgetEnforcement;
    public final AuthService auth;
    public final PurchaseService purchase;

    public Core(Db db, ZoneId zone, String budgetEnforcement) {
        this.db = db;
        this.zone = zone;
        this.budgetEnforcement = budgetEnforcement == null ? "warn" : budgetEnforcement.toLowerCase();
        this.auth = new AuthService(db, zone);
        this.purchase = new PurchaseService(db, zone, this.budgetEnforcement);
    }

    /** Runs once at startup, after schema.sql. */
    public void start() {
        new Migrations(db).run();
        auth.ensureAdmin();
        new Seed(db, zone).run();
    }
}
