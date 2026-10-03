package org.trigger.opspilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-auth-session-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false", "spring.ai.dashscope.api-key=disabled", "opspilot.agent.recovery.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false",
        "server.address=127.0.0.1", "management.server.port=0", "management.server.address=127.0.0.1"
})
class AuthSessionHttpIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JwtProperties properties;
    @Autowired private SessionAuthorization authorization;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.boot.test.mock.mockito.SpyBean private org.trigger.opspilot.audit.AuditService audit;
    @LocalServerPort private int port;
    private AuthSessionHttpScenarios scenario() { return new AuthSessionHttpScenarios(jdbc, mapper, encoder, port); }
    @Test void shouldFenceFinalAuthorizationAgainstConcurrentRevocationCommit() throws Exception {
        try (var s = scenario()) { s.finalAuthorizationSeesRevocationCommittedWhileWaiting(authorization, transactions); }
    }
    @Test void shouldRecheckLeaseExpiryAfterFinalAuthorizationLockWait() throws Exception {
        try (var s = scenario()) { s.finalAuthorizationRechecksExpiryAfterRowLockWait(authorization, transactions); }
    }
    @Test void shouldRevokeAllIssuedTokensWithoutFreshLoginRevivingThem() throws Exception {
        try (var s = scenario()) { s.logoutAllRevokesTwoTokensPermanently(); }
    }
    @Test void shouldChangePasswordAndRevokeTokensAtomically() throws Exception {
        try (var s = scenario()) { s.passwordChangeRevokesTokensAndRequiresNewPassword(); }
    }
    @Test void shouldRejectInvalidPasswordChangesWithoutWrites() throws Exception {
        try (var s = scenario()) { s.invalidPasswordChangesDoNotWrite(); }
    }
    @Test void shouldFenceConcurrentLogoutRequests() throws Exception {
        try (var s = scenario()) { s.concurrentRevocationsOnlyAdvanceOnce(); }
    }
    @Test void shouldOnlyRevokeAuthenticatedActorsOwnSessions() throws Exception {
        try (var s = scenario()) { s.revocationDoesNotAffectAnotherAccount(); }
    }
    @Test void shouldRejectLegacyAndMalformedSessionVersions() throws Exception {
        try (var s = scenario()) { s.rejectsLegacyAndMalformedVersions(properties); }
    }
    @Test void shouldSupportUnicodeWithoutAllowingUnchangedPassword() throws Exception {
        try (var s = scenario()) { s.supportsUnicodeAndRejectsUnchangedPassword(); }
    }
    @Test void shouldRejectSessionVersionExhaustionWithoutWrapping() throws Exception {
        try (var s = scenario()) { s.versionExhaustionDoesNotWrap(); }
    }
    @Test void shouldRollbackPasswordAndVersionWhenAuditFails() throws Exception {
        org.mockito.Mockito.doThrow(new org.trigger.opspilot.common.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "FORCED_AUDIT_FAILURE", "controlled audit failure"))
                .when(audit).record(org.mockito.ArgumentMatchers.eq("AUTH_PASSWORD_CHANGED"),
                        org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        try (var s = scenario()) { s.failedAuditRollsBackPasswordAndVersion(); }
    }
    @Test void shouldRejectBcryptByteTruncationAliasesAtLogin() throws Exception {
        try (var s = scenario()) { s.rejectsBcryptTruncationAliasesAtLogin(); }
    }
    @Test void shouldServeAccountSpaDeepLinkButKeepApiProtected() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var page = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/account/security"))
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(page.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(page.body()).contains("<div id=\"app\">").doesNotContain("password_hash");
        var api = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/auth/me"))
                .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        org.assertj.core.api.Assertions.assertThat(api.statusCode()).isEqualTo(401);
    }
}
