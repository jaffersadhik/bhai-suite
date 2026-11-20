package com.mschool.config;

import com.mschool.core.Core;
import com.mschool.core.JdbcDb;
import com.mschool.core.Translator;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Core core(DataSource ds, @Value("${mschool.zone:Asia/Kolkata}") String zone) {
        return new Core(new JdbcDb(ds), ZoneId.of(zone));
    }

    @Bean
    public Translator translator(Core core, @Value("${translate.google.key:}") String key, @Value("${translate.url:}") String url) {
        return new Translator(core.db, key, url);
    }

    /** login captcha + per-IP failed-login counting + IP block (tables ip_fail_day / ip_block are created in the mschool database) */
    @Bean
    public com.suite.security.LoginSecurity loginSecurity(DataSource ds, @Value("${mschool.zone:Asia/Kolkata}") String zone) {
        return com.suite.security.LoginSecurity.fromEnvironment(ds, ZoneId.of(zone));
    }

    /** schema.sql has already run by now (spring.sql.init). */
    @Bean
    public CommandLineRunner startup(Core core) {
        return args -> core.start();
    }
}
