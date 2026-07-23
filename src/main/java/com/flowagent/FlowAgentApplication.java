package com.flowagent;

import com.flowagent.link.tools.config.LinkConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * FlowAgent application entry point
 */
@SpringBootApplication
@Import(LinkConfiguration.class)
public class FlowAgentApplication {
    private static final Logger log = LoggerFactory.getLogger(FlowAgentApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FlowAgentApplication.class, args);
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> applicationReadyEventListener() {
        return event -> {
            String version = FlowAgentApplication.class.getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) {
                version = "dev";
            }

            String port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", "unknown");
            log.info("""

                ========================================
                  FlowAgent Engine Started!
                ========================================
                  Version: {}
                  Port: {}
                  Health: http://localhost:{}/actuator/health
                ========================================

                """, version, port, port);
        };
    }
}
