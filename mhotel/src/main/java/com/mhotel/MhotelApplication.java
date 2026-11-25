package com.mhotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** Runs as a WAR in Tomcat (ROOT.war of the hotel service) or standalone: java -jar mhotel.war */
@SpringBootApplication
public class MhotelApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(MhotelApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(MhotelApplication.class, args);
    }
}
