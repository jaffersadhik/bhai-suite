package com.suite.security;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * What a login endpoint needs, in one object (used by MBill and MSchool):
 *
 *   GET  /api/captcha     -> {@link #challenge}
 *   POST /api/login       -> {@link #beforeLogin} (blocked IP? captcha right?)  ... check the password ...  {@link #credentialsFailed} on a wrong one
 *   GET  /api/myip        -> the footer "Your IP" line
 *   System user screen    -> {@link #blocked} / {@link #unblock}
 *
 * Settings (environment variables, the same names for both applications):
 *   LOGIN_CAPTCHA=true|false        captcha at login (default true)
 *   LOGIN_MAX_FAILS=10              failed logins per IP and day; the next failure blocks the IP
 *   LOGIN_MAX_CAPTCHA_FAILS=40      wrong captcha answers per IP and day before the IP is blocked
 *   IP_BLOCK_HOURS=0                0 = a block stays until the System user lifts it; N = it also ends by itself after N hours
 *   IP_BLOCK_EXEMPT=127.0.0.1,::1   addresses that are never blocked
 */
public final class LoginSecurity {
    /** a login that must be refused. {@code status} is the HTTP status, {@code message} is shown to the user. */
    public static final class Denied extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final boolean blocked;
        public final boolean captcha;
        public final String ip;

        public Denied(int status, String message, boolean blocked, boolean captcha, String ip) {
            super(message);
            this.status = status;
            this.blocked = blocked;
            this.captcha = captcha;
            this.ip = ip;
        }
    }

    private final boolean captchaOn;
    private final Captcha captcha = new Captcha();
    private final IpGuard guard;

    public LoginSecurity(Store store, ZoneId zone, boolean captchaOn, int maxFails, int maxCaptchaFails, int blockHours, java.util.Set<String> exempt) {
        this.captchaOn = captchaOn;
        this.guard = new IpGuard(store, zone, maxFails, maxCaptchaFails, blockHours, exempt);
    }

    /** reads the settings from the environment (or -D system properties) and creates the two tables in the application's database */
    public static LoginSecurity fromEnvironment(DataSource ds, ZoneId zone) {
        Store store = new JdbcStore(ds);
        store.init();
        return new LoginSecurity(store, zone, !"false".equalsIgnoreCase(env("LOGIN_CAPTCHA", "true")), num(env("LOGIN_MAX_FAILS", "10"), 10),
                num(env("LOGIN_MAX_CAPTCHA_FAILS", "40"), 40), num(env("IP_BLOCK_HOURS", "0"), 0), IpUtil.keys(env("IP_BLOCK_EXEMPT", "127.0.0.1,::1")));
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) v = System.getProperty(k);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int num(String s, int def) {
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean captchaEnabled() {
        return captchaOn;
    }

    private Denied blockedDenied(String ip) {
        String k = IpUtil.key(ip);
        return new Denied(403, "பல முறை தவறான login முயற்சி நடந்ததால் இந்த IP " + k + " தடை செய்யப்பட்டுள்ளது. System user-ஐ தொடர்பு கொள்ளவும்", true, false, k);
    }

    /** GET /api/captcha: {enabled, id, image, length}; a blocked IP is told so right away */
    public Map<String, Object> challenge(String ip) {
        if (guard.isBlocked(ip)) throw blockedDenied(ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", captchaOn);
        if (captchaOn) out.putAll(captcha.create(IpUtil.key(ip)));
        return out;
    }

    /** first step of a login: refuses a blocked IP and a wrong / missing captcha answer */
    public void beforeLogin(String ip, String user, String captchaId, String answer) {
        if (guard.isBlocked(ip)) throw blockedDenied(ip);
        if (!captchaOn) return;
        if (answer == null || answer.isBlank() || captchaId == null || captchaId.isBlank()) {
            throw new Denied(400, "படத்தில் உள்ள Captcha எழுத்துகளை உள்ளிடவும்", false, true, null);
        }
        if (!captcha.verify(captchaId, answer)) {
            if (guard.failure(ip, user, true).blockedNow()) throw blockedDenied(ip);
            throw new Denied(400, "Captcha தவறு. புதிய படத்தில் உள்ள எழுத்துகளை மீண்டும் உள்ளிடவும்", false, true, null);
        }
    }

    /** call when the user name / password was wrong; throws {@link Denied} (blocked) when this was the failure that crossed the limit */
    public void credentialsFailed(String ip, String user) {
        if (guard.failure(ip, user, false).blockedNow()) throw blockedDenied(ip);
    }

    public List<Map<String, Object>> blocked() {
        return guard.blocked();
    }

    public boolean unblock(String ip, String by) {
        return guard.unblock(ip, by);
    }
}
