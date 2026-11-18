package com.mschool.web;

import com.mschool.core.ApiException;
import com.mschool.core.Core;
import com.mschool.core.Ctx;
import com.mschool.core.DbException;
import com.mschool.core.U;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every endpoint: authenticate from the X-Auth header, call the service, map errors to {"error": "..."}. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger LOG = Logger.getLogger(ApiController.class.getName());
    private static final Map<String, Object> OK = Map.of("ok", true);

    private final Core core;

    public ApiController(Core core) {
        this.core = core;
    }

    /** health check (no login) - used by the Docker HEALTHCHECK */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return OK;
    }

    // ---------------------------------------------------------------- plumbing

    private ResponseEntity<Object> guard(Supplier<Object> work) {
        try {
            Object r = work.get();
            return ResponseEntity.ok(r == null ? OK : r);
        } catch (ApiException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (DbException e) {
            LOG.log(Level.WARNING, "database error", e);
            String m = String.valueOf(e.getMessage());
            if (m.contains("Duplicate entry")) {
                return ResponseEntity.status(409).body(Map.of("error", "இந்த பதிவு ஏற்கனவே உள்ளது"));
            }
            return ResponseEntity.status(500).body(Map.of("error", "தரவுத்தள பிழை. மீண்டும் முயற்சிக்கவும்"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "unexpected error", e);
            return ResponseEntity.status(500).body(Map.of("error", "எதிர்பாராத பிழை"));
        }
    }

    private ResponseEntity<Object> run(String token, Function<Ctx, Object> f) {
        return guard(() -> f.apply(core.auth.ctx(token)));
    }

    private ResponseEntity<Object> done(String token, java.util.function.Consumer<Ctx> f) {
        return guard(() -> {
            f.accept(core.auth.ctx(token));
            return OK;
        });
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    // ---------------------------------------------------------------- session

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return guard(() -> core.auth.login(U.s(m.get("user")), m.get("pass") == null ? null : m.get("pass").toString()));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.auth::me);
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = "X-Auth", required = false) String t) {
        return done(t, core.auth::logout);
    }

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.auth.changePassword(c, m.get("oldPass") == null ? null : m.get("oldPass").toString(),
                m.get("newPass") == null ? null : m.get("newPass").toString()));
    }

    @GetMapping("/data")
    public ResponseEntity<Object> data(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.master::data);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Object> dashboard(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.dashboard::dashboard);
    }

    // ---------------------------------------------------------------- users, institutions

    @GetMapping("/users")
    public ResponseEntity<Object> users(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.auth::users);
    }

    @PostMapping("/users")
    public ResponseEntity<Object> saveUser(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.auth.saveUser(c, m));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Object> deleteUser(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.auth.deleteUser(c, id));
    }

    @GetMapping("/admin/overview")
    public ResponseEntity<Object> adminOverview(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.auth::adminOverview);
    }

    @PostMapping("/admin/institution")
    public ResponseEntity<Object> saveInstitution(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.auth.saveInstitution(c, m));
    }

    /** The head of an institution edits his own name, phone, address, weekly off. */
    @PostMapping("/institution")
    public ResponseEntity<Object> saveMyInstitution(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = new HashMap<>(body(b));
        return run(t, c -> {
            c.needHead();
            m.put("id", c.inst());
            return core.auth.saveInstitution(c, m);
        });
    }

    @DeleteMapping("/admin/institution/{id}")
    public ResponseEntity<Object> deleteInstitution(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.auth.deleteInstitution(c, id));
    }

    @PostMapping("/admin/open")
    public ResponseEntity<Object> open(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.auth.open(c, U.l(m.get("instId"), 0)));
    }

    // ---------------------------------------------------------------- set-up: years, classes, sections, rooms

    @PostMapping("/year")
    public ResponseEntity<Object> year(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.saveYear(c, m));
    }

    @PostMapping("/class")
    public ResponseEntity<Object> saveClass(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.saveClass(c, m));
    }

    @DeleteMapping("/class/{id}")
    public ResponseEntity<Object> deleteClass(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.master.deleteClass(c, id));
    }

    @PostMapping("/section")
    public ResponseEntity<Object> saveSection(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.saveSection(c, m));
    }

    @DeleteMapping("/section/{id}")
    public ResponseEntity<Object> deleteSection(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.master.deleteSection(c, id));
    }

    @PostMapping("/setup/template")
    public ResponseEntity<Object> template(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.applyTemplate(c, U.s(m.get("template"))));
    }

    @GetMapping("/rooms")
    public ResponseEntity<Object> rooms(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.master::rooms);
    }

    @GetMapping("/room/{id}")
    public ResponseEntity<Object> room(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return run(t, c -> core.master.room(c, id));
    }

    @PostMapping("/room")
    public ResponseEntity<Object> saveRoom(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.saveRoom(c, m));
    }

    @DeleteMapping("/room/{id}")
    public ResponseEntity<Object> deleteRoom(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.master.deleteRoom(c, id));
    }

    @PostMapping("/room/{id}/maint")
    public ResponseEntity<Object> addMaint(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.master.addMaintenance(c, id, m));
    }

    @PostMapping("/maint/{id}/done")
    public ResponseEntity<Object> doneMaint(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.master.finishMaintenance(c, id, m));
    }

    @DeleteMapping("/maint/{id}")
    public ResponseEntity<Object> deleteMaint(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.master.deleteMaintenance(c, id));
    }

    // ---------------------------------------------------------------- students and staff

    @GetMapping("/students")
    public ResponseEntity<Object> students(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.people.students(c, q));
    }

    @GetMapping("/student/{id}")
    public ResponseEntity<Object> student(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return run(t, c -> core.people.student(c, id));
    }

    @PostMapping("/student")
    public ResponseEntity<Object> saveStudent(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.people.saveStudent(c, m));
    }

    @PostMapping("/student/{id}/status")
    public ResponseEntity<Object> studentStatus(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.people.setStudentStatus(c, id, U.s(m.get("status"))));
    }

    @PostMapping("/students/import")
    public ResponseEntity<Object> importStudents(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.people.importStudents(c, m.get("csv") == null ? null : m.get("csv").toString()));
    }

    @GetMapping("/staff")
    public ResponseEntity<Object> staff(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.people.staff(c, q));
    }

    @GetMapping("/staff/{id}")
    public ResponseEntity<Object> staffMember(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return run(t, c -> core.people.staffMember(c, id));
    }

    @PostMapping("/staff")
    public ResponseEntity<Object> saveStaff(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.people.saveStaff(c, m));
    }

    @PostMapping("/staff/{id}/status")
    public ResponseEntity<Object> staffStatus(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.people.setStaffStatus(c, id, U.s(m.get("status"))));
    }

    // ---------------------------------------------------------------- attendance

    @GetMapping("/attendance/sheet")
    public ResponseEntity<Object> sheet(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.attendance.sheet(c, U.l(q.get("sectionId"), 0), q.get("date")));
    }

    @PostMapping("/attendance/sheet")
    public ResponseEntity<Object> saveSheet(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.attendance.saveSheet(c, m));
    }

    @GetMapping("/attendance/day")
    public ResponseEntity<Object> attendanceDay(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.attendance.day(c, q.get("date")));
    }

    @GetMapping("/attendance/month")
    public ResponseEntity<Object> attendanceMonth(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.attendance.monthly(c, U.l(q.get("sectionId"), 0), q.get("month")));
    }

    @GetMapping("/attendance/staff")
    public ResponseEntity<Object> staffSheet(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.attendance.staffSheet(c, q.get("date")));
    }

    @PostMapping("/attendance/staff")
    public ResponseEntity<Object> saveStaffSheet(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.attendance.saveStaffSheet(c, m));
    }

    @GetMapping("/attendance/staff/month")
    public ResponseEntity<Object> staffMonth(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.attendance.staffMonthly(c, q.get("month")));
    }

    // ---------------------------------------------------------------- fees

    @PostMapping("/fees/head")
    public ResponseEntity<Object> saveHead(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.fees.saveHead(c, m));
    }

    @DeleteMapping("/fees/head/{id}")
    public ResponseEntity<Object> deleteHead(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.fees.deleteHead(c, id));
    }

    @GetMapping("/fees/structure")
    public ResponseEntity<Object> structure(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.fees.structure(c, U.l(q.get("yearId"), 0), U.l(q.get("classId"), 0)));
    }

    @PutMapping("/fees/structure")
    public ResponseEntity<Object> saveStructure(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.fees.saveStructure(c, m));
    }

    @GetMapping("/fees/student/{id}")
    public ResponseEntity<Object> feeStatement(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestParam Map<String, String> q) {
        return run(t, c -> core.fees.statement(c, id, U.lo(q.get("yearId"))));
    }

    @PostMapping("/fees/concession")
    public ResponseEntity<Object> concession(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.fees.saveConcession(c, m));
    }

    @PostMapping("/fees/receipt")
    public ResponseEntity<Object> createReceipt(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.fees.createReceipt(c, m));
    }

    @GetMapping("/fees/receipt/{id}")
    public ResponseEntity<Object> receipt(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return run(t, c -> core.fees.receipt(c, id));
    }

    @GetMapping("/fees/receipts")
    public ResponseEntity<Object> receipts(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.fees.receipts(c, q));
    }

    @PostMapping("/fees/receipt/{id}/cancel")
    public ResponseEntity<Object> cancelReceipt(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.fees.cancelReceipt(c, id, U.s(m.get("reason"))));
    }

    @GetMapping("/fees/pending")
    public ResponseEntity<Object> pending(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.fees.pending(c, q));
    }

    @GetMapping("/fees/report")
    public ResponseEntity<Object> feeReport(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.fees.report(c, q.get("date")));
    }

    // ---------------------------------------------------------------- payroll

    @GetMapping("/payroll")
    public ResponseEntity<Object> payroll(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.payroll.list(c, q.get("month")));
    }

    @PostMapping("/payroll/generate")
    public ResponseEntity<Object> generate(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.payroll.generate(c, m));
    }

    @PutMapping("/payroll/{id}")
    public ResponseEntity<Object> updatePayroll(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.payroll.update(c, id, m));
    }

    @PostMapping("/payroll/{id}/pay")
    public ResponseEntity<Object> pay(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return done(t, c -> core.payroll.pay(c, id, U.s(m.get("mode"))));
    }

    @PostMapping("/payroll/payall")
    public ResponseEntity<Object> payAll(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.payroll.payAll(c, U.s(m.get("month")), U.s(m.get("mode"))));
    }

    @DeleteMapping("/payroll/{id}")
    public ResponseEntity<Object> deletePayroll(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable long id) {
        return done(t, c -> core.payroll.delete(c, id));
    }

    // ---------------------------------------------------------------- promotion

    @GetMapping("/promotion/summary")
    public ResponseEntity<Object> promotionSummary(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.promotion::summary);
    }

    @GetMapping("/promotion/sheet")
    public ResponseEntity<Object> promotionSheet(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam Map<String, String> q) {
        return run(t, c -> core.promotion.sheet(c, U.l(q.get("sectionId"), 0)));
    }

    @PostMapping("/promotion/results")
    public ResponseEntity<Object> promotionResults(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.promotion.setResults(c, m));
    }

    @PostMapping("/promotion/run")
    public ResponseEntity<Object> promotionRun(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.promotion.run(c, m));
    }
}
