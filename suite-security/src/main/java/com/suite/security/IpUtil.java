package com.suite.security;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Turns whatever the server saw as the client address into one stable key; never does a DNS lookup. */
public final class IpUtil {
    private IpUtil() {
    }

    /**
     * IPv4 -> "a.b.c.d";  IPv6 -> its /64 network "2001:db8:1:2::/64" (one customer connection owns a whole /64, so blocking single
     * IPv6 addresses would be useless);  IPv4-mapped IPv6 -> IPv4;  anything else -> "unknown".
     */
    public static String key(String raw) {
        if (raw == null) return "unknown";
        String s = raw.trim();
        if (s.startsWith("[") && s.contains("]")) s = s.substring(1, s.indexOf(']'));
        int pct = s.indexOf('%');
        if (pct > 0) s = s.substring(0, pct);
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = s.split("\\.");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(p[i]);
                if (v > 255) return "unknown";
                b.append(i == 0 ? "" : ".").append(v);
            }
            return b.toString();
        }
        if (s.indexOf(':') >= 0 && s.matches("[0-9a-fA-F:.]{2,45}")) {
            try {
                InetAddress a = InetAddress.getByName(s);          // an IPv6 literal: parsed locally, no lookup
                byte[] b = a.getAddress();
                if (b.length == 4) return (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + "." + (b[3] & 255);
                return String.format(Locale.ROOT, "%x:%x:%x:%x::/64", ((b[0] & 255) << 8) | (b[1] & 255), ((b[2] & 255) << 8) | (b[3] & 255),
                        ((b[4] & 255) << 8) | (b[5] & 255), ((b[6] & 255) << 8) | (b[7] & 255));
            } catch (java.net.UnknownHostException e) {
                return "unknown";
            }
        }
        return "unknown";
    }

    /** comma separated list of addresses -> set of keys */
    public static Set<String> keys(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) return out;
        for (String p : csv.split("[,; ]+")) if (!p.isBlank()) out.add(key(p));
        return out;
    }
}
