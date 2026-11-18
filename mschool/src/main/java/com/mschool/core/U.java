package com.mschool.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for reading request bodies and database rows. */
public final class U {
    private U() {
    }

    /** Trimmed string or null when blank. */
    public static String s(Object o) {
        if (o == null) {
            return null;
        }
        String t = o.toString().trim();
        return t.isEmpty() ? null : t;
    }

    public static String s(Object o, int max, String label) {
        String t = s(o);
        if (t != null && t.length() > max) {
            throw new ApiException(400, label + " " + max + " எழுத்துகளுக்குள் இருக்க வேண்டும்");
        }
        return t;
    }

    public static String req(Object o, int max, String label) {
        String t = s(o, max, label);
        if (t == null) {
            throw new ApiException(400, label + " தேவை");
        }
        return t;
    }

    public static long l(Object o, long def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String t = o.toString().trim();
        if (t.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            try {
                return (long) Double.parseDouble(t);
            } catch (NumberFormatException e2) {
                throw new ApiException(400, "எண் தவறு: " + t);
            }
        }
    }

    /** Nullable long: null when blank. */
    public static Long lo(Object o) {
        return s(o) == null ? null : Long.valueOf(l(o, 0));
    }

    public static int i(Object o, int def) {
        return (int) l(o, def);
    }

    public static double d(Object o, double def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        String t = o.toString().trim();
        if (t.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "எண் தவறு: " + t);
        }
    }

    public static boolean b(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        String t = o.toString().trim();
        return t.equals("1") || t.equalsIgnoreCase("true");
    }

    /** yyyy-MM-dd (extra time part ignored) or null. */
    public static LocalDate date(Object o) {
        String t = s(o);
        if (t == null) {
            return null;
        }
        try {
            return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t);
        } catch (Exception e) {
            throw new ApiException(400, "தேதி தவறு: " + t);
        }
    }

    public static LocalDate reqDate(Object o, String label) {
        LocalDate d = date(o);
        if (d == null) {
            throw new ApiException(400, label + " தேவை");
        }
        return d;
    }

    /** One of the allowed values (case-insensitive) or the default when blank. */
    public static String pick(Object o, String def, String label, String... allowed) {
        String t = s(o);
        if (t == null) {
            return def;
        }
        for (String a : allowed) {
            if (a.equalsIgnoreCase(t)) {
                return a;
            }
        }
        throw new ApiException(400, label + " தவறு: " + t);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    public static List<Long> longs(Object o) {
        List<Long> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(l(x, 0));
            }
        }
        return out;
    }

    /** LIKE pattern that matches q anywhere, with wildcards escaped. */
    public static String like(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Ordered map from alternating key, value arguments. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** Value of a row column as long (0 for null). */
    public static long n(Map<String, Object> row, String key) {
        return row == null ? 0 : l(row.get(key), 0);
    }
}
