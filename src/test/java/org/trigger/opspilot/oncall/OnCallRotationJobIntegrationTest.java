package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-rotation-job-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "opspilot.ai.enabled=false",
        "opspilot.oncall.escalation.enabled=false", "opspilot.oncall.rotation.scan-delay=100",
        "opspilot.oncall.rotation.initial-delay=500"
})
class OnCallRotationJobIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private OnCallRosterService roster;
    @Autowired private OnCallRotationService rotations;
    @Autowired private OnCallService onCall;

    @Test
    void shouldGenerateTheBlockedSlotThroughTheRealSchedulerAfterConflictIsRemoved() throws Exception {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO oncall_schedule(service_resource_id, name) VALUES (3, '定时轮转验收')").update(key, "id");
        long schedule = key.getKey().longValue();
        LocalDateTime at = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single()
                .truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        var manual = roster.create(new OnCallRosterService.ShiftCommand(schedule, 3,
                at, at.plusHours(8), false, "暂时占用"), 1L, "test");
        var rotation = rotations.create(new OnCallRotationService.Command(schedule, "后台续排",
                at, 480, List.of(2L)), 1L, "test");
        assertThat(slotStatus(rotation.id())).isEqualTo("BLOCKED");
        roster.cancel(manual.id(), 0, "释放占用，等待后台补齐", 1L, "test");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!"GENERATED".equals(slotStatus(rotation.id())) && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(slotStatus(rotation.id())).isEqualTo("GENERATED");
        long shift = jdbc.sql("SELECT shift_id FROM oncall_rotation_slot WHERE rotation_id = :id AND slot_index = 0")
                .param("id", rotation.id()).query(Long.class).single();
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log WHERE action = 'ONCALL_SHIFT_CREATED'
                          AND target_id = :id AND actor_id IS NULL AND ip_address = 'scheduler'
                        """).param("id", Long.toString(shift)).query(Long.class).single()).isEqualTo(1);
        assertThat(onCall.current().stream().filter(view -> view.scheduleId() == schedule).findFirst().orElseThrow().userId())
                .isEqualTo(2L);
        rotations.state(rotation.id(), 0, false, "验收结束，保留生成事实", 1L, "test");
    }

    private String slotStatus(long id) {
        return jdbc.sql("SELECT status FROM oncall_rotation_slot WHERE rotation_id = :id AND slot_index = 0")
                .param("id", id).query(String.class).single();
    }
}
