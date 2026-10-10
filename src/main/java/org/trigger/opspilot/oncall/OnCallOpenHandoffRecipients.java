package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** A publication is historical. Current eligibility is an observation, never a claim or delivery receipt. */
@Service
public class OnCallOpenHandoffRecipients {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OnCallOpenHandoffRecipients(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot freeze(OnCallOpenHandoffService.View row) {
        // Called once in the publisher's transaction, after its plan/source/actor checks.
        // An original-key acknowledgement never invokes this and never adds newly granted accounts.
        if (!row.status().equals("OPEN") || row.version() != 0) throw new IllegalArgumentException("Require original open publication");
        var at = now();
        var snapshot = new Snapshot(row.id(), row.scheduleId(), row.sourceShiftId(), row.sourceVersion(), row.requesterId(),
                row.startsAt(), row.endsAt(), row.version(), at, "DATABASE_SESSION_LOCAL", candidates(row.id(), at));
        String payload;
        try { payload = json.writeValueAsString(snapshot); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Unable to freeze open publication"); }
        if(snapshot.recipients().size()>500||payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>60000)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"ONCALL_OPEN_PUBLICATION_TOO_LARGE","发布候选或快照载荷超出上限，未保存请求或通知");
        jdbc.sql("""
                INSERT INTO oncall_open_handoff_publication(handoff_id,event_version,captured_at,snapshot_json)
                VALUES (:id,0,:at,:payload)
                """).param("id", row.id()).param("at", at).param("payload", payload).update();
        return snapshot;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public View get(long id) {
        var current = jdbc.sql("SELECT version,status FROM oncall_open_handoff WHERE id=:id").param("id", id)
                .query((rs,n) -> new State(rs.getInt(1),rs.getString(2))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"ONCALL_OPEN_HANDOFF_NOT_FOUND","开放接班请求不存在"));
        var payload = jdbc.sql("SELECT snapshot_json FROM oncall_open_handoff_publication WHERE handoff_id=:id")
                .param("id", id).query(String.class).optional();
        var at = now();
        if (payload.isEmpty()) return new View(at,current.version(),current.status(),false,null,List.of(),true);
        Snapshot saved;
        try { saved = json.readValue(payload.get(), Snapshot.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Invalid frozen open publication"); }
        if (saved.handoffId() != id || saved.eventVersion() != 0) throw new IllegalStateException("Invalid frozen open publication identity");
        var capturedIds = saved.recipients().stream().map(Recipient::userId).toList();
        var eligible = candidates(id, at).stream().map(Recipient::userId).filter(capturedIds::contains).toList();
        return new View(at,current.version(),current.status(),true,saved,eligible,true);
    }

    // One SQL statement checks the current plan, both accounts/members and the same
    // source/version/remaining whole-second interval used by a new claim. No global-role fallback.
    // Recheck immediately before a future send; this observation cannot be atomic with remote HTTP.
    private List<Recipient> candidates(long id, LocalDateTime at) {
        var nextSecond = at.getNano() == 0 ? at : at.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        return jdbc.sql("""
                SELECT m.user_id,m.version,u.display_name,u.role_code
                FROM oncall_open_handoff h JOIN oncall_schedule p ON p.id=h.schedule_id
                JOIN oncall_shift s ON s.id=h.source_shift_id
                JOIN oncall_schedule_member m ON m.schedule_id=h.schedule_id
                JOIN sys_user u ON u.id=m.user_id
                WHERE h.id=:id AND h.status='OPEN' AND p.active=TRUE AND m.user_id<>h.requester_id AND %s
                  AND EXISTS (SELECT 1 FROM oncall_schedule_member m JOIN sys_user u ON u.id=m.user_id
                    WHERE m.schedule_id=h.schedule_id AND m.user_id=h.requester_id AND %s)
                  AND s.schedule_id=h.schedule_id AND s.user_id=h.requester_id AND s.version=h.source_version
                  AND s.override_flag=FALSE AND s.cancelled_at IS NULL
                  AND s.starts_at<=CASE WHEN h.starts_at>:next THEN h.starts_at ELSE :next END
                  AND s.ends_at>=h.ends_at AND h.ends_at>CASE WHEN h.starts_at>:next THEN h.starts_at ELSE :next END
                  AND NOT EXISTS (SELECT 1 FROM oncall_shift x WHERE x.schedule_id=h.schedule_id
                    AND x.id<>h.source_shift_id AND x.cancelled_at IS NULL AND x.starts_at<h.ends_at
                    AND x.ends_at>CASE WHEN h.starts_at>:next THEN h.starts_at ELSE :next END)
                ORDER BY m.user_id
                """.formatted(OnCallPlanMembershipService.RESPONSE_ELIGIBILITY,OnCallPlanMembershipService.RESPONSE_ELIGIBILITY))
                .param("id",id).param("next",nextSecond).query((rs,n) -> new Recipient(rs.getLong(1),rs.getInt(2),rs.getString(3),rs.getString(4))).list();
    }

    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single(); }
    private record State(int version,String status) {}
    public record Recipient(long userId,int memberVersion,String displayName,String roleCode) {}
    public record Snapshot(long handoffId,long scheduleId,long sourceShiftId,int sourceVersion,long requesterId,
                           LocalDateTime startsAt,LocalDateTime endsAt,int eventVersion,LocalDateTime capturedAt,
                           String timeBasis,List<Recipient> recipients) {
        public Snapshot { recipients = List.copyOf(recipients); }
    }
    public record View(LocalDateTime databaseNow,int currentRequestVersion,String currentRequestStatus,
                       boolean publicationSnapshotAvailable,Snapshot publication,List<Long> eligibleOriginalRecipientIds,
                       boolean deliveryImplemented) {}
}
