package org.trigger.opspilot.oncall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.util.UUID;

/** An explicit human operation requeues the same frozen event, never sends or claims a shift. */
@Service
public class OnCallOpenNotificationRedrive {
    private final JdbcClient jdbc;
    private final OnCallPlanMembershipService members;
    private final OnCallOpenHandoffRecipients recipients;
    private final OnCallOpenNotifications notifications;
    private final OnCallOpenNotificationProperties properties;
    private final AuditService audit;

    public OnCallOpenNotificationRedrive(JdbcClient jdbc,OnCallPlanMembershipService members,OnCallOpenHandoffRecipients recipients,
                                        OnCallOpenNotifications notifications,OnCallOpenNotificationProperties properties,AuditService audit) {
        this.jdbc=jdbc;this.members=members;this.recipients=recipients;this.notifications=notifications;this.properties=properties;this.audit=audit;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result retry(long handoff,long notification,Command command,long actor,String ip) {
        String key=key(command.operationKey()),reason=text(command.reason());
        if(command.version()<0)throw invalid("须提供明确通知版本");
        var initial=jdbc.sql("""
                SELECT h.schedule_id,h.requester_id,n.recipient_id FROM oncall_open_handoff h
                JOIN oncall_open_handoff_notification n ON n.handoff_id=h.id WHERE h.id=:handoff AND n.id=:notification
                """).param("handoff",handoff).param("notification",notification)
                .query((rs,n)->new Scope(rs.getLong(1),rs.getLong(2),rs.getLong(3))).optional()
                .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"ONCALL_OPEN_NOTIFICATION_NOT_FOUND","此请求的通知不存在"));
        // Match publication/claim/membership lock order. Account lock also serializes one actor's keys across events.
        jdbc.sql("SELECT id FROM oncall_schedule WHERE id=:id FOR UPDATE").param("id",initial.schedule()).query(Long.class).single();
        jdbc.sql("SELECT id FROM oncall_open_handoff WHERE id=:id FOR UPDATE").param("id",handoff).query(Long.class).single();
        members.lockAccounts(actor,initial.owner(),initial.recipient());
        members.requireManager(initial.schedule(),actor); // ADMIN override is the existing plan-management contract; OPS_MANAGER needs can_manage.
        var row=notifications.view(notification,true);
        var previous=jdbc.sql("SELECT * FROM oncall_open_notification_redrive WHERE actor_id=:actor AND operation_key=:key")
                .param("actor",actor).param("key",key).query(mapper).optional();
        if(previous.isPresent()) {
            var saved=previous.get();
            if(saved.handoffId()!=handoff||saved.notificationId()!=notification||saved.capturedVersion()!=command.version()||!saved.reason().equals(reason))
                throw conflict("ONCALL_OPEN_NOTIFICATION_OPERATION_KEY_REUSED","操作键已用于不同通知、版本或说明");
            return new Result(saved,row,true); // Original immutable receipt, separate current delivery; no eligibility rewrite or enqueue.
        }
        if(!properties.enabled())throw new ApiException(HttpStatus.CONFLICT,"ONCALL_OPEN_NOTIFICATION_DISABLED","发送适配器已关闭，未重新排队");
        if(!row.status().equals("FAILED")||row.version()!=command.version())
            throw conflict("ONCALL_OPEN_NOTIFICATION_VERSION_CONFLICT","只有当前 FAILED 通知可明确重投");
        if(row.version()>Integer.MAX_VALUE-1-2*properties.maxAttempts()||row.attempts()>Integer.MAX_VALUE-properties.maxAttempts())
            throw conflict("ONCALL_OPEN_NOTIFICATION_COUNTER_EXHAUSTED","持久计数预算不足，未重新排队");
        if(!recipients.get(handoff).eligibleOriginalRecipientIds().contains(row.recipientId()))
            throw conflict("ONCALL_OPEN_NOTIFICATION_INELIGIBLE","原候选当前不满足发送条件，未重新排队或改投");
        var at=jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();
        int changed=jdbc.sql("""
                UPDATE oncall_open_handoff_notification SET status='PENDING',version=version+1,cycle_start_attempts=attempts,
                  next_attempt_at=:now,lease_token=NULL,lease_until=NULL
                WHERE id=:id AND status='FAILED' AND version=:version
                """).param("now",at).param("id",notification).param("version",command.version()).update();
        if(changed!=1)throw conflict("ONCALL_OPEN_NOTIFICATION_VERSION_CONFLICT","通知已变化，未重新排队");
        jdbc.sql("""
                INSERT INTO oncall_open_notification_redrive(notification_id,handoff_id,actor_id,operation_key,captured_version,queued_version,
                  prior_attempts,prior_http_status,prior_error_code,delivery_key,reason,accepted_at)
                VALUES (:id,:handoff,:actor,:key,:version,:queued,:attempts,:http,:error,:delivery,:reason,:now)
                """).param("id",notification).param("handoff",handoff).param("actor",actor).param("key",key).param("version",row.version())
                .param("queued",row.version()+1).param("attempts",row.attempts()).param("http",row.lastHttpStatus()).param("error",row.lastErrorCode())
                .param("delivery",row.deliveryKey()).param("reason",reason).param("now",at).update();
        audit.recordAs(actor,ip,"ONCALL_OPEN_NOTIFICATION_REDRIVE","oncall_open_handoff_notification",notification,
                "FAILED v"+row.version()+" → PENDING v"+(row.version()+1)+"；累计尝试保留 "+row.attempts()+"；"+reason);
        var receipt=jdbc.sql("SELECT * FROM oncall_open_notification_redrive WHERE actor_id=:actor AND operation_key=:key")
                .param("actor",actor).param("key",key).query(mapper).single();
        return new Result(receipt,notifications.view(notification,false),false);
    }
    private static String key(String value){try{var key=UUID.fromString(value).toString();if(!key.equals(value))throw new IllegalArgumentException();return key;}
        catch(NullPointerException|IllegalArgumentException error){throw invalid("操作键须为规范UUID");}}
    private static String text(String value){if(value==null||value.isBlank()||value.length()>500)throw invalid("说明须为1–500字");return value.strip();}
    private static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"ONCALL_OPEN_NOTIFICATION_INVALID_REDRIVE",message);}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private record Scope(long schedule,long owner,long recipient) {}
    private static final RowMapper<Receipt> mapper=(rs,n)->new Receipt(rs.getLong("id"),rs.getLong("notification_id"),rs.getLong("handoff_id"),rs.getLong("actor_id"),
            rs.getString("operation_key"),rs.getInt("captured_version"),rs.getInt("queued_version"),rs.getInt("prior_attempts"),rs.getObject("prior_http_status",Integer.class),
            rs.getString("prior_error_code"),rs.getString("delivery_key"),rs.getString("reason"),rs.getObject("accepted_at",LocalDateTime.class));
    public record Command(int version,String operationKey,String reason) {}
    public record Receipt(long id,long notificationId,long handoffId,long actorId,String operationKey,int capturedVersion,int queuedVersion,int priorAttempts,
                          Integer priorHttpStatus,String priorErrorCode,String deliveryKey,String reason,LocalDateTime acceptedAt) {}
    public record Result(Receipt receipt,OnCallOpenNotifications.View currentDelivery,boolean replayed) {}
}
