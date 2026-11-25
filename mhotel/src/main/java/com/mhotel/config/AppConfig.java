package com.mhotel.config;

import com.mhotel.core.Core;
import com.mhotel.core.JdbcDb;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Core core(DataSource ds, @Value("${mhotel.zone:Asia/Kolkata}") String zone) {
        return new Core(new JdbcDb(ds), ZoneId.of(zone));
    }

    /** schema.sql has already run by now (spring.sql.init). */
    @Bean
    public CommandLineRunner startup(Core core) {
        return args -> core.start();
    }
}
