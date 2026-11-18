package com.bhai.billing.web;

import com.bhai.billing.core.ApiException;
import com.bhai.billing.core.BillingService;
import com.bhai.billing.core.DbException;
import com.bhai.billing.core.PostDeployTests;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** REST API used by the app page (static/index.html). The session token comes in the X-Auth header. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final String AUTH = "X-Auth";
    private final BillingService svc;
    private final PostDeployTests tests;
    private final com.bhai.billing.core.Translator translator;

    public ApiController(BillingService svc, PostDeployTests tests, com.bhai.billing.core.Translator translator) {
        this.svc = svc;
        this.tests = tests;
        this.translator = translator;
    }

    // ---- session
    @GetMapping("/ping")
    public ResponseEntity<Object> ping() { return run(() -> Collections.singletonMap("ok", true)); }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        String ip = xf != null && !xf.isBlank() ? xf.split(",")[0].trim() : req.getRemoteAddr();
        return run(() -> svc.login(nn(body), ip, req.getHeader("User-Agent")));
    }

    /** online translation of Tamil master data / phrases ({to:["en"], items:[{s,e?}]}); open to the login page and the public shop too, rate limited */
    @PostMapping("/translate")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> translate(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        String ip = xf != null && !xf.isBlank() ? xf.split(",")[0].trim() : req.getRemoteAddr();
        return run(() -> {
            Map<String, Object> b = nn(body);
            translator.checkRate(ip);
            Object to = b.get("to"), items = b.get("items");
            if (!(to instanceof java.util.List) || !(items instanceof java.util.List)) throw new ApiException(400, "Invalid request");
            java.util.List<String> langs = new java.util.ArrayList<>();
            for (Object o : (java.util.List<Object>) to) langs.add(String.valueOf(o));
            return java.util.Collections.singletonMap("map", translator.translate(langs, (java.util.List<Object>) items));
        });
    }

    @PostMapping("/me/lang")
    public ResponseEntity<Object> setLang(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.setLang(auth, nn(body))); }

    @PostMapping("/ping")
    public ResponseEntity<Object> ping(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.ping(auth)); }

    @GetMapping("/admin/sessions")
    public ResponseEntity<Object> adminSessions(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.adminSessions(auth)); }

    @PostMapping("/admin/sessions/kill")
    public ResponseEntity<Object> adminKillSession(@RequestHeader(value = AUTH, required = false) String auth,
                                                   @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.adminKillSession(auth, nn(body))); }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.me(auth)); }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.logout(auth)); }

    @PostMapping("/password")
    public ResponseEntity<Object> password(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.password(auth, nn(body))); }

    // ---- company data (company always taken from the session)
    @GetMapping("/data")
    public ResponseEntity<Object> data(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.data(auth)); }

    @PutMapping("/catalog")
    public ResponseEntity<Object> catalog(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.putCatalog(auth, nn(body))); }

    @GetMapping("/bills")
    public ResponseEntity<Object> bills(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.bills(auth)); }

    @PostMapping("/bills")
    public ResponseEntity<Object> addBill(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.addBill(auth, nn(body))); }

    // ---- offline: numbers reserved for a device + everything it needs to work without internet
    @PostMapping("/offline/reserve")
    public ResponseEntity<Object> reserve(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.reserveNumbers(auth, nn(body))); }

    @GetMapping("/sync")
    public ResponseEntity<Object> sync(@RequestHeader(value = AUTH, required = false) String auth,
                                       @RequestParam(value = "cv", required = false) String cv,
                                       @RequestParam(value = "days", required = false) String days) { return run(() -> svc.syncPull(auth, cv, days)); }

    // ---- software invoices (owner: own company; admin: ?company=)
    @GetMapping("/invoices")
    public ResponseEntity<Object> invoices(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestParam(value = "company", required = false) String company) { return run(() -> svc.invoices(auth, company)); }

    @GetMapping("/invoices/detail")
    public ResponseEntity<Object> invoiceDetail(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestParam(value = "company", required = false) String company,
                                                @RequestParam(value = "month", required = false) String month) { return run(() -> svc.invoiceDetail(auth, company, month)); }

    @GetMapping("/admin/invoices")
    public ResponseEntity<Object> adminInvoices(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestParam(value = "month", required = false) String month) { return run(() -> svc.adminInvoices(auth, month)); }

    @PostMapping("/admin/invoices/generate")
    public ResponseEntity<Object> generateInvoices(@RequestHeader(value = AUTH, required = false) String auth,
                                                   @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.adminGenerateInvoices(auth, nn(body))); }

    @PostMapping("/admin/invoices/{id}/pay")
    public ResponseEntity<Object> payInvoice(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                             @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.payInvoice(auth, id, nn(body))); }

    // ---- item photos
    @PostMapping("/items/{id}/photo")
    public ResponseEntity<Object> savePhoto(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") String id,
                                            @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.savePhoto(auth, id, nn(body))); }

    /** app: ?a=<token> because an <img> tag can't send the X-Auth header */
    @GetMapping("/items/{id}/photo")
    public ResponseEntity<?> appPhoto(@RequestParam(value = "a", required = false) String auth, @PathVariable("id") String id) {
        try { return image(svc.appPhoto(auth, id)); } catch (ApiException e) { return error(e.getStatus(), e.getMessage()); }
    }

    // ---- online shop (public: no login, by the company's shop code)
    @GetMapping("/shop/{code}")
    public ResponseEntity<Object> shop(@PathVariable("code") String code) { return run(() -> svc.shop(code)); }

    @GetMapping("/shop/{code}/photo/{id}")
    public ResponseEntity<?> shopPhoto(@PathVariable("code") String code, @PathVariable("id") String id) {
        try { return image(svc.photo(code, id, true)); } catch (ApiException e) { return error(e.getStatus(), e.getMessage()); }
    }

    @PostMapping("/shop/{code}/orders")
    public ResponseEntity<Object> shopOrder(@PathVariable("code") String code, @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.shopOrder(code, nn(body))); }

    @GetMapping("/shop/{code}/orders/{token}")
    public ResponseEntity<Object> shopOrderStatus(@PathVariable("code") String code, @PathVariable("token") String token) { return run(() -> svc.shopOrderStatus(code, token)); }

    // ---- online orders (staff)
    @GetMapping("/orders")
    public ResponseEntity<Object> orders(@RequestHeader(value = AUTH, required = false) String auth,
                                         @RequestParam(value = "status", required = false) String status) { return run(() -> svc.orders(auth, status)); }

    // ---- money reports: payables (suppliers / POs) and receivables (retailer invoices)
    @PostMapping("/po/{id}/pay")
    public ResponseEntity<Object> poPayment(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                            @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.poPayment(auth, id, nn(body))); }

    @GetMapping("/payables")
    public ResponseEntity<Object> payables(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.payables(auth, branch)); }

    @GetMapping("/receivables")
    public ResponseEntity<Object> receivables(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.receivables(auth)); }

    // ---- wholesale: retailers, sales invoices, payments
    @GetMapping("/parties")
    public ResponseEntity<Object> parties(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.parties(auth)); }

    @PostMapping("/parties")
    public ResponseEntity<Object> saveParty(@RequestHeader(value = AUTH, required = false) String auth,
                                            @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveParty(auth, nn(body))); }

    @GetMapping("/parties/{id}")
    public ResponseEntity<Object> party(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") String id) { return run(() -> svc.party(auth, id)); }

    @PostMapping("/parties/{id}/payments")
    public ResponseEntity<Object> partyPayment(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") String id,
                                               @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.partyPayment(auth, id, nn(body))); }

    @PostMapping("/sales-invoices")
    public ResponseEntity<Object> createInvoice(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.createInvoice(auth, nn(body))); }

    /** sales / stock user takes an order for a customer (phone / WhatsApp / counter) */
    @PostMapping("/orders")
    public ResponseEntity<Object> takeOrder(@RequestHeader(value = AUTH, required = false) String auth,
                                            @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.takeOrder(auth, nn(body))); }

    @PostMapping("/orders/{id}")
    public ResponseEntity<Object> orderUpdate(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                              @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.orderUpdate(auth, id, nn(body))); }

    @PostMapping("/shop-settings")
    public ResponseEntity<Object> shopSettings(@RequestHeader(value = AUTH, required = false) String auth,
                                               @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.shopSettings(auth, nn(body))); }

    private static ResponseEntity<?> image(Object[] p) {
        if (p == null) return ResponseEntity.status(404).build();
        return ResponseEntity.ok().header("Content-Type", (String) p[0]).header("Cache-Control", "public, max-age=86400").body((byte[]) p[1]);
    }

    // ---- stock transfers (warehouse -> shops)
    @GetMapping("/transfers")
    public ResponseEntity<Object> transfers(@RequestHeader(value = AUTH, required = false) String auth,
                                            @RequestParam(value = "status", required = false) String status) { return run(() -> svc.transfers(auth, status)); }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<Object> transferGet(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id) { return run(() -> svc.transferGet(auth, id)); }

    @PostMapping("/transfers")
    public ResponseEntity<Object> sendTransfer(@RequestHeader(value = AUTH, required = false) String auth,
                                               @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.sendTransfer(auth, nn(body))); }

    @PostMapping("/transfers/{id}/receive")
    public ResponseEntity<Object> receiveTransfer(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                                  @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.receiveTransfer(auth, id, nn(body))); }

    @PostMapping("/transfers/{id}/cancel")
    public ResponseEntity<Object> cancelTransfer(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id) { return run(() -> svc.cancelTransfer(auth, id)); }

    // ---- purchase orders (stock users; owner view only)
    @GetMapping("/suppliers")
    public ResponseEntity<Object> suppliers(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.suppliers(auth)); }

    @PostMapping("/suppliers")
    public ResponseEntity<Object> saveSupplier(@RequestHeader(value = AUTH, required = false) String auth,
                                               @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveSupplier(auth, nn(body))); }

    @PostMapping("/suppliers/{id}/delete")
    public ResponseEntity<Object> deleteSupplier(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") String id) { return run(() -> svc.deleteSupplier(auth, id)); }

    @GetMapping("/po")
    public ResponseEntity<Object> poList(@RequestHeader(value = AUTH, required = false) String auth,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.poList(auth, status, branch)); }

    @GetMapping("/po/{id}")
    public ResponseEntity<Object> poGet(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id) { return run(() -> svc.poGet(auth, id)); }

    @PostMapping("/po")
    public ResponseEntity<Object> savePo(@RequestHeader(value = AUTH, required = false) String auth,
                                         @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.savePo(auth, nn(body))); }

    @PostMapping("/po/{id}/receive")
    public ResponseEntity<Object> receivePo(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                            @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.receivePo(auth, id, nn(body))); }

    @PostMapping("/po/{id}/update")
    public ResponseEntity<Object> updatePo(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") long id,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.updatePo(auth, id, nn(body))); }

    /** Save the cart as pending for a mobile number (not a bill yet) */
    @PostMapping("/pending")
    public ResponseEntity<Object> savePending(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.savePending(auth, nn(body))); }

    /** ?phone= today's pending of that number; no phone = open pending of the branch */
    @GetMapping("/pending")
    public ResponseEntity<Object> pending(@RequestHeader(value = AUTH, required = false) String auth,
                                          @RequestParam(value = "phone", required = false) String phone) { return run(() -> svc.pendingList(auth, phone)); }

    @DeleteMapping("/pending/{id}")
    public ResponseEntity<Object> deletePending(@RequestHeader(value = AUTH, required = false) String auth,
                                                @PathVariable("id") long id) { return run(() -> svc.deletePending(auth, id)); }

    /** ரத்து பில் (cancel bill) - a separate document made like a bill; the original bill stays */
    @PostMapping("/cancel-bills")
    public ResponseEntity<Object> addCancelBill(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.addCancelBill(auth, nn(body))); }

    /** bills of a mobile number (this month + last 2 months) with what is left to cancel on each line */
    @GetMapping("/bills/for-cancel")
    public ResponseEntity<Object> billForCancel(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestParam(value = "no", required = false) String no) { return run(() -> svc.billForCancel(auth, no)); }

    @GetMapping("/bills/by-phone")
    public ResponseEntity<Object> billsByPhone(@RequestHeader(value = AUTH, required = false) String auth,
                                               @RequestParam(value = "phone", required = false) String phone,
                                               @RequestParam(value = "months", required = false) String months) { return run(() -> svc.billsByPhone(auth, phone, months)); }

    /** unused cancel bills of a mobile number - exchange credit for a new bill */
    @GetMapping("/cancel-bills/open")
    public ResponseEntity<Object> exchangeOpen(@RequestHeader(value = AUTH, required = false) String auth,
                                               @RequestParam(value = "phone", required = false) String phone) { return run(() -> svc.exchangeOpen(auth, phone)); }

    @GetMapping("/cancel-bills")
    public ResponseEntity<Object> cancelBills(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.cancelBills(auth, branch)); }

    /** Sales report / statistics of the session company. ?date=yyyy-MM-dd (default today) */
    @GetMapping("/report")
    public ResponseEntity<Object> report(@RequestHeader(value = AUTH, required = false) String auth,
                                         @RequestParam(value = "date", required = false) String date,
                                         @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.report(auth, date, branch)); }

    /** Stock of a branch (stock users: own branch, admin: ?branch=) */
    @GetMapping("/stock")
    public ResponseEntity<Object> stock(@RequestHeader(value = AUTH, required = false) String auth,
                                        @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.stock(auth, branch)); }

    /** Stock report: owner / admin all branches (or ?branch=), stock users own branch */
    @GetMapping("/stock/report")
    public ResponseEntity<Object> stockReport(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestParam(value = "branch", required = false) String branch) { return run(() -> svc.stockReport(auth, branch)); }

    /** {item, size, qty} = set, {item, size, add} = add / subtract; negative allowed */
    @PutMapping("/stock")
    public ResponseEntity<Object> setStock(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.setStock(auth, nn(body))); }

    // ---- admin
    /** Today / selected day / month sales of all companies. ?date=yyyy-MM-dd (default today) */
    @GetMapping("/admin/sales")
    public ResponseEntity<Object> adminSales(@RequestHeader(value = AUTH, required = false) String auth,
                                             @RequestParam(value = "date", required = false) String date) { return run(() -> svc.adminSales(auth, date)); }

    @PostMapping("/admin/branch")
    public ResponseEntity<Object> saveBranch(@RequestHeader(value = AUTH, required = false) String auth,
                                             @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveBranch(auth, nn(body))); }

    @PostMapping("/admin/paymode")
    public ResponseEntity<Object> savePayMode(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.savePayMode(auth, nn(body))); }

    @DeleteMapping("/admin/branch/{companyId}/{id}")
    public ResponseEntity<Object> deleteBranch(@RequestHeader(value = AUTH, required = false) String auth,
                                               @PathVariable("companyId") String companyId, @PathVariable("id") String id) { return run(() -> svc.deleteBranch(auth, companyId, id)); }

    @GetMapping("/admin/overview")
    public ResponseEntity<Object> overview(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.overview(auth)); }

    @PostMapping("/admin/company")
    public ResponseEntity<Object> saveCompany(@RequestHeader(value = AUTH, required = false) String auth,
                                              @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveCompany(auth, nn(body))); }

    @DeleteMapping("/admin/company/{id}")
    public ResponseEntity<Object> deleteCompany(@RequestHeader(value = AUTH, required = false) String auth,
                                                @PathVariable("id") String id) { return run(() -> svc.deleteCompany(auth, id)); }

    /** ready-made master data sets (e.g. Dry Fruit Palace) */
    @GetMapping("/admin/templates")
    public ResponseEntity<Object> templates(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> svc.templates(auth)); }

    @PostMapping("/admin/company/{id}/template")
    public ResponseEntity<Object> loadTemplate(@RequestHeader(value = AUTH, required = false) String auth, @PathVariable("id") String id,
                                               @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.loadTemplate(auth, id, nn(body))); }

    /** import groups / items / sizes (and stock) from CSV text into the company of the session */
    @PostMapping("/catalog/import")
    public ResponseEntity<Object> importCatalog(@RequestHeader(value = AUTH, required = false) String auth,
                                                @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.importCatalog(auth, nn(body))); }

    @PostMapping("/admin/user")
    public ResponseEntity<Object> saveUser(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.saveUser(auth, nn(body))); }

    @DeleteMapping("/admin/user/{id}")
    public ResponseEntity<Object> deleteUser(@RequestHeader(value = AUTH, required = false) String auth,
                                             @PathVariable("id") String id) { return run(() -> svc.deleteUser(auth, id)); }

    // ---------------- post-deploy tests + rollback (admin)
    @GetMapping("/admin/tests")
    public ResponseEntity<Object> tests(@RequestHeader(value = AUTH, required = false) String auth,
                                        @RequestParam(value = "run", required = false) Long run) { return run(() -> { svc.adminName(auth); return tests.overview(run); }); }

    @GetMapping("/admin/tests/alert")
    public ResponseEntity<Object> testAlert(@RequestHeader(value = AUTH, required = false) String auth) { return run(() -> { svc.adminName(auth); return tests.alert(); }); }

    @PostMapping("/admin/tests/run")
    public ResponseEntity<Object> testRun(@RequestHeader(value = AUTH, required = false) String auth) {
        return run(() -> { String by = svc.adminName(auth); if (!tests.startAsync("admin: " + by)) throw new ApiException(409, "Test ஏற்கனவே இயங்குகிறது"); return tests.alert(); });
    }

    @PostMapping("/admin/rollback")
    public ResponseEntity<Object> rollback(@RequestHeader(value = AUTH, required = false) String auth,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return run(() -> { String by = svc.adminName(auth); return tests.requestRollback(by, body != null && Boolean.TRUE.equals(body.get("withDb"))); });
    }

    @PostMapping("/admin/open")
    public ResponseEntity<Object> open(@RequestHeader(value = AUTH, required = false) String auth,
                                       @RequestBody(required = false) Map<String, Object> body) { return run(() -> svc.openCompany(auth, nn(body))); }

    // ---- helpers
    private static Map<String, Object> nn(Map<String, Object> body) { return body == null ? new LinkedHashMap<>() : body; }

    private static ResponseEntity<Object> run(Supplier<Object> work) {
        try {
            return ResponseEntity.status(200).header("Cache-Control", "no-store").body(work.get());
        } catch (ApiException e) {
            return error(e.getStatus(), e.getMessage());
        } catch (DbException e) {
            if (e.isConstraintViolation()) return error(409, "தரவு முரண்பாடு — மீண்டும் முயற்சிக்கவும் (conflict)");
            log.error("Database error", e);
            return error(500, "Database error");
        } catch (RuntimeException e) {
            log.error("Server error", e);
            return error(500, "Server error");
        }
    }

    private static ResponseEntity<Object> error(int status, String msg) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == 401) b.header("WWW-Authenticate", "Bearer");
        return b.body(Collections.singletonMap("error", msg));
    }
}
