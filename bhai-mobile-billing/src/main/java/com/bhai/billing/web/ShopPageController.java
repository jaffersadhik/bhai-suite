package com.bhai.billing.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Every company's own web address: https://<domain>/s/<shop address>  (e.g. /s/dry-fruit-palace).
 * It serves the same online-shop page (static/shop.html); the page itself reads the address from the URL path and
 * asks the public API for that company's catalogue, stock and WhatsApp number. Old links (shop.html?s=<code>) keep working.
 */
@Controller
public class ShopPageController {
    private volatile String page;

    private String page() throws IOException {
        String p = page;
        if (p == null) {
            try (InputStream is = ShopPageController.class.getResourceAsStream("/static/shop.html")) {
                if (is == null) throw new IOException("shop.html missing");
                p = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            // the page lives at /s/<name>: make its relative files (i18n.js, i18n/*.json, fonts) load from the site root
            p = p.replaceFirst("<head>", "<head>\n<base href=\"/\">");
            page = p;
        }
        return p;
    }

    @GetMapping({"/s/{slug}", "/s/{slug}/"})
    @ResponseBody
    public ResponseEntity<String> shop() {
        try {
            return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                    .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic()).body(page());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.TEXT_PLAIN).body("Shop page not available");
        }
    }
}
