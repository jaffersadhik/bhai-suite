package com.mhotel.web;

import com.mhotel.core.ApiException;
import com.mhotel.core.Core;
import com.mhotel.core.Ctx;
import com.mhotel.core.DbException;
import com.mhotel.core.U;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
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
    private final String webhookSecret;

    public ApiController(Core core, @Value("${mhotel.webhook.secret:}") String webhookSecret) {
        this.core = core;
        this.webhookSecret = webhookSecret;
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

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    // ---------------------------------------------------------------- session
    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return guard(() -> core.auth.login(U.s(m.get("user")), U.s(m.get("password"))));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, core.auth::me);
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = "X-Auth", required = false) String token) {
        return guard(() -> {
            core.auth.logout(token);
            return OK;
        });
    }

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestHeader(value = "X-Auth", required = false) String token,
                                           @RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> m = body(b);
        return run(token, c -> {
            core.auth.changePassword(c, U.str(m.get("old")), U.str(m.get("new")));
            return OK;
        });
    }

    // ---------------------------------------------------------------- bootstrap + dashboard
    @GetMapping("/data")
    public ResponseEntity<Object> data(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, core.hotel::data);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Object> dashboard(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, core.hotel::dashboard);
    }

    // ---------------------------------------------------------------- menu
    @GetMapping("/menu")
    public ResponseEntity<Object> menu(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, c -> Map.of("menu", core.hotel.menu(c)));
    }

    @PostMapping("/menu")
    public ResponseEntity<Object> saveMenu(@RequestHeader(value = "X-Auth", required = false) String token,
                                           @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.saveMenu(c, body(b)));
    }

    @DeleteMapping("/menu/{id}")
    public ResponseEntity<Object> deleteMenu(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> core.hotel.deleteMenu(c, id));
    }

    // ---------------------------------------------------------------- tables
    @GetMapping("/tables")
    public ResponseEntity<Object> tables(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, c -> Map.of("tables", core.hotel.tables(c)));
    }

    @PostMapping("/tables")
    public ResponseEntity<Object> saveTable(@RequestHeader(value = "X-Auth", required = false) String token,
                                            @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.saveTable(c, body(b)));
    }

    @DeleteMapping("/tables/{id}")
    public ResponseEntity<Object> deleteTable(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> core.hotel.deleteTable(c, id));
    }

    // ---------------------------------------------------------------- orders
    @GetMapping("/orders")
    public ResponseEntity<Object> orders(@RequestHeader(value = "X-Auth", required = false) String token,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "type", required = false) String type) {
        return run(token, c -> core.hotel.orders(c, status, type));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Object> orderGet(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> core.hotel.orderGet(c, id));
    }

    @PostMapping("/orders")
    public ResponseEntity<Object> createOrder(@RequestHeader(value = "X-Auth", required = false) String token,
                                              @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.createOrder(c, body(b)));
    }

    @PostMapping("/orders/{id}/lines")
    public ResponseEntity<Object> setLines(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id,
                                           @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.setLines(c, id, body(b)));
    }

    @PostMapping("/orders/{id}/bill")
    public ResponseEntity<Object> billOrder(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id,
                                            @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.billOrder(c, id, body(b)));
    }

    @PostMapping("/orders/{id}/pay")
    public ResponseEntity<Object> payOrder(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id,
                                           @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> core.hotel.payOrder(c, id, body(b)));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<Object> cancelOrder(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> core.hotel.cancelOrder(c, id));
    }

    @GetMapping("/orders/{id}/whatsapp")
    public ResponseEntity<Object> whatsapp(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> core.hotel.whatsapp(c, id));
    }

    // ---------------------------------------------------------------- delivery-partner webhook (Swiggy / Zomato)
    /** No login; the partner must send the shared secret in X-Webhook-Secret (MHOTEL_WEBHOOK_SECRET). */
    @PostMapping("/delivery/webhook")
    public ResponseEntity<Object> webhook(@RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
                                          @RequestBody(required = false) Map<String, Object> b) {
        return guard(() -> {
            if (U.blank(webhookSecret) || !webhookSecret.equals(secret)) {
                throw new ApiException(401, "invalid webhook secret");
            }
            return core.hotel.webhookOrder(body(b));
        });
    }

    // ---------------------------------------------------------------- users + settings (admin)
    @GetMapping("/users")
    public ResponseEntity<Object> users(@RequestHeader(value = "X-Auth", required = false) String token) {
        return run(token, c -> Map.of("users", core.auth.users(c)));
    }

    @PostMapping("/users")
    public ResponseEntity<Object> saveUser(@RequestHeader(value = "X-Auth", required = false) String token,
                                           @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> {
            core.auth.saveUser(c, body(b));
            return Map.of("users", core.auth.users(c));
        });
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Object> deleteUser(@RequestHeader(value = "X-Auth", required = false) String token, @PathVariable("id") long id) {
        return run(token, c -> {
            core.auth.deleteUser(c, id);
            return Map.of("users", core.auth.users(c));
        });
    }

    @PostMapping("/settings/shop-name")
    public ResponseEntity<Object> shopName(@RequestHeader(value = "X-Auth", required = false) String token,
                                           @RequestBody(required = false) Map<String, Object> b) {
        return run(token, c -> {
            core.hotel.setShopName(c, U.str(body(b).get("name")));
            return OK;
        });
    }
}
