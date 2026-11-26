package com.teammind.evaluation.metric;

import java.util.List;

/** 检索指标计算：Recall@K、MRR、NDCG@K，均基于二值相关性。 */
public final class MetricCalculator {

    private MetricCalculator() {
    }

    public static RetrievalMetrics score(List<Boolean> relevance, int relevantCount) {
        int k = relevance == null ? 0 : relevance.size();
        if (k == 0 || relevantCount <= 0) {
            return new RetrievalMetrics(0, 0, 0);
        }
        int hits = 0;
        int firstRelevant = -1;
        double dcg = 0;
        for (int index = 0; index < k; index++) {
            boolean relevant = Boolean.TRUE.equals(relevance.get(index));
            if (relevant) {
                hits++;
                if (firstRelevant < 0) {
                    firstRelevant = index;
                }
                dcg += 1.0 / log2(index + 2);
            }
        }
        double recall = Math.min(1.0, hits / (double) relevantCount);
        double mrr = firstRelevant < 0 ? 0 : 1.0 / (firstRelevant + 1);
        double idcg = 0;
        int ideal = Math.min(relevantCount, k);
        for (int index = 0; index < ideal; index++) {
            idcg += 1.0 / log2(index + 2);
        }
        double ndcg = idcg == 0 ? 0 : dcg / idcg;
        return new RetrievalMetrics(recall, mrr, ndcg);
    }

    public static RetrievalMetrics average(List<RetrievalMetrics> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return new RetrievalMetrics(0, 0, 0);
        }
        double recall = metrics.stream().mapToDouble(RetrievalMetrics::recallAtK).average().orElse(0);
        double mrr = metrics.stream().mapToDouble(RetrievalMetrics::mrr).average().orElse(0);
        double ndcg = metrics.stream().mapToDouble(RetrievalMetrics::ndcgAtK).average().orElse(0);
        return new RetrievalMetrics(recall, mrr, ndcg);
    }

    public static double averageDouble(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }

    public record RetrievalMetrics(double recallAtK, double mrr, double ndcgAtK) {
    }
}
