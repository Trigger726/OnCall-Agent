package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Durable historical notifications. Neither a webhook receipt nor a retry decides a swap. */
@Service
public class OnCallSwapNotifications {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OnCallSwapNotificationProperties properties;
    private final URI destination;
    private final HttpClient client;

    public OnCallSwapNotifications(JdbcClient jdbc, ObjectMapper json, AuditService audit,
                                   OnCallSwapNotificationProperties properties) {
        this.jdbc = jdbc; this.json = json; this.audit = audit; this.properties = properties;
        if (!properties.enabled()) { destination = null; client = null; return; }
        URI uri;
        try { uri = URI.create(properties.url()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid swap notification configuration"); }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean local = "http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if ((!secure && !local) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getQuery() != null || properties.token() == null || properties.token().isBlank()
                || properties.token().contains("\r") || properties.token().contains("\n")
                || !bounded(properties.connectTimeout()) || !bounded(properties.readTimeout())
                || !bounded(properties.lease()) || !bounded(properties.retryBaseDelay()) || !bounded(properties.retryMaxDelay())
                || properties.retryBaseDelay().compareTo(properties.retryMaxDelay()) > 0
                || properties.lease().compareTo(properties.connectTimeout().plus(properties.readTimeout())) <= 0
                || properties.maxAttempts() < 1 || properties.maxAttempts() > 10 || properties.batchSize() < 1 || properties.batchSize() > 100) {
            throw new IllegalArgumentException("Invalid swap notification configuration");
        }
        destination = uri;
        client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static boolean bounded(Duration value) { return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(Duration.ofDays(1)) <= 0; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OnCallSwapService.View row) {
        if (!properties.enabled()) return; // Enabling later does not backfill historical events.
        List<Long> recipients = row.status().equals("PENDING") ? List.of(row.targetUserId()) : List.of(row.requesterId(), row.targetUserId());
        for (long recipient : recipients) {
            String name = jdbc.sql("SELECT display_name FROM sys_user WHERE id=:id").param("id", recipient).query(String.class).single();
            Snapshot snapshot = new Snapshot("ONCALL_SWAP_" + row.status(), row.id(), row.version(), row.status(), recipient, name,
                    row.requesterId(), row.targetUserId(), row.firstScheduleId(), row.firstShiftId(), row.firstStartsAt().toString(), row.firstEndsAt().toString(),
                    row.secondScheduleId(), row.secondShiftId(), row.secondStartsAt().toString(), row.secondEndsAt().toString(),
                    (row.decidedAt() == null ? row.createdAt() : row.decidedAt()).toString(), "DATABASE_SESSION_LOCAL", true, true);
            String payload;
            try { payload = json.writeValueAsString(snapshot); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Unable to freeze swap notification"); }
            jdbc.sql("""
                    INSERT INTO oncall_swap_notification(swap_id,event_version,event_status,recipient_id,recipient_name,delivery_key,payload_json)
                    VALUES (:swap,:version,:status,:recipient,:name,:key,:payload)
                    """).param("swap", row.id()).param("version", row.version()).param("status", row.status()).param("recipient", recipient)
                    .param("name", name).param("key", UUID.randomUUID().toString()).param("payload", payload).update();
        }
    }

    public ListView list(long swapId) {
        requireSwap(swapId, false);
        return new ListView(properties.enabled(), now(), jdbc.sql("SELECT * FROM oncall_swap_notification WHERE swap_id=:id ORDER BY id")
                .param("id", swapId).query(mapper).list()); // At most one request plus two decision notifications.
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View retry(long swapId, long id, int version, String reason, long actor, String ip) {
        if (!properties.enabled()) throw conflict("ONCALL_SWAP_NOTIFICATION_DISABLED", "换班通知未启用");
        if (version < 0 || reason == null || reason.isBlank() || reason.length() > 500) throw new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_SWAP_NOTIFICATION_INVALID", "请提供捕获版本及1–500字说明");
        var swap = requireSwap(swapId, true);
        String role = jdbc.sql("SELECT role_code FROM sys_user WHERE id=:id AND status='ACTIVE' FOR UPDATE").param("id", actor).query(String.class).optional().orElse("");
        if (!List.of("ADMIN", "OPS_MANAGER").contains(role) && !(role.equals("ON_CALL") && (actor == swap.requester() || actor == swap.target()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ONCALL_SWAP_NOTIFICATION_FORBIDDEN", "只有管理角色或本次实际参与者可重试通知");
        }
        View row = jdbc.sql("SELECT * FROM oncall_swap_notification WHERE id=:id AND swap_id=:swap FOR UPDATE")
                .param("id", id).param("swap", swapId).query(mapper).optional().orElseThrow(OnCallSwapNotifications::missing);
        String clean = reason.strip();
        var previous = jdbc.sql("SELECT last_retry_from_version,last_retry_reason FROM oncall_swap_notification WHERE id=:id")
                .param("id", id).query((rs,n) -> new Retry(rs.getObject(1,Integer.class),rs.getString(2))).single();
        if (previous.version() != null && previous.version() == version && clean.equals(previous.reason())) return row;
        if (!row.status().equals("FAILED") || row.version() != version || ineligible(row) != null) throw conflict("ONCALL_SWAP_NOTIFICATION_NOT_RETRYABLE", "通知已变化或已失效，请刷新核对，不自动重试");
        jdbc.sql("""
                UPDATE oncall_swap_notification SET status='PENDING',version=version+1,attempts=0,next_attempt_at=:now,
                  last_http_status=NULL,last_error_code=NULL,lease_token=NULL,lease_until=NULL,
                  last_retry_from_version=:version,last_retry_reason=:reason WHERE id=:id
                """).param("id", id).param("version", version).param("reason", clean).param("now", now()).update();
        audit.recordAs(actor, ip, "ONCALL_SWAP_NOTIFICATION_RETRY", "ONCALL_SWAP_NOTIFICATION", id, clean);
        return view(id);
    }

    @Transactional(propagation = Propagation.NEVER)
    public int dispatchDue() {
        if (!properties.enabled()) return 0;
        var ids = jdbc.sql("""
                SELECT id FROM oncall_swap_notification WHERE
                (status='PENDING' AND next_attempt_at<=:now) OR (status='CLAIMED' AND lease_until<=:now)
                ORDER BY id LIMIT :limit
                """).param("now", now()).param("limit", properties.batchSize()).query(Long.class).list();
        int claimed = 0;
        for (long id : ids) {
            if (Thread.currentThread().isInterrupted()) break;
            LocalDateTime at = now(); String token = UUID.randomUUID().toString();
            int exhausted = jdbc.sql("""
                    UPDATE oncall_swap_notification SET status='FAILED',version=version+1,last_error_code='LEASE_EXPIRED',lease_token=NULL,lease_until=NULL
                    WHERE id=:id AND status='CLAIMED' AND lease_until<=:now AND attempts>=:max
                    """).param("id",id).param("now",at).param("max",properties.maxAttempts()).update();
            if (exhausted == 1) { claimed++; continue; }
            int updated = jdbc.sql("""
                    UPDATE oncall_swap_notification SET status='CLAIMED',version=version+1,attempts=attempts+1,total_attempts=total_attempts+1,
                      lease_token=:token,lease_until=:until WHERE id=:id AND attempts<:max AND
                      ((status='PENDING' AND next_attempt_at<=:now) OR (status='CLAIMED' AND lease_until<=:now))
                    """).param("id",id).param("token",token).param("until",at.plus(properties.lease())).param("now",at).param("max",properties.maxAttempts()).update();
            if (updated != 1) continue;
            claimed++; deliver(view(id), token); // Claim immediately before this delivery, never an entire batch with one lease clock.
        }
        return claimed;
    }

    private void deliver(View row, String token) {
        String invalid = ineligible(row);
        if (invalid != null) { settle(row,token,"SKIPPED",null,invalid); return; }
        String payload = jdbc.sql("SELECT payload_json FROM oncall_swap_notification WHERE id=:id AND status='CLAIMED' AND lease_token=:token AND lease_until>:now")
                .param("id",row.id()).param("token",token).param("now",now()).query(String.class).optional().orElse(null);
        if (payload == null) return;
        Integer status = null; String code = null;
        try {
            var request = HttpRequest.newBuilder(destination).timeout(properties.connectTimeout().plus(properties.readTimeout()))
                    .header("Content-Type","application/json").header("Authorization","Bearer " + properties.token())
                    .header("Idempotency-Key","oncall-swap-notification:" + row.deliveryKey()).POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) { status = response.statusCode(); } // Close immediately; no unbounded response body or sensitive error text.
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); code = "TRANSPORT_INTERRUPTED"; }
        catch (Exception error) { code = "TRANSPORT_ERROR"; }
        if (status != null && status >= 200 && status < 300) settle(row,token,"DELIVERED",status,null);
        else {
            if (code == null) code = "HTTP_" + status;
            boolean permanent = status != null && status >= 300 && status < 500 && status != 429;
            settle(row,token,permanent || row.attempts() >= properties.maxAttempts() ? "FAILED" : "PENDING",status,code);
        }
    }

    private void settle(View row, String token, String state, Integer status, String code) {
        LocalDateTime at = now(); Duration delay = properties.retryBaseDelay();
        for (int i=1;i<row.attempts() && delay.compareTo(properties.retryMaxDelay())<0;i++) delay=delay.multipliedBy(2);
        if (delay.compareTo(properties.retryMaxDelay())>0) delay=properties.retryMaxDelay();
        jdbc.sql("""
                UPDATE oncall_swap_notification SET status=:state,version=version+1,last_http_status=:http,last_error_code=:code,
                  next_attempt_at=:next,lease_token=NULL,lease_until=NULL,
                  delivered_at=CASE WHEN :state='DELIVERED' THEN :now ELSE delivered_at END
                WHERE id=:id AND status='CLAIMED' AND lease_token=:token AND lease_until>:now
                """).param("id",row.id()).param("token",token).param("state",state).param("http",status).param("code",code)
                .param("now",at).param("next",at.plus(delay)).update(); // Expired/previous claims cannot publish receipts.
    }

    private String ineligible(View row) {
        var swap = requireSwap(row.swapId(), false);
        if (swap.version() != row.eventVersion() || !swap.status().equals(row.eventStatus())) return "EVENT_SUPERSEDED";
        boolean recipient = jdbc.sql("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER','ON_CALL')")
                .param("id",row.recipientId()).query(Long.class).optional().isPresent();
        if (!recipient) return "RECIPIENT_INELIGIBLE";
        if (row.eventStatus().equals("PENDING")) {
            boolean actionable = jdbc.sql("""
                    SELECT s.id FROM oncall_shift_swap s JOIN oncall_shift a ON a.id=s.first_shift_id JOIN oncall_shift b ON b.id=s.second_shift_id
                    JOIN oncall_schedule p ON p.id=s.first_schedule_id JOIN oncall_schedule q ON q.id=s.second_schedule_id
                    JOIN sys_user u ON u.id=s.requester_id WHERE s.id=:id AND p.active=TRUE AND q.active=TRUE AND u.status='ACTIVE'
                    AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') AND a.cancelled_at IS NULL AND b.cancelled_at IS NULL
                    AND a.override_flag=FALSE AND b.override_flag=FALSE AND a.version=s.first_version AND b.version=s.second_version
                    AND a.starts_at>:now AND b.starts_at>:now
                    AND NOT EXISTS (SELECT id FROM oncall_shift x WHERE x.schedule_id=a.schedule_id AND x.id<>a.id AND x.cancelled_at IS NULL AND x.starts_at<a.ends_at AND x.ends_at>a.starts_at)
                    AND NOT EXISTS (SELECT id FROM oncall_shift x WHERE x.schedule_id=b.schedule_id AND x.id<>b.id AND x.cancelled_at IS NULL AND x.starts_at<b.ends_at AND x.ends_at>b.starts_at)
                    """).param("id",row.swapId()).param("now",now()).query(Long.class).optional().isPresent();
            if (!actionable) return "REQUEST_NO_LONGER_ACTIONABLE";
        }
        return null; // Last DB observation before HTTP; not an atomic transaction with the remote receiver.
    }

    private SwapState requireSwap(long id, boolean lock) {
        return jdbc.sql("SELECT requester_id,target_user_id,version,status FROM oncall_shift_swap WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id",id).query((rs,n)->new SwapState(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getString(4))).optional().orElseThrow(OnCallSwapNotifications::missing);
    }
    private View view(long id) { return jdbc.sql("SELECT * FROM oncall_swap_notification WHERE id=:id").param("id",id).query(mapper).single(); }
    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single(); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"ONCALL_SWAP_NOTIFICATION_NOT_FOUND","换班或通知不存在"); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    private static final RowMapper<View> mapper=(rs,n)->new View(rs.getLong("id"),rs.getLong("swap_id"),rs.getInt("event_version"),rs.getString("event_status"),
            rs.getLong("recipient_id"),rs.getString("recipient_name"),rs.getString("delivery_key"),rs.getString("status"),rs.getInt("version"),
            rs.getInt("attempts"),rs.getInt("total_attempts"),rs.getObject("next_attempt_at",LocalDateTime.class),rs.getObject("lease_until",LocalDateTime.class),
            rs.getObject("last_http_status",Integer.class),rs.getString("last_error_code"),rs.getObject("delivered_at",LocalDateTime.class));
    private record SwapState(long requester,long target,int version,String status) {}
    private record Retry(Integer version,String reason) {}
    private record Snapshot(String eventType,long swapId,int eventVersion,String historicalStatus,long recipientId,String recipientName,
                            long requesterId,long targetUserId,long firstScheduleId,long firstShiftId,String firstStartsAt,String firstEndsAt,
                            long secondScheduleId,long secondShiftId,String secondStartsAt,String secondEndsAt,String eventOccurredAt,
                            String timeBasis,boolean humanConfirmationRequired,boolean currentCoverageMustBeReadSeparately) {}
    public record View(long id,long swapId,int eventVersion,String eventStatus,long recipientId,String recipientName,String deliveryKey,
                       String status,int version,int attempts,int totalAttempts,LocalDateTime nextAttemptAt,LocalDateTime leaseUntil,
                       Integer lastHttpStatus,String lastErrorCode,LocalDateTime deliveredAt) {}
    public record ListView(boolean enabled,LocalDateTime databaseNow,List<View> deliveries) {}
}
