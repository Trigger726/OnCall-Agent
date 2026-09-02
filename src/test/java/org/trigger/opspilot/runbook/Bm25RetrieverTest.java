package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25RetrieverTest {
    @Test
    void shouldTokenizeChineseBigramsAndRankTheRelevantChunk() {
        assertThat(RunbookTextProcessor.tokenize("连接池 pending"))
                .contains("连", "连接", "接池", "pending");

        List<Bm25Retriever.ScoredDocument> results = Bm25Retriever.search(
                "Redis 连接池 pending",
                List.of(
                        new Bm25Retriever.Document(1, "认证 token 兼容错误"),
                        new Bm25Retriever.Document(2, "Redis 连接池 active idle pending 慢命令"),
                        new Bm25Retriever.Document(3, "接口超时 P95 下游依赖")
                ), 3);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).document().chunkId()).isEqualTo(2);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void shouldSplitLongMarkdownIntoBoundedOverlappingChunks() {
        String markdown = "# 诊断\n" + "连接池排队。".repeat(300) + "\n# 恢复\n回滚配置并观察指标。";

        List<RunbookTextProcessor.ChunkDraft> chunks = RunbookTextProcessor.chunk(markdown);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(1_200));
        assertThat(chunks.get(0).heading()).isEqualTo("诊断");
        assertThat(chunks.get(chunks.size() - 1).heading()).isEqualTo("恢复");
    }
}
