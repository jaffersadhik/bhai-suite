package com.mhotel.core;

import java.time.ZoneId;

/** All services, wired once. No Spring types here, so the logic stays testable on its own. */
public class Core {
    public final Db db;
    public final ZoneId zone;
    public final AuthService auth;
    public final HotelService hotel;

    public Core(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
        this.auth = new AuthService(db, zone);
        this.hotel = new HotelService(db, zone);
    }

    /** Runs once at startup, after schema.sql. */
    public void start() {
        new Migrations(db).run();
        auth.ensureAdmin();
    }
}
