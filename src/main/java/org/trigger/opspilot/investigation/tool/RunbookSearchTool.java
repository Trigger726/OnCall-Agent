package org.trigger.opspilot.investigation.tool;

import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.runbook.RunbookService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RunbookSearchTool implements InvestigationTool {
    private final RunbookService runbookService;

    public RunbookSearchTool(RunbookService runbookService) {
        this.runbookService = runbookService;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String name() {
        return "runbook_retrieval";
    }

    @Override
    public String title() {
        return "检索标准处置手册";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        return search(incident, null);
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident, ToolContext context) {
        return search(incident, context.actorId());
    }

    private ToolResult search(IncidentService.IncidentDetail incident, Long actorId) {
        String query = incident.incident().title() + " " + incident.description() + " "
                + incident.alerts().stream().map(IncidentService.AlertView::title)
                .reduce("", (left, right) -> left + " " + right);
        RunbookService.SearchResponse response = runbookService.searchForUser(query, actorId, 3);
        List<ToolEvidence> evidence = response.results().stream()
                .map(result -> new ToolEvidence(
                        "RUNBOOK", result.citation(), result.publishedAt(),
                        result.title() + "；版本=v" + result.versionNo()
                                + "；章节=" + result.heading() + "；分数=" + result.score()
                                + "；" + result.excerpt()))
                .toList();
        String summary = evidence.isEmpty()
                ? "BM25 未命中可访问的标准处置手册，需要补充知识库或人工制定验证步骤。"
                : "BM25 从 " + response.candidateChunkCount() + " 个可访问分块中召回 "
                + evidence.size() + " 条 Runbook 证据，引用包含稳定键、版本和分块。";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", response.query());
        metadata.put("retrievalEngine", response.engine());
        metadata.put("topK", response.topK());
        metadata.put("candidateChunkCount", response.candidateChunkCount());
        metadata.put("citations", response.results().stream().map(RunbookService.SearchResult::citation).toList());
        return new ToolResult(summary, evidence, metadata);
    }
}
