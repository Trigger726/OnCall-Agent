package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One durable original-publication event per captured recipient. A 2xx is never a human claim. */
@Service
public class OnCallOpenNotifications {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final OnCallOpenHandoffRecipients recipients;
    private final OnCallOpenNotificationProperties properties;
    private final HttpClient client;
    private final URI destination;

    public OnCallOpenNotifications(JdbcClient jdbc,ObjectMapper json,OnCallOpenHandoffRecipients recipients,OnCallOpenNotificationProperties properties) {
        this.jdbc=jdbc;this.json=json;this.recipients=recipients;this.properties=properties;
        if(!properties.enabled()){client=null;destination=null;return;}
        URI uri;
        try{uri=URI.create(properties.url());}catch(RuntimeException e){throw new IllegalArgumentException("Invalid open notification configuration");}
        boolean secure="https".equalsIgnoreCase(uri.getScheme()),local="http".equalsIgnoreCase(uri.getScheme())&&("localhost".equalsIgnoreCase(uri.getHost())||"127.0.0.1".equals(uri.getHost()));
        if((!secure&&!local)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||properties.token()==null||properties.token().isBlank()||properties.token().contains("\r")||properties.token().contains("\n")
                ||!bounded(properties.connectTimeout())||!bounded(properties.readTimeout())||!bounded(properties.lease())
                ||!bounded(properties.retryBaseDelay())||!bounded(properties.retryMaxDelay())
                ||properties.lease().compareTo(properties.connectTimeout().plus(properties.readTimeout()))<=0
                ||properties.retryBaseDelay().compareTo(properties.retryMaxDelay())>0
                ||properties.maxAttempts()<1||properties.maxAttempts()>10||properties.batchSize()<1||properties.batchSize()>100)
            throw new IllegalArgumentException("Invalid open notification configuration");
        destination=uri;client=HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static boolean bounded(Duration value){return value!=null&&value.compareTo(Duration.ofMillis(1))>=0&&value.compareTo(Duration.ofDays(1))<=0;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(OnCallOpenHandoffRecipients.Snapshot saved) {
        if(!properties.enabled())return; // No enabling-time backfill and no enqueue on original-key acknowledgement.
        for(var recipient:saved.recipients()) {
            var snapshot=new Snapshot("ONCALL_OPEN_PUBLISHED",saved.handoffId(),saved.eventVersion(),saved.scheduleId(),saved.sourceShiftId(),
                    saved.sourceVersion(),saved.requesterId(),recipient.userId(),recipient.memberVersion(),recipient.displayName(),
                    saved.startsAt().toString(),saved.endsAt().toString(),saved.capturedAt().toString(),saved.timeBasis(),true,true);
            String payload;
            try{payload=json.writeValueAsString(snapshot);}catch(JsonProcessingException e){throw new IllegalStateException("Unable to freeze open notification");}
            jdbc.sql("""
                    INSERT INTO oncall_open_handoff_notification(handoff_id,event_version,recipient_id,delivery_key,payload_json)
                    VALUES (:id,0,:recipient,:key,:payload)
                    """).param("id",saved.handoffId()).param("recipient",recipient.userId()).param("key",UUID.randomUUID().toString()).param("payload",payload).update();
        }
    }

    public ListView list(long handoffId) {
        recipients.get(handoffId); // Same authenticated historical-read contract and real missing-id 404.
        return new ListView(true,properties.enabled(),now(),jdbc.sql("SELECT * FROM oncall_open_handoff_notification WHERE handoff_id=:id ORDER BY id")
                .param("id",handoffId).query(mapper).list());
    }

    @Transactional(propagation=Propagation.NEVER)
    public int dispatchDue() {
        if(!properties.enabled())return 0;
        var ids=jdbc.sql("""
                SELECT id FROM oncall_open_handoff_notification WHERE
                  (status='PENDING' AND next_attempt_at<=:now) OR (status='CLAIMED' AND lease_until<=:now)
                ORDER BY id LIMIT :limit
                """).param("now",now()).param("limit",properties.batchSize()).query(Long.class).list();
        int count=0;
        for(long id:ids) {
            if(Thread.currentThread().isInterrupted())break;
            var at=now();String token=UUID.randomUUID().toString();
            int exhausted=jdbc.sql("""
                    UPDATE oncall_open_handoff_notification SET status='FAILED',version=version+1,
                      last_error_code=CASE WHEN status='CLAIMED' THEN 'LEASE_EXPIRED' ELSE 'ATTEMPTS_EXHAUSTED' END,lease_token=NULL,lease_until=NULL
                    WHERE id=:id AND attempts>=:max AND ((status='CLAIMED' AND lease_until<=:now) OR (status='PENDING' AND next_attempt_at<=:now))
                    """).param("id",id).param("now",at).param("max",properties.maxAttempts()).update();
            if(exhausted==1){count++;continue;}
            int claimed=jdbc.sql("""
                    UPDATE oncall_open_handoff_notification SET status='CLAIMED',version=version+1,attempts=attempts+1,lease_token=:token,lease_until=:until
                    WHERE id=:id AND attempts<:max AND ((status='PENDING' AND next_attempt_at<=:now) OR (status='CLAIMED' AND lease_until<=:now))
                    """).param("id",id).param("max",properties.maxAttempts()).param("now",at).param("token",token).param("until",at.plus(properties.lease())).update();
            if(claimed!=1)continue;
            count++;deliver(view(id),token);
        }
        return count;
    }

    private void deliver(View row,String token) {
        // Observation immediately before building the real HTTP request; no SQL transaction is held over the network.
        // Revocation after this observation cannot be made atomic with a third-party HTTP receiver.
        if(!recipients.get(row.handoffId()).eligibleOriginalRecipientIds().contains(row.recipientId())){
            settle(row,token,"SKIPPED",null,"NO_LONGER_ELIGIBLE");return;
        }
        var sendUntil=now().plus(properties.connectTimeout()).plus(properties.readTimeout());
        var payload=jdbc.sql("SELECT payload_json FROM oncall_open_handoff_notification WHERE id=:id AND status='CLAIMED' AND lease_token=:token AND lease_until>:until")
                .param("id",row.id()).param("token",token).param("until",sendUntil).query(String.class).optional();
        if(payload.isEmpty())return;
        Integer status=null;String code=null;
        try {
            var request=HttpRequest.newBuilder(destination).timeout(properties.connectTimeout().plus(properties.readTimeout()))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+properties.token())
                    .header("Idempotency-Key","oncall-open-notification:"+row.deliveryKey()).POST(HttpRequest.BodyPublishers.ofString(payload.get())).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){status=response.statusCode();} // No unbounded response body or sensitive provider text retained.
        }catch(InterruptedException e){Thread.currentThread().interrupt();code="TRANSPORT_INTERRUPTED";}
        catch(Exception e){code="TRANSPORT_ERROR";}
        if(status!=null&&status>=200&&status<300){settle(row,token,"DELIVERED",status,null);return;}
        if(code==null)code="HTTP_"+status;
        boolean permanent=status!=null&&status>=300&&status<500&&status!=429;
        settle(row,token,permanent||row.attempts()>=properties.maxAttempts()?"FAILED":"PENDING",status,code);
    }

