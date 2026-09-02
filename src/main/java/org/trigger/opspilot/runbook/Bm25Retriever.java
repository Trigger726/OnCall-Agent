package org.trigger.opspilot.runbook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Bm25Retriever {
    static final String ENGINE = "BM25_LOCAL_V1";
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25Retriever() {
    }

    static List<ScoredDocument> search(String query, List<Document> documents, int topK) {
        if (documents.isEmpty()) return List.of();
        Set<String> queryTerms = new LinkedHashSet<>(RunbookTextProcessor.tokenize(query));
        if (queryTerms.isEmpty()) return List.of();

        List<List<String>> documentTokens = documents.stream()
                .map(document -> RunbookTextProcessor.tokenize(document.searchableText()))
                .toList();
        double averageLength = documentTokens.stream().mapToInt(List::size).average().orElse(1.0);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : queryTerms) {
            int frequency = 0;
            for (List<String> tokens : documentTokens) {
                if (tokens.contains(term)) frequency++;
            }
            documentFrequency.put(term, frequency);
        }

        String compactQuery = RunbookTextProcessor.compact(query);
        List<ScoredDocument> scored = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            List<String> tokens = documentTokens.get(index);
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) termFrequency.merge(token, 1, Integer::sum);
            double score = 0;
            for (String term : queryTerms) {
                int tf = termFrequency.getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (documents.size() - df + 0.5) / (df + 0.5));
                double denominator = tf + K1 * (1 - B + B * tokens.size() / averageLength);
                score += idf * tf * (K1 + 1) / denominator;
            }
            if (compactQuery.length() >= 2
                    && RunbookTextProcessor.compact(documents.get(index).searchableText()).contains(compactQuery)) {
                score += 1.5;
            }
            if (score > 0) scored.add(new ScoredDocument(documents.get(index), round(score)));
        }
        return scored.stream()
                .sorted((left, right) -> {
                    int byScore = Double.compare(right.score(), left.score());
                    return byScore != 0 ? byScore : Long.compare(left.document().chunkId(), right.document().chunkId());
                })
                .limit(topK)
                .toList();
    }

    private static double round(double score) {
        return Math.round(score * 10_000.0) / 10_000.0;
    }

    record Document(long chunkId, String searchableText) {
    }

    record ScoredDocument(Document document, double score) {
    }
}
