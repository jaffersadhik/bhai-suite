package com.bhai.billing.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Access-token authentication: clients send "Authorization: Bearer &lt;access_token&gt;" (RFC 6750).
 * The token is mapped onto the internal X-Auth header the controllers read, so both forms work
 * (X-Auth stays accepted for older app copies). A 401 answer carries "WWW-Authenticate: Bearer".
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String a = req.getHeader("Authorization");
        if (a != null && a.regionMatches(true, 0, "Bearer ", 0, 7) && req.getHeader("X-Auth") == null) {
            final String tok = a.substring(7).trim();
            req = new HttpServletRequestWrapper(req) {
                @Override public String getHeader(String name) { return "X-Auth".equalsIgnoreCase(name) ? tok : super.getHeader(name); }
                @Override public Enumeration<String> getHeaders(String name) {
                    return "X-Auth".equalsIgnoreCase(name) ? Collections.enumeration(Collections.singletonList(tok)) : super.getHeaders(name);
                }
                @Override public Enumeration<String> getHeaderNames() {
                    Set<String> s = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
                    s.add("X-Auth");
                    return Collections.enumeration(s);
                }
            };
        }
        chain.doFilter(req, res);
    }
}