    private void settle(View row,String token,String state,Integer http,String code) {
        var at=now();var delay=properties.retryBaseDelay();
        for(int i=1;i<row.attempts()&&delay.compareTo(properties.retryMaxDelay())<0;i++)delay=delay.multipliedBy(2);
        if(delay.compareTo(properties.retryMaxDelay())>0)delay=properties.retryMaxDelay();
        jdbc.sql("""
                UPDATE oncall_open_handoff_notification SET status=:state,version=version+1,last_http_status=:http,last_error_code=:code,
                  next_attempt_at=:next,lease_token=NULL,lease_until=NULL,delivered_at=CASE WHEN :state='DELIVERED' THEN :now ELSE delivered_at END
                WHERE id=:id AND status='CLAIMED' AND lease_token=:token AND lease_until>:now
                """).param("id",row.id()).param("token",token).param("state",state).param("http",http).param("code",code)
                .param("now",at).param("next",at.plus(delay)).update(); // A stale lease can never overwrite a newer worker's receipt.
    }
    private View view(long id){return jdbc.sql("SELECT * FROM oncall_open_handoff_notification WHERE id=:id").param("id",id).query(mapper).single();}
    private LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private static final RowMapper<View> mapper=(rs,n)->new View(rs.getLong("id"),rs.getLong("handoff_id"),rs.getInt("event_version"),rs.getLong("recipient_id"),
            rs.getString("delivery_key"),rs.getString("status"),rs.getInt("version"),rs.getInt("attempts"),rs.getObject("next_attempt_at",LocalDateTime.class),
            rs.getObject("lease_until",LocalDateTime.class),rs.getObject("last_http_status",Integer.class),rs.getString("last_error_code"),rs.getObject("delivered_at",LocalDateTime.class));
    private record Snapshot(String eventType,long handoffId,int eventVersion,long scheduleId,long sourceShiftId,int sourceVersion,long requesterId,
                            long recipientId,int recipientMemberVersion,String recipientName,String startsAt,String endsAt,String capturedAt,String timeBasis,
                            boolean humanConfirmationRequired,boolean currentCoverageMustBeReadSeparately) {}
    public record View(long id,long handoffId,int eventVersion,long recipientId,String deliveryKey,String status,int version,int attempts,
                       LocalDateTime nextAttemptAt,LocalDateTime leaseUntil,Integer lastHttpStatus,String lastErrorCode,LocalDateTime deliveredAt) {}
    public record ListView(boolean adapterImplemented,boolean enabled,LocalDateTime databaseNow,List<View> deliveries) {}
}
