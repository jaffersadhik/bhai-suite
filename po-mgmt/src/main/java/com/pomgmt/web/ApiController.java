package com.pomgmt.web;

import com.pomgmt.core.ApiException;
import com.pomgmt.core.Core;
import com.pomgmt.core.Ctx;
import com.pomgmt.core.DbException;
import com.pomgmt.core.U;
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

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return OK;
    }

    private ResponseEntity<Object> guard(Supplier<Object> work) {
        try {
            Object r = work.get();
            return ResponseEntity.ok(r == null ? OK : r);
        } catch (ApiException e) {
            return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
        } catch (DbException e) {
            LOG.log(Level.WARNING, "database error", e);
            String m = String.valueOf(e.getMessage());
            if (m.contains("Duplicate entry")) return ResponseEntity.status(409).body(Map.of("error", "இந்த பதிவு ஏற்கனவே உள்ளது"));
            return ResponseEntity.status(500).body(Map.of("error", "தரவுத்தள பிழை. மீண்டும் முயற்சிக்கவும்"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "unexpected error", e);
            return ResponseEntity.status(500).body(Map.of("error", "எதிர்பாராத பிழை"));
        }
    }

    private ResponseEntity<Object> run(String token, Function<Ctx, Object> f) {
        return guard(() -> f.apply(core.auth.ctx(token)));
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    // ---- session
    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return guard(() -> core.auth.login(U.s(m.get("user")), U.s(m.get("password"))));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.auth::me);
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = "X-Auth", required = false) String t) {
        return guard(() -> { core.auth.logout(t); return OK; });
    }

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> { core.auth.changePassword(c, U.str(m.get("old")), U.str(m.get("new"))); return OK; });
    }

    // ---- bootstrap + dashboard
    @GetMapping("/data")
    public ResponseEntity<Object> data(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.purchase::data);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Object> dashboard(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, core.purchase::dashboard);
    }

    // ---- masters
    @GetMapping("/suppliers")
    public ResponseEntity<Object> suppliers(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, c -> Map.of("suppliers", core.purchase.suppliers()));
    }

    @PostMapping("/suppliers")
    public ResponseEntity<Object> saveSupplier(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.saveSupplier(c, body(b)));
    }

    @PostMapping("/agencies")
    public ResponseEntity<Object> addAgency(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.addAgency(c, body(b)));
    }

    @GetMapping("/items")
    public ResponseEntity<Object> items(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, c -> Map.of("items", core.purchase.items()));
    }

    @PostMapping("/items")
    public ResponseEntity<Object> saveItem(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.saveItem(c, body(b)));
    }

    // ---- budget control
    @PostMapping("/budget")
    public ResponseEntity<Object> setBudget(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.setBudget(c, body(b)));
    }

    @GetMapping("/budgets")
    public ResponseEntity<Object> budgets(@RequestHeader(value = "X-Auth", required = false) String t,
                                          @RequestParam(value = "period", required = false) String period,
                                          @RequestParam(value = "branch", required = false) String branch) {
        return run(t, c -> Map.of("budgets", core.purchase.listBudgets(period, branch)));
    }

    @GetMapping("/budget/balance")
    public ResponseEntity<Object> balance(@RequestHeader(value = "X-Auth", required = false) String t,
                                          @RequestParam("item") String item, @RequestParam("branch") String branch,
                                          @RequestParam("period") String period) {
        return run(t, c -> core.purchase.budgetBalance(c, item, branch, period));
    }

    // ---- purchase entry -> branch POs
    @PostMapping("/entry")
    public ResponseEntity<Object> createEntry(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.createEntry(c, body(b)));
    }

    // ---- POs (po_number contains '/', so it is a query param, not a path variable)
    @GetMapping("/pos")
    public ResponseEntity<Object> listPos(@RequestHeader(value = "X-Auth", required = false) String t,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "branch", required = false) String branch,
                                          @RequestParam(value = "ref", required = false) String ref) {
        return run(t, c -> core.purchase.listPos(status, branch, ref));
    }

    @GetMapping("/po")
    public ResponseEntity<Object> getPo(@RequestHeader(value = "X-Auth", required = false) String t, @RequestParam("no") String no) {
        return run(t, c -> core.purchase.poDetail(no));
    }

    @PostMapping("/po/approve")
    public ResponseEntity<Object> approve(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.approvePo(c, body(b)));
    }

    @PostMapping("/po/cancel")
    public ResponseEntity<Object> cancel(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(t, c -> core.purchase.cancelPo(c, U.str(m.get("poNumber")), U.str(m.get("reason"))));
    }

    @PostMapping("/po/receive")
    public ResponseEntity<Object> receive(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> core.purchase.receiveGoods(c, body(b)));
    }

    // ---- reports
    @GetMapping("/report")
    public ResponseEntity<Object> report(@RequestHeader(value = "X-Auth", required = false) String t,
                                         @RequestParam("report") String report,
                                         @RequestParam(value = "period", required = false) String period) {
        return run(t, c -> core.purchase.report(report, period));
    }

    // ---- users (admin)
    @GetMapping("/users")
    public ResponseEntity<Object> users(@RequestHeader(value = "X-Auth", required = false) String t) {
        return run(t, c -> Map.of("users", core.auth.users(c)));
    }

    @PostMapping("/users")
    public ResponseEntity<Object> saveUser(@RequestHeader(value = "X-Auth", required = false) String t, @RequestBody(required = false) Map<String, Object> b) {
        return run(t, c -> { core.auth.saveUser(c, body(b)); return Map.of("users", core.auth.users(c)); });
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Object> deleteUser(@RequestHeader(value = "X-Auth", required = false) String t, @PathVariable("id") long id) {
        return run(t, c -> { core.auth.deleteUser(c, id); return Map.of("users", core.auth.users(c)); });
    }
}
