package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.trigger.opspilot.common.ApiException;

/** Persisted scope must never silently become a global search. UNKNOWN is reviewable but not evaluable. */
public final class RunbookRetrievalScope {
    private RunbookRetrievalScope() { }

    static View read(String type, String json, ObjectMapper mapper) {
        if ("UNKNOWN".equals(type)) return new View(type, null);
        if ("GLOBAL".equals(type) && json == null) return new View(type, null);
        if ("SCOPED".equals(type) && json != null) {
            try {
                var scope = mapper.readValue(json, RunbookService.SearchScope.class);
                if (scope != null) return new View(type, scope);
            } catch (Exception exception) {
                // Malformed / incomplete snapshots are not a license to run an unscoped evaluation.
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "RUNBOOK_RETRIEVAL_SCOPE_INVALID", "检索范围快照无效，不能按全库处理");
    }

    public record View(String type, RunbookService.SearchScope filter) { }
}
