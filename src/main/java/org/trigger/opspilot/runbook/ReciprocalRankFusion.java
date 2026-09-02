package org.trigger.opspilot.runbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReciprocalRankFusion {
    static final String ENGINE = "HYBRID_RRF_V1";

    private ReciprocalRankFusion() {
    }

    static List<FusedDocument> fuse(List<Long> lexicalRanking, List<Long> semanticRanking,
                                    int rankConstant, int topK) {
        Map<Long, Integer> lexicalRanks = ranks(lexicalRanking);
        Map<Long, Integer> semanticRanks = ranks(semanticRanking);
        Set<Long> chunkIds = new LinkedHashSet<>(lexicalRanking);
        chunkIds.addAll(semanticRanking);
        List<FusedDocument> fused = new ArrayList<>();
        for (long chunkId : chunkIds) {
            Integer lexicalRank = lexicalRanks.get(chunkId);
            Integer semanticRank = semanticRanks.get(chunkId);
            double score = (lexicalRank == null ? 0 : 1.0 / (rankConstant + lexicalRank))
                    + (semanticRank == null ? 0 : 1.0 / (rankConstant + semanticRank));
            fused.add(new FusedDocument(chunkId, round(score), lexicalRank, semanticRank));
        }
        return fused.stream()
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.score(), left.score());
                    return byScore != 0 ? byScore : Long.compare(left.chunkId(), right.chunkId());
                })
                .limit(topK)
                .toList();
    }

    private static Map<Long, Integer> ranks(List<Long> ranking) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (int index = 0; index < ranking.size(); index++) {
            ranks.putIfAbsent(ranking.get(index), index + 1);
        }
        return ranks;
    }

    private static double round(double score) {
        return Math.round(score * 1_000_000.0) / 1_000_000.0;
    }

    record FusedDocument(long chunkId, double score, Integer lexicalRank, Integer semanticRank) {
    }
}
