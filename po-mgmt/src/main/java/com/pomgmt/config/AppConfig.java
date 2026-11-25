package com.pomgmt.config;

import com.pomgmt.core.Core;
import com.pomgmt.core.JdbcDb;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Core core(DataSource ds, @Value("${po.zone:Asia/Kolkata}") String zone,
                     @Value("${po.budget.enforcement:warn}") String enforcement) {
        return new Core(new JdbcDb(ds), ZoneId.of(zone), enforcement);
    }

    /** login captcha + per-IP failed-login counting + IP block (tables ip_fail_day / ip_block created in the po_mgmt database) */
    @Bean
    public com.suite.security.LoginSecurity loginSecurity(DataSource ds, @Value("${po.zone:Asia/Kolkata}") String zone) {
        return com.suite.security.LoginSecurity.fromEnvironment(ds, ZoneId.of(zone));
    }

    /** schema.sql has already run by now (spring.sql.init). */
    @Bean
    public CommandLineRunner startup(Core core) {
        return args -> core.start();
    }
}
