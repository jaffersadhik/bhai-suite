package com.bhai.billing.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Public demo videos (no login): /demo/ shows static/demo/index.html.
 * Spring Boot does not serve index.html for a directory URL, so /demo and /demo/ are mapped here.
 * The videos and posters under /demo/videos/ are plain static files (HTTP range requests work, so seeking is fine).
 */
@Controller
public class DemoPageController {
    @GetMapping({"/demo", "/demo/"})
    public String demo() {
        return "forward:/demo/index.html";
    }
}
