package org.openboxes.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openboxes.identity.entity.PasswordResetToken;
import org.openboxes.identity.entity.User;
import org.openboxes.identity.repository.PasswordResetTokenRepository;
import org.openboxes.identity.repository.UserRepository;
import org.openboxes.identity.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Integration test for the identity service end-to-end against a throwaway MariaDB container
 * (Task 16 — JUnit + TestContainers). Mirrors the {@code DocumentServiceIntegrationTest}
 * pattern: real Spring context + JPA + MariaDB, with Hibernate emitting the schema from JPA
 * entities (production Liquibase wiring intentionally disabled — entity-vs-changelog
 * divergence is covered by the live compose stack + Playwright E2E specs, not here).
 *
 * <p>Fixture rows are loaded from {@code test-data/seed.sql} after schema generation via
 * {@code spring.jpa.defer-datasource-initialization=true}. {@link JavaMailSender} is mocked
 * so signup / password reset tests don't touch real SMTP. Endpoints are exercised through
 * MockMvc to keep cookie + status assertions straightforward.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IdentityServiceIntegrationTest {

    @Container
    static final MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:10.6");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        // HS256 requires a >=32-byte secret; this filter + AuthService both consume it.
        r.add("openboxes.jwt.secret", () -> "test-jwt-secret-at-least-32-chars-for-hmac-sha256-junit-only");
        r.add("openboxes.mail.from", () -> "openboxes-test@example.com");
        // Signup is gated behind this flag in production; turn it on so signup_* tests run.
        r.add("openboxes.signup.enabled", () -> "true");
        // reCAPTCHA disabled (default), so RecaptchaService.validate() returns true and the
        // signup tests don't need to mock the Google verifier endpoint.
        r.add("spring.liquibase.enabled", () -> "false");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // create-drop runs BEFORE data.sql by default; defer keeps the seed load until after
        // Hibernate has emitted the schema.
        r.add("spring.jpa.defer-datasource-initialization", () -> "true");
        r.add("spring.sql.init.mode", () -> "always");
        r.add("spring.sql.init.data-locations", () -> "classpath:test-data/seed.sql");
    }

    // BCrypt-encoded "Admin123!" — shared with seed.sql admin/disabled/nullactive/reset/
    // nonadmin/target users for easy reuse across tests.
    private static final String ADMIN_PASSWORD = "Admin123!";
    private static final String LEGACY_PASSWORD = "Legacy123!";
    private static final String ADMIN_ID    = "person-admin00000000000000000000000000";
    private static final String LEGACY_ID   = "person-legacy0000000000000000000000000";
    private static final String DISABLED_ID = "person-disabled00000000000000000000000";
    private static final String NULLACTIVE_ID = "person-nullactive000000000000000000000";
    private static final String RESET_ID    = "person-reset00000000000000000000000000";
    private static final String NONADMIN_ID = "person-nonadmin00000000000000000000000";
    private static final String TARGET_ID   = "person-target0000000000000000000000000";
    private static final String WAREHOUSE_ID = "loc-warehouse0000000000000000000000000";
    private static final String DISABLED_LOC_ID = "loc-disabled00000000000000000000000000";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PasswordResetTokenRepository tokenRepository;
    @Autowired JwtService jwtService;
    @PersistenceContext EntityManager em;

    @MockBean JavaMailSender mailSender;

    @BeforeEach
    void resetMocks() {
        reset(mailSender);
    }

    // ---------- login ----------

    @Test
    void loginGoodCreds_returns200AndSetsCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "admin", "password", ADMIN_PASSWORD))))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(JwtService.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly");
    }

    @Test
    void loginBadCreds_returns401() throws Exception {
        mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "admin", "password", "WrongPass1!"))))
            .andExpect(status().is(401));
    }

    @Test
    void loginDisabledAccount_returns403() throws Exception {
        mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "disabled", "password", ADMIN_PASSWORD))))
            .andExpect(status().is(403));
    }

    @Test
    void loginNullActiveAccount_returns403() throws Exception {
        mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "nullactive", "password", ADMIN_PASSWORD))))
            .andExpect(status().is(403));
    }

    @Test
    void logout_clearsCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/identity/logout")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintAdminToken(null))))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(JwtService.COOKIE_NAME + "=;");
        assertThat(setCookie).contains("Max-Age=0");
    }

    // ---------- chooseLocation ----------

    @Test
    void chooseLocation_reissuesJwtAndUpdatesLastLoginDate() throws Exception {
        Instant before = Instant.now();
        MvcResult result = mvc.perform(put("/api/identity/chooseLocation/" + WAREHOUSE_ID)
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintAdminToken(null))))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(JwtService.COOKIE_NAME + "=");
        em.clear();
        User user = userRepository.findById(ADMIN_ID).orElseThrow();
        assertThat(user.getLastLoginDate()).isNotNull();
        assertThat(user.getLastLoginDate()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void chooseLocation_404OnBadLocationId() throws Exception {
        mvc.perform(put("/api/identity/chooseLocation/does-not-exist-id")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintAdminToken(null))))
            .andExpect(status().is(404));
    }

    @Test
    void chooseLocation_403OnDisabledLocation() throws Exception {
        mvc.perform(put("/api/identity/chooseLocation/" + DISABLED_LOC_ID)
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintAdminToken(null))))
            .andExpect(status().is(403));
    }

    // ---------- me ----------

    @Test
    void me_returnsUserAndLocationAndEffectiveRoles() throws Exception {
        mvc.perform(get("/api/identity/me")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintAdminToken(WAREHOUSE_ID))))
            .andExpect(status().is(200))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("admin")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Test Warehouse")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("role-admin")));
    }

    // ---------- signup ----------

    @Test
    void signup_createsUserAndPersonAndSendsEmail() throws Exception {
        mvc.perform(post("/api/identity/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "username", "newsignup",
                    "password", "GoodPass1!",
                    "firstName", "New",
                    "lastName", "Signup",
                    "email", "newsignup@example.com",
                    "phoneNumber", "555-0000",
                    "recaptchaToken", ""))))
            .andExpect(status().is(200));
        assertThat(userRepository.findByUsername("newsignup")).isPresent();
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        assertThat(captor.getValue().getTo()).contains("newsignup@example.com");
    }

    @Test
    void signup_409OnDuplicateUsername() throws Exception {
        mvc.perform(post("/api/identity/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "username", "admin",
                    "password", "GoodPass1!",
                    "firstName", "Dup",
                    "lastName", "User",
                    "email", "different@example.com",
                    "phoneNumber", "",
                    "recaptchaToken", ""))))
            .andExpect(status().is(409));
    }

    @Test
    void signup_400OnWeakPassword() throws Exception {
        mvc.perform(post("/api/identity/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "username", "weakpw",
                    "password", "short",
                    "firstName", "Weak",
                    "lastName", "Pw",
                    "email", "weakpw@example.com",
                    "phoneNumber", "",
                    "recaptchaToken", ""))))
            .andExpect(status().is(400));
    }

    // ---------- password change (self) ----------

    @Test
    void passwordChange_verifiesCurrentBeforeSettingNew() throws Exception {
        mvc.perform(post("/api/identity/password/change")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintTokenFor(RESET_ID, null, List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", "NewPass1!"))))
            .andExpect(status().is(200));
        em.clear();
        User u = userRepository.findById(RESET_ID).orElseThrow();
        assertThat(u.getPassword()).startsWith("$2a$");
    }

    @Test
    void passwordChange_400OnWeakNew() throws Exception {
        mvc.perform(post("/api/identity/password/change")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintTokenFor(ADMIN_ID, null, List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", "weak"))))
            .andExpect(status().is(400));
    }

    @Test
    void passwordChange_401OnWrongCurrent() throws Exception {
        mvc.perform(post("/api/identity/password/change")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME, mintTokenFor(ADMIN_ID, null, List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("currentPassword", "Wrong1!", "newPassword", "NewPass1!"))))
            .andExpect(status().is(401));
    }

    // ---------- password reset ----------

    @Test
    void passwordResetRequest_alwaysReturns200() throws Exception {
        // existing email
        mvc.perform(post("/api/identity/password/reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "reset@example.com"))))
            .andExpect(status().is(200));
        // unknown email — must also 200 (do not leak existence)
        mvc.perform(post("/api/identity/password/reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "nobody@example.com"))))
            .andExpect(status().is(200));
    }

    @Test
    @Transactional
    void passwordResetConfirm_validatesTokenAndUpdatesHash() throws Exception {
        String token = persistResetToken(RESET_ID, Instant.now().plus(1, ChronoUnit.HOURS), null);
        mvc.perform(post("/api/identity/password/reset/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", "ResetPass1!"))))
            .andExpect(status().is(200));
        em.clear();
        User u = userRepository.findById(RESET_ID).orElseThrow();
        assertThat(u.getPassword()).startsWith("$2a$");
    }

    @Test
    @Transactional
    void passwordResetConfirm_400OnUsedToken() throws Exception {
        String token = persistResetToken(RESET_ID, Instant.now().plus(1, ChronoUnit.HOURS), Instant.now());
        mvc.perform(post("/api/identity/password/reset/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", "ResetPass1!"))))
            .andExpect(status().is(400));
    }

    @Test
    @Transactional
    void passwordResetConfirm_400OnExpiredToken() throws Exception {
        String token = persistResetToken(RESET_ID, Instant.now().minus(1, ChronoUnit.HOURS), null);
        mvc.perform(post("/api/identity/password/reset/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", "ResetPass1!"))))
            .andExpect(status().is(400));
    }

    // ---------- legacy password migration ----------

    @Test
    void sha1AutoMigrate_acceptsSha1ThenStoresBcrypt() throws Exception {
        mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "legacy", "password", LEGACY_PASSWORD))))
            .andExpect(status().is(200));
        // PasswordMigrator uses REQUIRES_NEW propagation, so the rewrite commits in its own
        // transaction. Clear the persistence context to force a re-read.
        em.clear();
        User u = userRepository.findById(LEGACY_ID).orElseThrow();
        assertThat(u.getPassword()).startsWith("$2a$");
    }

    @Test
    void cleartextStored_rejected() throws Exception {
        // Seed has user 'cleartext' with literal "cleartext" stored as the password column.
        // Spec §10.1, §14: no cleartext fallback — must 401.
        mvc.perform(post("/api/identity/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "cleartext", "password", "cleartext"))))
            .andExpect(status().is(401));
    }

    // ---------- admin endpoint authorization ----------

    @Test
    void adminEndpoint_403WhenCallerNotAdmin() throws Exception {
        // Non-admin caller (ROLE_BROWSER only) hits PUT /users/{id}/password
        mvc.perform(put("/api/identity/users/" + TARGET_ID + "/password")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME,
                    mintTokenFor(NONADMIN_ID, null, List.of("role-browser00000000000000000000000000"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", "NewPass1!"))))
            .andExpect(status().is(403));
    }

    @Test
    void adminEndpoint_200WhenCallerIsAdmin() throws Exception {
        mvc.perform(put("/api/identity/users/" + TARGET_ID + "/password")
                .cookie(new jakarta.servlet.http.Cookie(JwtService.COOKIE_NAME,
                    mintTokenFor(ADMIN_ID, null,
                        List.of("role-admin0000000000000000000000000000", "role-browser00000000000000000000000000"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("newPassword", "NewPass1!"))))
            .andExpect(status().is(200));
        em.clear();
        User u = userRepository.findById(TARGET_ID).orElseThrow();
        assertThat(u.getPassword()).startsWith("$2a$");
    }

    // ---------- helpers ----------

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String mintAdminToken(String locationId) {
        return mintTokenFor(ADMIN_ID, locationId,
            List.of("role-admin0000000000000000000000000000", "role-browser00000000000000000000000000"));
    }

    private String mintTokenFor(String userId, String locationId, List<String> roleIds) {
        User u = userRepository.findById(userId).orElseThrow();
        return jwtService.issue(u, locationId, roleIds);
    }

    private String persistResetToken(String userId, Instant expiresAt, Instant usedAt) {
        User u = userRepository.findById(userId).orElseThrow();
        String token = "tok-" + java.util.UUID.randomUUID().toString().replace("-", "");
        PasswordResetToken prt = new PasswordResetToken();
        prt.setToken(token);
        prt.setUser(u);
        prt.setCreatedAt(Instant.now());
        prt.setExpiresAt(expiresAt);
        prt.setUsedAt(usedAt);
        tokenRepository.save(prt);
        return token;
    }

    // Static imports for status() and content() — kept here to keep the method-level
    // imports compact at the top of the file.
    private static org.springframework.test.web.servlet.result.StatusResultMatchers status() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
    }

    private static org.springframework.test.web.servlet.result.ContentResultMatchers content() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.content();
    }
}
