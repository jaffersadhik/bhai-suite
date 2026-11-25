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

    /** schema.sql has already run by now (spring.sql.init). */
    @Bean
    public CommandLineRunner startup(Core core) {
        return args -> core.start();
    }
}
