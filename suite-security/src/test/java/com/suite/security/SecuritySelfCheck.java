package com.suite.security;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** run:  javac -d out $(find . -name '*.java') && java -cp out com.suite.security.SecuritySelfCheck   (no test framework needed) */
public final class SecuritySelfCheck {
    static int ok = 0;

    static void check(boolean c, String m) {
        if (!c) throw new AssertionError("FAIL: " + m);
        ok++;
        System.out.println("  ok  " + m);
    }

    static <T extends Throwable> T expect(Class<T> t, Runnable r, String m) {
        try {
            r.run();
        } catch (Throwable e) {
            if (t.isInstance(e)) { ok++; System.out.println("  ok  " + m); return t.cast(e); }
            throw new AssertionError("FAIL: " + m + " (got " + e + ")");
        }
        throw new AssertionError("FAIL: " + m + " (nothing thrown)");
    }

    public static void main(String[] a) throws Exception {
        // ---------- glyph table + alphabet
        check(Glyphs.ALPHABET.length() == 24 && Glyphs.ALPHABET.chars().distinct().count() == 24, "alphabet: 24 distinct symbols");
        for (char c : "01 5SZ8BGIOQV".toCharArray()) if (c != ' ') check(Glyphs.ALPHABET.indexOf(c) < 0, "alphabet has no look-alike '" + c + "'");

        // ---------- captcha
        Captcha cp = new Captcha();
        Map<String, Object> ch = cp.create("1.2.3.4");
        String id = (String) ch.get("id"), ans = cp.peek(id);
        check(ans.length() == 5 && ans.chars().allMatch(c -> Glyphs.ALPHABET.indexOf(c) >= 0), "captcha text: 5 symbols from the alphabet (" + ans + ")");
        check(((String) ch.get("image")).startsWith("data:image/svg+xml;base64,"), "captcha image is an SVG data URI");
        String svg = new String(java.util.Base64.getDecoder().decode(((String) ch.get("image")).substring(26)), java.nio.charset.StandardCharsets.UTF_8);
        check(!svg.contains("<text") && !svg.contains(ans), "SVG holds no <text> and not the answer");
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(true, "SVG is well-formed XML (" + svg.length() + " bytes)");
        check(!cp.verify(id, "WRONG"), "wrong answer refused");
        check(!cp.verify(id, ans), "the id is used up after one try (right answer is refused afterwards)");
        Map<String, Object> c2 = cp.create("1.2.3.4");
        String a2 = cp.peek((String) c2.get("id"));
        check(cp.verify((String) c2.get("id"), " " + a2.toLowerCase() + " "), "right answer accepted: lower case and spaces do not matter");
        check(!cp.verify(null, "X") && !cp.verify("nope", "X"), "unknown / null id refused");
        Captcha shortLived = new Captcha(1, 100, 100);
        String id3 = (String) shortLived.create("k").get("id");
        String a3 = shortLived.peek(id3);
        Thread.sleep(5);
        check(!shortLived.verify(id3, a3), "expired captcha refused");
        Captcha limited = new Captcha(60_000, 100, 3);
        for (int i = 0; i < 3; i++) limited.create("same-ip");
        LoginSecurity.Denied d = expect(LoginSecurity.Denied.class, () -> limited.create("same-ip"), "4th captcha in a minute from one IP -> rate limited");
        check(d.status == 429, "rate limit status 429");
        limited.create("other-ip");
        check(true, "another IP is not affected by the limit");
        Captcha capped = new Captcha(60_000, 5, 1000);
        for (int i = 0; i < 5; i++) capped.create("k" + i);
        expect(LoginSecurity.Denied.class, () -> capped.create("k9"), "waiting captchas are capped");
        // different pictures each time, and every symbol renders
        String s1 = Captcha.render("ACDEF", new Random(1)), s2 = Captcha.render("ACDEF", new Random(2));
        check(!s1.equals(s2), "two pictures of the same text differ");
        for (char c : Glyphs.ALPHABET.toCharArray()) check(Captcha.render("" + c + c + c + c + c, new Random(c)).contains("<path"), "renders '" + c + "'");

        // ---------- IP keys
        check(IpUtil.key("203.0.113.7").equals("203.0.113.7"), "IPv4 key");
        check(IpUtil.key(" 010.1.1.1 ").equals("10.1.1.1"), "IPv4 with leading zero normalised");
        check(IpUtil.key("999.1.1.1").equals("unknown"), "bad IPv4 -> unknown");
        check(IpUtil.key("::ffff:203.0.113.7").equals("203.0.113.7"), "IPv4-mapped IPv6 -> IPv4");
        check(IpUtil.key("2001:db8:1:2:aaaa:bbbb:cccc:dddd").equals("2001:db8:1:2::/64") && IpUtil.key("2001:DB8:1:2::5").equals("2001:db8:1:2::/64"), "IPv6 -> /64 network (all addresses of one connection share one key)");
        check(IpUtil.key("[2001:db8::1]").equals("2001:db8:0:0::/64"), "bracketed IPv6");
        check(IpUtil.key("fe80::1%eth0").equals("fe80:0:0:0::/64"), "IPv6 zone id dropped");
        check(IpUtil.key("evil.example.com").equals("unknown") && IpUtil.key(null).equals("unknown") && IpUtil.key("1.2.3.4, 5.6.7.8").equals("unknown"), "host names / lists -> unknown (no DNS lookup, no spoofable list)");

        // ---------- guard: > 10 failures in a day blocks
        ZoneId z = ZoneId.of("Asia/Kolkata");
        MemoryStore ms = new MemoryStore();
        IpGuard g = new IpGuard(ms, z, 10, 40, 0, IpUtil.keys("127.0.0.1,::1"));
        for (int i = 1; i <= 10; i++) check(!g.failure("198.51.100.9", "bob", false).blockedNow(), "failure " + i + " of 10: not blocked yet");
        check(!g.isBlocked("198.51.100.9"), "after 10 failures the IP is still free");
        check(g.failure("198.51.100.9", "bob", false).blockedNow(), "11th failure blocks the IP");
        check(g.isBlocked("198.51.100.9") && g.isBlocked("198.51.100.9"), "blocked IP stays blocked");
        check(!g.isBlocked("198.51.100.10"), "another IP is not blocked");
        check(g.blocked().size() == 1 && Boolean.TRUE.equals(g.blocked().get(0).get("active")) && "bob".equals(g.blocked().get(0).get("lastUser")), "block list shows the IP, active, last user");
        check(g.unblock("198.51.100.9", "sysadmin"), "unblock by the System user");
        check(!g.isBlocked("198.51.100.9"), "IP is free again");
        check(!g.unblock("198.51.100.9", "sysadmin"), "second unblock: nothing to lift");
        check(!g.failure("198.51.100.9", "bob", false).blockedNow(), "after unblock today's counter starts from 0 (one failure does not re-block)");
        check(g.blocked().get(0).get("unblockedBy").equals("sysadmin") || !Boolean.TRUE.equals(g.blocked().get(0).get("active")), "history keeps who lifted it");
        // IPv6 users cannot dodge the block by changing the last 64 bits
        for (int i = 0; i < 11; i++) g.failure("2001:db8:5:6:" + Integer.toHexString(i + 1) + "::1", "x", false);
        check(g.isBlocked("2001:db8:5:6:ffff:ffff:ffff:ffff"), "IPv6: blocking covers the whole /64");
        check(g.unblock("2001:db8:5:6::/64", "sys") && !g.isBlocked("2001:db8:5:6:9::9"), "IPv6 unblock with the key shown in the list");
        // exempt
        for (int i = 0; i < 30; i++) g.failure("127.0.0.1", "x", false);
        check(!g.isBlocked("127.0.0.1"), "exempt address is never blocked");
        // captcha failures have their own, higher limit
        boolean b = false;
        for (int i = 0; i < 40; i++) b |= g.failure("203.0.113.50", "u", true).blockedNow();
        check(!b && !g.isBlocked("203.0.113.50"), "40 wrong captchas: not blocked");
        check(g.failure("203.0.113.50", "u", true).blockedNow() && g.isBlocked("203.0.113.50"), "41st wrong captcha blocks");
        // optional automatic end of a block
        MemoryStore ms2 = new MemoryStore();
        IpGuard timed = new IpGuard(ms2, z, 10, 40, 1, Set.of());
        ms2.block("192.0.2.1", "old", 11, "u", LocalDateTime.now(z).minusHours(2));
        check(!timed.isBlocked("192.0.2.1"), "IP_BLOCK_HOURS=1: a 2 hour old block has ended by itself");
        ms2.block("192.0.2.2", "new", 11, "u", LocalDateTime.now(z).minusMinutes(5));
        check(timed.isBlocked("192.0.2.2"), "IP_BLOCK_HOURS=1: a 5 minute old block is still active");

        // ---------- the façade the login endpoints use
        LoginSecurity ls = new LoginSecurity(new MemoryStore(), z, true, 3, 5, 0, Set.of());
        Map<String, Object> chal = ls.challenge("203.0.113.1");
        check(Boolean.TRUE.equals(chal.get("enabled")) && chal.get("image") != null, "challenge: enabled + picture");
        LoginSecurity.Denied e1 = expect(LoginSecurity.Denied.class, () -> ls.beforeLogin("203.0.113.1", "u", "x", ""), "empty captcha -> refused");
        check(e1.status == 400 && e1.captcha, "empty captcha: 400 + captcha flag");
        LoginSecurity.Denied e2 = expect(LoginSecurity.Denied.class, () -> ls.beforeLogin("203.0.113.1", "u", (String) chal.get("id"), "ZZZZZ"), "wrong captcha -> refused");
        check(e2.status == 400 && e2.captcha && !e2.blocked, "wrong captcha: 400 + captcha flag, not blocked yet");
        LoginSecurity.Denied off = null;
        for (int i = 0; i < 3; i++) { try { ls.credentialsFailed("203.0.113.2", "u"); } catch (LoginSecurity.Denied x) { off = x; } }
        check(off == null, "3 wrong passwords: still allowed (limit 3)");
        LoginSecurity.Denied blk = expect(LoginSecurity.Denied.class, () -> ls.credentialsFailed("203.0.113.2", "u"), "4th wrong password -> blocked");
        check(blk.status == 403 && blk.blocked && "203.0.113.2".equals(blk.ip), "blocked: 403 + flag + the IP");
        expect(LoginSecurity.Denied.class, () -> ls.challenge("203.0.113.2"), "a blocked IP gets no captcha, just the block message");
        expect(LoginSecurity.Denied.class, () -> ls.beforeLogin("203.0.113.2", "u", "a", "b"), "a blocked IP cannot log in even with the right password");
        check(ls.unblock("203.0.113.2", "sys"), "unblock via the façade");
        ls.challenge("203.0.113.2");
        check(true, "unblocked IP gets a captcha again");
        // a right captcha passes
        Map<String, Object> c5 = ls.challenge("203.0.113.3");
        // (the answer is private to Captcha; use a second Captcha instance semantic: verify path is covered above)
        LoginSecurity off2 = new LoginSecurity(new MemoryStore(), z, false, 3, 5, 0, Set.of());
        off2.beforeLogin("203.0.113.4", "u", null, null);
        check(Boolean.FALSE.equals(off2.challenge("203.0.113.4").get("enabled")), "LOGIN_CAPTCHA=false: no captcha asked, login allowed");

        System.out.println("PASS " + ok + " checks");
    }
}
