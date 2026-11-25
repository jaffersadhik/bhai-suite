package com.pomgmt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** Runs as a WAR in Tomcat (ROOT.war of the purchase service) or standalone: java -jar po-mgmt.war */
@SpringBootApplication
public class PomgmtApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(PomgmtApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(PomgmtApplication.class, args);
    }
}
