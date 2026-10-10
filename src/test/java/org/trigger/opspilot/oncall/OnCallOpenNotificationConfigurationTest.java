package org.trigger.opspilot.oncall;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class OnCallOpenNotificationConfigurationTest {
    private OnCallOpenNotificationProperties properties(String url,String token,Duration connect,Duration read,Duration lease,int attempts,int batch){return new OnCallOpenNotificationProperties(true,url,token,connect,read,lease,Duration.ofSeconds(1),Duration.ofMinutes(1),attempts,batch);}
    private OnCallOpenNotifications create(OnCallOpenNotificationProperties p){return new OnCallOpenNotifications(null,null,null,p);}
    @Test void shouldRejectUnsafeDestinationAndHeaderWithoutExposingSecrets(){
        for(String url:new String[]{"http://example.com/deliver","http:///no-host","ftp://example.com","https://user:secret@example.com","https://example.com?q=secret","https://example.com#secret"})
            assertThatThrownBy(()->create(properties(url,"secret-channel",Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(3),2,20))).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid open notification configuration");
        for(String token:new String[]{"","secret\nheader","secret\rheader"})assertThatThrownBy(()->create(properties("https://example.com/deliver",token,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(3),2,20))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shouldRequireBoundedTimeoutsLongerLeaseAndFiniteAttemptsAndBatch(){
        for(var p:new OnCallOpenNotificationProperties[]{
                properties("https://example.com/deliver","secret",Duration.ZERO,Duration.ofSeconds(1),Duration.ofSeconds(3),2,20),
                properties("https://example.com/deliver","secret",Duration.ofSeconds(1),null,Duration.ofSeconds(3),2,20),
                properties("https://example.com/deliver","secret",Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(2),2,20),
                properties("https://example.com/deliver","secret",Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(3),11,20),
                properties("https://example.com/deliver","secret",Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(3),2,101)})assertThatThrownBy(()->create(p)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shouldNotCreateNetworkClientOrDispatchWhenDisabled(){var disabled=new OnCallOpenNotificationProperties(false,null,null,null,null,null,null,null,0,0);assertThat(create(disabled).dispatchDue()).isZero();}
}
