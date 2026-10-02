package org.trigger.opspilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-account-status-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.agent.recovery.enabled=false", "opspilot.oncall.rotation.enabled=false",
        "opspilot.oncall.escalation.enabled=false", "management.server.port=0",
        "server.address=127.0.0.1", "management.server.address=127.0.0.1"
})
class AccountStatusHttpIntegrationTest {
    @Autowired private org.trigger.opspilot.audit.AuditService auditService;
    @org.junit.jupiter.api.BeforeEach
    void bindLegacyServiceRequestFixture() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.59");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
    }
    @org.junit.jupiter.api.AfterEach
    void clearLegacyServiceRequestFixture() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtProperties jwtProperties;
    @LocalServerPort private int port;

    @Test void shouldRejectDisabledReadsAndLogin() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.disabledReadsAndLogin(); }
    }
    @Test void shouldRejectDisabledWriteWithoutAuditOrTimeline() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.disabledWriteHasNoSideEffects(); }
    }
    @Test void shouldRejectOldJwtAfterAccountRemoval() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.removedAccountCannotUseOldToken(); }
    }
    @Test void shouldReloadCurrentRoleButKeepActiveAuthentication() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.activeRoleUsesCurrentDatabaseAuthority(); }
    }
    @Test void shouldUseRealServletRequestForAuthenticatedHttpWrite() throws Exception {
        assertThatLegacyIp();
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.activeWriteUsesRealRequestContext(); }
    }
    @Test void shouldRejectOldJwtWhenUsernameIsRecreated() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) { scenario.recreatedUsernameCannotInheritOldToken(); }
    }
    @Test void shouldRejectMalformedSignedIdentityWithoutBusinessWrites() throws Exception {
        try (var scenario = new AccountStatusHttpScenarios(jdbc, mapper, port)) {
            scenario.malformedSignedIdentityIsUnauthorized(jwtProperties);
        }
    }
    private void assertThatLegacyIp() {
        org.assertj.core.api.Assertions.assertThat(auditService.currentIp()).isEqualTo("192.0.2.59");
    }
}
