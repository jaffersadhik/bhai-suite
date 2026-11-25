package com.pomgmt.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Small stateless helpers shared by the services. */
public final class U {
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private U() {
    }

    /** raw string or null (used by the login endpoint). */
    public static String s(Object o) {
        return o == null ? null : o.toString();
    }

    /** trimmed string, never null. */
    public static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    /** trimmed string clipped to n characters. */
    public static String clip(Object o, int n) {
        String v = str(o);
        return v.length() <= n ? v : v.substring(0, n);
    }

    public static boolean blank(Object o) {
        return str(o).isEmpty();
    }

    public static long lng(Object o) {
        // MySQL Connector/J returns TINYINT(1) columns (active, over_budget, ...) as Boolean by default (tinyInt1isBit=true)
        if (o instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(str(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static int intv(Object o) {
        return (int) lng(o);
    }

    public static double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(str(o).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    public static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static BigDecimal money(double v) {
        return BigDecimal.valueOf(round2(v));
    }

    public static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = HEX[(b[i] >> 4) & 0xf];
            out[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(out);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new DbException("sha256", e);
        }
    }

    /** url-safe random token. */
    public static String token() {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        return hex(b);
    }

    public static String salt() {
        byte[] b = new byte[12];
        RND.nextBytes(b);
        return hex(b);
    }

    /** keep only digits (phone numbers). */
    public static String digits(Object o) {
        return str(o).replaceAll("[^0-9]", "");
    }
}
