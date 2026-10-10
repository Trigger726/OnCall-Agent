package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class CoverageScenarios {
    @Autowired private JdbcClient jdbc;
    @Autowired private OnCallCoverageService coverage;
    @Autowired private OnCallRosterService roster;
    @Autowired private OnCallRotationService rotations;
    @Autowired private OnCallService current;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void shouldPartitionGapsOverridesAndHalfOpenHandoffsWithoutDoubleCounting() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        var base = shift(schedule, 2, at.plusHours(1), at.plusHours(5), false);
        var cover = shift(schedule, 3, at.plusHours(2), at.plusHours(3), true);
        var handoff = shift(schedule, 3, at.plusHours(5), at.plusHours(7), false);
        var view = coverage.coverage(schedule, at, at.plusHours(8));
        assertThat(view.totalSeconds()).isEqualTo(28800);
        assertThat(view.coveredSeconds()).isEqualTo(21600);
        assertThat(view.gapSeconds()).isEqualTo(7200);
        assertThat(view.segments()).extracting(OnCallCoverageService.Segment::shiftId)
                .containsExactly(null, base.id(), cover.id(), base.id(), handoff.id(), null);
        assertThat(view.segments().get(2).shadowedShiftIds()).containsExactly(base.id());
        assertThat(view.segments()).allSatisfy(s -> assertThat(s.sameLayerOverlap()).isFalse());
        var clipped = coverage.coverage(schedule, at.plusHours(2), at.plusHours(3));
        assertThat(clipped.segments()).singleElement().satisfies(s -> {
            assertThat(s.shiftId()).isEqualTo(cover.id());
            assertThat(s.startsAt()).isEqualTo(at.plusHours(2));
            assertThat(s.endsAt()).isEqualTo(at.plusHours(3));
        });
    }

    @Test
    void shouldMatchCurrentSelectionAndExcludeCancelledCoverage() {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var base = shift(schedule, 2, at, at.plusHours(4), false);
        var cover = shift(schedule, 3, at, at.plusHours(1), true);
        assertThat(coverage.coverage(schedule, at, at.plusHours(4)).segments().get(0).userId())
                .isEqualTo(current.current().stream().filter(s -> s.scheduleId() == schedule).findFirst().orElseThrow().userId());
        roster.cancel(cover.id(), 0, "恢复原班", 1L, "test");
        assertThat(coverage.coverage(schedule, at, at.plusHours(4)).segments()).singleElement()
                .satisfies(s -> assertThat(s.shiftId()).isEqualTo(base.id()));
        roster.cancel(base.id(), 0, "留空验收", 1L, "test");
        assertThat(coverage.coverage(schedule, at, at.plusHours(4)).gapSeconds()).isEqualTo(14400);
    }

    @Test
    void shouldFallbackFromIneligibleOverrideAndReportUnavailableGapsAndInactivePlans() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        var base = shift(schedule, 2, at, at.plusHours(2), false);
        long unavailable = insert("""
                INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at,override_flag)
                VALUES (:id,4,:start,:end,TRUE)
                """, schedule, at, at.plusHours(3)); // Auditor is active, but not an eligible on-call role.
        var view = coverage.coverage(schedule, at, at.plusHours(3));
        assertThat(view.segments()).hasSize(2);
        assertThat(view.segments().get(0).shiftId()).isEqualTo(base.id());
        assertThat(view.segments().get(0).unavailableShiftIds()).containsExactly(unavailable);
        assertThat(view.segments().get(1).gapReason()).isEqualTo("MEMBER_UNAVAILABLE");
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id", schedule).update();
        var inactive = coverage.coverage(schedule, at, at.plusHours(3));
        assertThat(inactive.gapSeconds()).isEqualTo(10800);
        assertThat(inactive.segments()).allSatisfy(s -> {
            assertThat(s.shiftId()).isNull();
            assertThat(s.gapReason()).isEqualTo("SCHEDULE_INACTIVE");
        });
    }

    @Test
    void shouldRecomputeCoverageFromCurrentAccountStatusAndRole() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        var base = shift(schedule, 2, at, at.plusHours(2), false);
        var cover = shift(schedule, 3, at, at.plusHours(2), true);
        try {
            jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=3").update();
            var inactiveUser = coverage.coverage(schedule, at, at.plusHours(2)).segments().get(0);
            assertThat(inactiveUser.shiftId()).isEqualTo(base.id());
            assertThat(inactiveUser.unavailableShiftIds()).containsExactly(cover.id());
            jdbc.sql("UPDATE sys_user SET status='ACTIVE',role_code='AUDITOR' WHERE id=3").update();
            assertThat(coverage.coverage(schedule, at, at.plusHours(2)).segments().get(0).shiftId()).isEqualTo(base.id());
        } finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE',role_code='OPS_MANAGER' WHERE id=3").update(); }
        assertThat(coverage.coverage(schedule, at, at.plusHours(2)).segments().get(0).shiftId()).isEqualTo(cover.id());
    }

    @Test
    void shouldRevealLegacySameLayerOverlapAndUseExactRoutingTieBreaks() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        long first = insert("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,2,:start,:end)", schedule, at, at.plusHours(4));
        long laterId = insert("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,3,:start,:end)", schedule, at, at.plusHours(4));
        long laterStart = insert("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,2,:start,:end)", schedule, at.plusHours(1), at.plusHours(3));
        var view = coverage.coverage(schedule, at, at.plusHours(4));
        assertThat(view.segments()).extracting(OnCallCoverageService.Segment::shiftId).containsExactly(laterId, laterStart, laterId);
        assertThat(view.segments()).allSatisfy(s -> assertThat(s.sameLayerOverlap()).isTrue());
        assertThat(view.segments().get(1).shadowedShiftIds()).containsExactly(laterId, first);
        assertThat(view.coveredSeconds()).isEqualTo(14400); // Three candidates are still one coverage interval.
    }

    @Test
    void shouldNotForecastUnmaterializedOrPausedRotations() {
        long schedule = schedule();
        LocalDateTime at = now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        var rule = rotations.create(new OnCallRotationService.Command(schedule, "覆盖验收", at, 480, List.of(2L,3L)), 1L, "test");
        rotations.state(rule.id(), rule.version(), false, "暂停验收", 1L, "test");
        var view = coverage.coverage(schedule, at, at.plusDays(21));
        assertThat(view.coveredSeconds()).isPositive().isLessThan(view.totalSeconds());
        assertThat(view.segments().get(view.segments().size()-1).gapReason()).isEqualTo("NO_SHIFT");
        long before = jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id", schedule).query(Long.class).single();
        coverage.coverage(schedule, at, at.plusDays(21));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id", schedule).query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void shouldRejectDenseInputRatherThanReturnTruncatedHealthyCoverage() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        for (int i=0; i<1001; i++) {
            insert("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,2,:start,:end)", schedule, at, at.plusHours(1));
        }
        assertThatThrownBy(() -> coverage.coverage(schedule, at, at.plusHours(1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_COVERAGE_TOO_DENSE");
        assertThat(coverage.coverage(schedule, at.plusHours(1), at.plusHours(2)).sourceShifts()).isZero();
    }

    @Test
    void shouldAllowAuthenticatedReadOnlyApiAndValidateWindowsWithoutWrites() throws Exception {
        long schedule = schedule();
        LocalDateTime at = now();
        mvc.perform(get("/api/v1/on-call/coverage").param("scheduleId", ""+schedule)).andExpect(status().isUnauthorized());
        String token = login("auditor");
        long audits = jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();
        mvc.perform(get("/api/v1/on-call/coverage").param("scheduleId", ""+schedule).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.segments[0].gapReason").value("NO_SHIFT"));
        for (String end : new String[]{at.toString(), at.minusHours(1).toString(), at.plusDays(32).toString()}) {
            mvc.perform(get("/api/v1/on-call/coverage").param("scheduleId", ""+schedule)
                            .param("from", at.toString()).param("to", end).header("Authorization", token)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/on-call/coverage").param("scheduleId", ""+schedule).param("from","invalid").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/on-call/coverage").param("scheduleId", "999999").header("Authorization", token)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single()).isEqualTo(audits);
    }

    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP").query((rs,row)->rs.getObject(1,LocalDateTime.class)).single().truncatedTo(ChronoUnit.SECONDS); }
    private long schedule() {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (3,:name)").param("name", "覆盖-"+UUID.randomUUID()).update(key,"id");
        PlanMembershipFixtures.grant(jdbc, key.getKey().longValue(), 1, 2, 3);
        return key.getKey().longValue();
    }
    private OnCallRosterService.ShiftView shift(long schedule,long user,LocalDateTime start,LocalDateTime end,boolean override) {
        return roster.create(new OnCallRosterService.ShiftCommand(schedule,user,start,end,override,"覆盖验收"),1L,"test");
    }
    private long insert(String sql,long schedule,LocalDateTime start,LocalDateTime end) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).param("id",schedule).param("start",start).param("end",end).update(key,"id");
        return key.getKey().longValue();
    }
    private String login(String username) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer "+json.readTree(response).path("data").path("accessToken").asText();
    }
}
