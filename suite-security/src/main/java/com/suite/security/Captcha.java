package com.suite.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alphanumeric image captcha for the login pages.
 *
 *  - 5 symbols from a 24-symbol alphabet without look-alikes; case does not matter (easy on a phone keyboard)
 *  - the picture is an SVG made of PATHS (dots of a 5x7 font, each letter rotated / shifted, plus noise lines and dots) - the answer is
 *    never present as text in the picture, so it cannot be read out of the markup
 *  - the answer stays on the server: {@link #create} returns an opaque id + the picture, {@link #verify} accepts each id ONCE and only
 *    for a few minutes
 *  - creation is rate limited per IP and the number of waiting captchas is capped (no memory flooding)
 *
 * Honest limit: a captcha stops scripts, not determined people or paid solving services - that is why failed logins are also counted per IP
 * ({@link IpGuard}).
 */
public final class Captcha {
    public static final int LENGTH = 5;
    private static final SecureRandom RND = new SecureRandom();
    private static final String[] COLORS = {"#09442f", "#1b3a6b", "#6b1f1f"};

    private static final class Entry {
        final String answer;
        final long expires;

        Entry(String answer, long expires) {
            this.answer = answer;
            this.expires = expires;
        }
    }

    private final long ttlMs;
    private final int maxWaiting;
    private final int perIpPerMinute;
    private final ConcurrentHashMap<String, Entry> waiting = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> rate = new ConcurrentHashMap<>();   // ip -> {windowStartMs, count}

    public Captcha() {
        this(3 * 60_000L, 20_000, 40);
    }

    public Captcha(long ttlMs, int maxWaiting, int perIpPerMinute) {
        this.ttlMs = ttlMs;
        this.maxWaiting = maxWaiting;
        this.perIpPerMinute = perIpPerMinute;
    }

    /** a new challenge for the given (already normalised) client key; {id, image: data URI, length} */
    public Map<String, Object> create(String clientKey) {
        long now = System.currentTimeMillis();
        throttle(clientKey, now);
        if (waiting.size() >= maxWaiting) purge(now);
        if (waiting.size() >= maxWaiting) throw new LoginSecurity.Denied(429, "இப்போது பலர் login செய்கிறார்கள். சில விநாடி கழித்து முயற்சிக்கவும்", false, false, null);
        StringBuilder t = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) t.append(Glyphs.ALPHABET.charAt(RND.nextInt(Glyphs.ALPHABET.length())));
        String id = HexFormat.of().formatHex(randomBytes(16));
        waiting.put(id, new Entry(t.toString(), now + ttlMs));
        String svg = render(t.toString(), RND);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("image", "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8)));
        out.put("length", LENGTH);
        return out;
    }

    /** true when {@code answer} is right for {@code id}. The id is used up either way - one try per picture. */
    public boolean verify(String id, String answer) {
        if (id == null || answer == null) return false;
        Entry e = waiting.remove(id.trim());
        if (e == null || e.expires < System.currentTimeMillis()) return false;
        String a = answer.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), e.answer.getBytes(StandardCharsets.UTF_8));
    }

    /** test hook: the expected answer of a waiting captcha */
    String peek(String id) {
        Entry e = waiting.get(id);
        return e == null ? null : e.answer;
    }

    int waitingCount() {
        return waiting.size();
    }

    private void throttle(String key, long now) {
        if (rate.size() > 50_000) rate.clear();
        long[] w = rate.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (w) {
            if (now - w[0] > 60_000) {
                w[0] = now;
                w[1] = 0;
            }
            if (++w[1] > perIpPerMinute) throw new LoginSecurity.Denied(429, "மிக வேகமாக captcha கேட்கப்படுகிறது. ஒரு நிமிடம் கழித்து முயற்சிக்கவும்", false, false, null);
        }
    }

    private void purge(long now) {
        waiting.entrySet().removeIf(en -> en.getValue().expires < now);
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ picture

    private static double r(Random rnd, double lo, double hi) {
        return lo + rnd.nextDouble() * (hi - lo);
    }

    private static String f(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** the SVG text of a captcha picture (200 x 64) */
    public static String render(String text, Random rnd) {
        final int w = 200, h = 64;
        StringBuilder s = new StringBuilder(4096);
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" width=\"").append(w).append("\" height=\"").append(h).append("\">");
        s.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" rx=\"10\" fill=\"#f4f8f5\"/>");
        // background noise: curves and dots in the same colours as the letters
        for (int i = 0; i < 3; i++) {
            String c = COLORS[rnd.nextInt(COLORS.length)];
            s.append("<path d=\"M").append(f(r(rnd, 0, 30))).append(' ').append(f(r(rnd, 8, 56)))
                    .append(" C").append(f(r(rnd, 40, 80))).append(' ').append(f(r(rnd, 0, 64)))
                    .append(' ').append(f(r(rnd, 110, 150))).append(' ').append(f(r(rnd, 0, 64)))
                    .append(' ').append(f(r(rnd, 170, 200))).append(' ').append(f(r(rnd, 8, 56)))
                    .append("\" fill=\"none\" stroke=\"").append(c).append("\" stroke-width=\"1.1\" opacity=\".3\"/>");
        }
        for (int i = 0; i < 18; i++)
            s.append("<circle cx=\"").append(f(r(rnd, 3, w - 3))).append("\" cy=\"").append(f(r(rnd, 3, h - 3))).append("\" r=\"").append(f(r(rnd, .7, 1.4)))
                    .append("\" fill=\"").append(COLORS[rnd.nextInt(COLORS.length)]).append("\" opacity=\".4\"/>");
        // letters
        double slot = (w - 16) / (double) text.length();
        for (int i = 0; i < text.length(); i++) {
            String[] rows = Glyphs.of(text.charAt(i));
            double cell = r(rnd, 5.6, 6.1);
            double gw = 5 * cell, gh = 7 * cell;
            double tx = 8 + i * slot + (slot - gw) / 2 + r(rnd, -2, 2);
            double ty = (h - gh) / 2 + r(rnd, -3, 3);
            double rot = r(rnd, -9, 9);
            String color = COLORS[rnd.nextInt(COLORS.length)];
            s.append("<path transform=\"translate(").append(f(tx)).append(' ').append(f(ty)).append(") rotate(").append(f(rot)).append(' ').append(f(gw / 2)).append(' ').append(f(gh / 2))
                    .append(")\" fill=\"").append(color).append("\" d=\"");
            for (int y = 0; y < 7; y++)
                for (int x = 0; x < 5; x++)
                    if (rows[y].charAt(x) == '#') {
                        double jx = r(rnd, -.15, .15), jy = r(rnd, -.15, .15), sz = cell * r(rnd, 1.1, 1.2);   // blocks overlap: strokes stay solid
                        s.append('M').append(f(x * cell + jx)).append(' ').append(f(y * cell + jy)).append('h').append(f(sz)).append('v').append(f(sz)).append('h').append(f(-sz)).append('z');
                    }
            s.append("\"/>");
        }
        // strike-through lines over the letters
        for (int i = 0; i < 1; i++)
            s.append("<path d=\"M").append(f(r(rnd, 0, 25))).append(' ').append(f(r(rnd, 14, 50))).append(" L").append(f(r(rnd, 175, 200))).append(' ').append(f(r(rnd, 14, 50)))
                    .append("\" stroke=\"").append(COLORS[rnd.nextInt(COLORS.length)]).append("\" stroke-width=\"1.2\" opacity=\".35\"/>");
        return s.append("</svg>").toString();
    }
}
