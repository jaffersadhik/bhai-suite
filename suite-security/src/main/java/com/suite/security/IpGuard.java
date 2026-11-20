package com.suite.security;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts failed logins per IP and per day and blocks an IP that fails more than {@code maxFails} times in one day.
 * A block stays until somebody with the right (the System user) lifts it - or, if {@code blockHours} &gt; 0, until that many hours passed.
 * A successful login does NOT reset the counter (otherwise an attacker could mix in a valid login of his own to start again).
 */
public final class IpGuard {
    /** what {@link #failure} found out */
    public record Result(int fails, int captchaFails, boolean blockedNow) {
    }

    private final Store store;
    private final ZoneId zone;
    private final int maxFails;
    private final int maxCaptchaFails;
    private final int blockHours;
    private final Set<String> exempt;
    private volatile LocalDate lastPurge;

    public IpGuard(Store store, ZoneId zone, int maxFails, int maxCaptchaFails, int blockHours, Set<String> exempt) {
        this.store = store;
        this.zone = zone;
        this.maxFails = maxFails;
        this.maxCaptchaFails = maxCaptchaFails;
        this.blockHours = blockHours;
        this.exempt = exempt;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    public boolean isBlocked(String rawIp) {
        String k = IpUtil.key(rawIp);
        if (exempt.contains(k)) return false;
        Store.Block b = store.findBlock(k);
        if (b == null) return false;
        if (blockHours > 0 && b.blockedAt().plusHours(blockHours).isBefore(now())) {
            store.unblock(k, "auto (" + blockHours + " h)", now());
            store.clearDay(k, now().toLocalDate());
            return false;
        }
        return true;
    }

    /** one failed login (wrong user / password) or, with {@code captcha}, one wrong captcha answer */
    public Result failure(String rawIp, String user, boolean captcha) {
        String k = IpUtil.key(rawIp);
        if (exempt.contains(k)) return new Result(0, 0, false);
        LocalDateTime t = now();
        LocalDate today = t.toLocalDate();
        if (lastPurge == null || lastPurge.isBefore(today)) {
            lastPurge = today;
            try {
                store.purgeBefore(today.minusDays(7));
            } catch (RuntimeException ignore) {
                /* housekeeping only */
            }
        }
        Store.Counts c = store.bump(k, today, captcha, t);
        boolean over = captcha ? c.captchaFails() > maxCaptchaFails : c.fails() > maxFails;
        boolean already = store.findBlock(k) != null;
        if (over && !already) {
            store.block(k, captcha ? "wrong captcha " + c.captchaFails() + " times today" : "failed login " + c.fails() + " times today", c.fails() + c.captchaFails(), user, t);
            return new Result(c.fails(), c.captchaFails(), true);
        }
        return new Result(c.fails(), c.captchaFails(), false);
    }

    /** the blocks as plain maps for the admin screen */
    public List<Map<String, Object>> blocked() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Store.Block b : store.blocks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", b.ip());
            m.put("blockedAt", String.valueOf(b.blockedAt()).replace('T', ' '));
            m.put("reason", b.reason());
            m.put("fails", b.fails());
            m.put("lastUser", b.lastUser());
            m.put("active", b.active());
            m.put("unblockedAt", b.unblockedAt() == null ? null : String.valueOf(b.unblockedAt()).replace('T', ' '));
            m.put("unblockedBy", b.unblockedBy());
            out.add(m);
        }
        return out;
    }

    /** lifts the block; {@code ip} is the key shown in the list (or any spelling of the address) */
    public boolean unblock(String ip, String by) {
        if (ip == null || ip.isBlank()) return false;
        String raw = ip.trim();
        LocalDateTime t = now();
        boolean ok = store.unblock(raw, by, t);
        String k = raw;
        if (!ok) {
            k = IpUtil.key(raw);
            ok = store.unblock(k, by, t);
        }
        if (ok) store.clearDay(k, t.toLocalDate());
        return ok;
    }
}
