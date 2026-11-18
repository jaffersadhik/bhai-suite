package com.mschool.core;

import java.time.ZoneId;

/** All services, wired once. No Spring types in here, so the logic stays testable on its own. */
public class Core {
    public final Db db;
    public final ZoneId zone;
    public final AuthService auth;
    public final MasterService master;
    public final PeopleService people;
    public final AttendanceService attendance;
    public final FeeService fees;
    public final PayrollService payroll;
    public final PromotionService promotion;
    public final DashboardService dashboard;

    public Core(Db db, ZoneId zone) {
        this.db = db;
        this.zone = zone;
        this.auth = new AuthService(db, zone);
        this.master = new MasterService(db, zone);
        this.people = new PeopleService(db, zone);
        this.attendance = new AttendanceService(db, zone);
        this.fees = new FeeService(db, zone);
        this.payroll = new PayrollService(db, zone);
        this.promotion = new PromotionService(db, zone);
        this.dashboard = new DashboardService(db, zone, attendance, fees);
    }

    /** Runs once at startup, after schema.sql. */
    public void start() {
        new Migrations(db).run();
        auth.ensureAdmin();
    }
}
