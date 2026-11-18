package com.bhai.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * MBill (Mobile Bill). Runs two ways: deployed as a WAR into Tomcat (ROOT.war, see the Dockerfile),
 * or standalone: java -jar bhai-mobile-billing.war (embedded Tomcat).
 */
@SpringBootApplication
public class BillingApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(BillingApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
