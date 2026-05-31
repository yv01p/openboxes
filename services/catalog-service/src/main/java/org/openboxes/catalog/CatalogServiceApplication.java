package org.openboxes.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// @EnableJpaAuditing activates @CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy
// (FD#8 Option-A audit infra introduced in T2; auditor supplied by JwtAuditorAware).
@SpringBootApplication
@EnableJpaAuditing
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
