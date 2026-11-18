package com.mschool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/** Runs as a WAR in Tomcat (ROOT.war of the school service) or standalone: java -jar mschool.war */
@SpringBootApplication
public class MSchoolApplication extends SpringBootServletInitializer {
    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(MSchoolApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(MSchoolApplication.class, args);
    }
}
