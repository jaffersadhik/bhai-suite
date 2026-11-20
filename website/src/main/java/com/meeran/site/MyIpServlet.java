package com.meeran.site;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * GET /api/myip  ->  {"ip":"203.0.113.7"}   (the footer "Your IP" line of the site).
 * GET /api/ping   ->  {"ok":true}            (Docker HEALTHCHECK of the shared container).
 *
 * Only {@code request.getRemoteAddr()} is used. When the site runs behind Caddy, the RemoteIpValve in tomcat/server.xml replaces the
 * proxy's address by the real visitor address from X-Forwarded-For - and only when the request really comes from the trusted proxy,
 * so a visitor cannot fake the value by sending his own X-Forwarded-For header.
 */
public class MyIpServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-store");
        if ("/api/ping".equals(req.getServletPath())) { res.getWriter().write("{\"ok\":true}"); return; }
        res.getWriter().write("{\"ip\":\"" + clean(req.getRemoteAddr()) + "\"}");
    }

    /** an IP address only ever contains hex digits, '.', ':' and (IPv6 scope) '%'; anything else is dropped */
    static String clean(String ip) {
        if (ip == null) return "";
        StringBuilder b = new StringBuilder(ip.length());
        for (int i = 0; i < ip.length() && b.length() < 64; i++) {
            char c = ip.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':' || c == '%') b.append(c);
        }
        return b.toString();
    }
}
