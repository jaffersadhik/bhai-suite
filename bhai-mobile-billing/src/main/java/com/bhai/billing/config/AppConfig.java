package com.bhai.billing.config;

import com.bhai.billing.core.BillingService;
import com.bhai.billing.core.JdbcDb;
import com.bhai.billing.core.PostDeployTests;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class AppConfig {
    private BillingService service;

    /** software invoices: every month on the 2nd at 8 AM (India time) for the month that just ended */
    @Scheduled(cron = "0 0 8 2 * *", zone = "${bmb.zone:Asia/Kolkata}")
    public void monthlyInvoices() {
        if (service == null) return;
        try {
            int n = service.generateDueInvoices();
            log.info("MBill (Mobile Bill): monthly invoices generated: {}", n);
        } catch (RuntimeException e) {
            log.error("Monthly invoice generation failed", e);
        }
    }
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public JdbcDb jdbcDb(DataSource dataSource) { return new JdbcDb(dataSource); }

    @Bean
    public BillingService billingService(JdbcDb db, @Value("${bmb.zone:Asia/Kolkata}") String zone) {
        return new BillingService(db, ZoneId.of(zone));
    }

    @Bean
    public com.bhai.billing.core.Translator translator(JdbcDb db, @Value("${translate.google.key:}") String key, @Value("${translate.url:}") String url) {
        return new com.bhai.billing.core.Translator(db, key, url);
    }

    @Bean
    public PostDeployTests postDeployTests(JdbcDb db, @Value("${bmb.zone:Asia/Kolkata}") String zone) {
        return new PostDeployTests(db, ZoneId.of(zone));
    }

    /** every start-up is a deploy: once the web server is up, run the live post-deploy tests (in the background) */
    @EventListener
    public void onReady(ApplicationReadyEvent e) {
        PostDeployTests t = e.getApplicationContext().getBean(PostDeployTests.class);
        String port = e.getApplicationContext().getEnvironment().getProperty("local.server.port");
        if (port != null) t.setPort(Integer.parseInt(port));
        log.info("MBill build {} started - running post-deploy tests", t.build());
        t.startAsync("deploy / start-up");
    }

    /** Runs after schema.sql has created the tables: creates admin / admin1234 on an empty database. */
    @Bean
    public ApplicationRunner createDefaultAdmin(BillingService service) {
        return args -> {
            service.migrate();
            this.service = service;
            try {
                int n = service.generateDueInvoices();          // catch up if the server was down on the 2nd
                if (n > 0) log.info("MBill (Mobile Bill): {} pending monthly invoices generated at start-up", n);
            } catch (RuntimeException e) {
                log.error("Invoice catch-up failed", e);
            }
            if (service.ensureDefaultAdmin())
                log.warn("MBill (Mobile Bill): first start - created login admin / admin1234. Change this password immediately after login.");
        };
    }
}
