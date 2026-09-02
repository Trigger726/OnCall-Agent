package org.trigger.opspilot.observability;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;

import java.time.LocalDateTime;

@Component
public class ObservationQueryContextFactory {
    private final JdbcClient jdbcClient;

    public ObservationQueryContextFactory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ObservationQueryContext from(IncidentService.IncidentDetail incident) {
        String resourceCode = jdbcClient.sql("SELECT resource_code FROM cmdb_resource WHERE id = :id")
                .param("id", incident.incident().resourceId()).query(String.class).single();
        LocalDateTime start = incident.incident().createdAt().minusMinutes(30);
        LocalDateTime end = incident.incident().updatedAt().plusMinutes(30);
        return new ObservationQueryContext(incident.incident().id(), incident.incident().resourceId(),
                resourceCode, incident.incident().resourceName(), start, end);
    }

    public record ObservationQueryContext(long incidentId, long resourceId,
                                          String resourceCode, String resourceName,
                                          LocalDateTime start, LocalDateTime end) {
    }
}
