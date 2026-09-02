package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {
    @Test
    void shouldFuseRanksWithoutMixingRawScoreScales() {
        List<ReciprocalRankFusion.FusedDocument> fused = ReciprocalRankFusion.fuse(
                List.of(10L, 20L, 30L), List.of(30L, 20L, 40L), 60, 4);

        assertThat(fused).extracting(ReciprocalRankFusion.FusedDocument::chunkId)
                .containsExactly(30L, 20L, 10L, 40L);
        assertThat(fused.get(0).lexicalRank()).isEqualTo(3);
        assertThat(fused.get(0).semanticRank()).isEqualTo(1);
        assertThat(fused.get(2).semanticRank()).isNull();
    }

    @Test
    void shouldBreakEqualFusionScoresByStableChunkId() {
        List<ReciprocalRankFusion.FusedDocument> fused = ReciprocalRankFusion.fuse(
                List.of(9L), List.of(7L), 60, 2);

        assertThat(fused).extracting(ReciprocalRankFusion.FusedDocument::chunkId)
                .containsExactly(7L, 9L);
    }
}
