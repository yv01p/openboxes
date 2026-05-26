package org.openboxes.document;

import org.junit.jupiter.api.Test;
import org.openboxes.document.entity.Document;
import org.openboxes.document.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DocumentService} backed by a throwaway MariaDB container
 * (Task 11 Option A — TestContainers). The dev compose stack only exposes db:3306 on
 * the internal docker network, not the host, so a "real DB" integration test that runs
 * from {@code ./gradlew test} on the developer's host or in CI needs its own ephemeral
 * DB.
 *
 * <p>{@code mariadb:10.6} pins to the dev compose image (see docker-compose-base.yml's
 * referenced image) so the SQL dialect / driver behaviour matches prod.
 *
 * <p><b>What this test actually exercises:</b> Spring context + JPA entity mapping +
 * transactional create/findById roundtrip against real MariaDB. <b>What it does NOT
 * exercise:</b> production Liquibase wiring (disabled below — see comment on
 * {@code spring.liquibase.enabled=false}) or schema validity against the production
 * changelog. Entity-vs-Liquibase divergence is NOT caught here — covered today only
 * by the live compose stack's Hibernate validate mode at app boot, which the Playwright
 * E2E specs exercise transitively. T11-I1 tracks closing that gap.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
class DocumentServiceIntegrationTest {

    @Container
    static final MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:10.6");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        // openboxes.jwt.secret is normally pulled from the env via application.yml;
        // tests don't exercise the filter, but the bean still requires a non-null value.
        r.add("openboxes.jwt.secret", () -> "junit-test-secret-not-used-by-this-test");
        // The production document-changelog-master is a SHADOW changelog: it presupposes
        // the Grails-side liquibase has already created the document/document_type tables,
        // and its preConditions fail against a pristine MariaDB. For this test we let
        // Hibernate emit the schema from the JPA entities instead, which is sufficient to
        // exercise DocumentService end-to-end. Production wiring (Liquibase validate-mode
        // schema against Grails-built tables) is still covered by the live compose stack
        // and the Playwright E2E specs.
        r.add("spring.liquibase.enabled", () -> "false");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired DocumentService docService;

    @Test
    void createAndFetchById() {
        Document d = docService.create("test", "test.txt", "text/plain", "hi".getBytes(), null);
        Document fetched = docService.findById(d.getId()).orElseThrow();
        assertThat(fetched.getFilename()).isEqualTo("test.txt");
        assertThat(fetched.getFileContents()).isEqualTo("hi".getBytes());
        assertThat(fetched.getContentType()).isEqualTo("text/plain");
        assertThat(fetched.getName()).isEqualTo("test");
    }
}
