package org.trigger.opspilot.investigation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AgentEventOutbox {
    private final JdbcClient jdbc;

    public AgentEventOutbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Claim> claim(int limit, LocalDateTime now, Duration lease) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (limit < 1 || limit > 100 || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("Invalid outbox batch or lease");
        }
        var candidates = jdbc.sql("""
                        SELECT event_id FROM agent_event_outbox
                        WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                           OR (status = 'CLAIMED' AND lease_until <= :now)
                        ORDER BY event_id LIMIT :limit
                        """).param("now", now).param("limit", limit).query(Long.class).list();
        List<Claim> claimed = new ArrayList<>();
        for (long eventId : candidates) {
            String token = UUID.randomUUID().toString();
            int updated = jdbc.sql("""
                            UPDATE agent_event_outbox
                            SET status = 'CLAIMED', lease_token = :token, lease_until = :until,
                                attempts = attempts + 1
                            WHERE event_id = :id AND
                              ((status = 'PENDING' AND next_attempt_at <= :now)
                               OR (status = 'CLAIMED' AND lease_until <= :now))
                            """).param("id", eventId).param("token", token)
                    .param("until", now.plus(lease)).param("now", now).update();
            if (updated == 1) claimed.add(new Claim(eventId, token));
        }
        return List.copyOf(claimed);
    }

    public boolean delivered(Claim claim, LocalDateTime now) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return jdbc.sql("""
                        UPDATE agent_event_outbox
                        SET status = 'DELIVERED', delivered_at = :now, lease_token = NULL, lease_until = NULL
                        WHERE event_id = :id AND status = 'CLAIMED'
                          AND lease_token = :token AND lease_until > :now
                        """).param("id", claim.eventId()).param("token", claim.token())
                .param("now", now).update() == 1;
    }

    public boolean retry(Claim claim, LocalDateTime now, Duration delay) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (delay.isNegative() || delay.isZero()) throw new IllegalArgumentException("Invalid retry delay");
        return jdbc.sql("""
                        UPDATE agent_event_outbox
                        SET status = 'PENDING', next_attempt_at = :next, lease_token = NULL, lease_until = NULL
                        WHERE event_id = :id AND status = 'CLAIMED'
                          AND lease_token = :token AND lease_until > :now
                        """).param("id", claim.eventId()).param("token", claim.token())
                .param("now", now).param("next", now.plus(delay)).update() == 1;
    }

    public record Claim(long eventId, String token) { }
}
