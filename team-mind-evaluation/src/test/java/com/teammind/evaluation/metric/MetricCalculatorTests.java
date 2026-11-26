package com.teammind.evaluation.metric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricCalculatorTests {
    @Test
    void 全部命中时三项指标为一() {
        var metrics = MetricCalculator.score(List.of(true, true), 2);

        assertThat(metrics.recallAtK()).isEqualTo(1);
        assertThat(metrics.mrr()).isEqualTo(1);
        assertThat(metrics.ndcgAtK()).isEqualTo(1);
    }

    @Test
    void 首个命中在第二名时MRR为一半() {
        var metrics = MetricCalculator.score(List.of(false, true, false), 1);

        assertThat(metrics.recallAtK()).isEqualTo(1);
        assertThat(metrics.mrr()).isEqualTo(0.5);
        assertThat(metrics.ndcgAtK()).isCloseTo(0.6309, within(0.001));
    }

    @Test
    void 部分召回和空结果() {
        var partial = MetricCalculator.score(List.of(true, false, true), 3);
        var empty = MetricCalculator.score(List.of(), 2);

        assertThat(partial.recallAtK()).isCloseTo(2.0 / 3, within(0.0001));
        assertThat(empty.recallAtK()).isZero();
        assertThat(empty.mrr()).isZero();
        assertThat(empty.ndcgAtK()).isZero();
    }

    @Test
    void 平均值计算忽略空列表() {
        var average = MetricCalculator.average(List.of(
                new MetricCalculator.RetrievalMetrics(1.0, 1.0, 1.0),
                new MetricCalculator.RetrievalMetrics(0.5, 0.5, 0.5)));

        assertThat(average.recallAtK()).isEqualTo(0.75);
        assertThat(MetricCalculator.averageDouble(List.of(1.0, 0.0, 0.5))).isEqualTo(0.5);
        assertThat(MetricCalculator.average(List.of())).isEqualTo(
                new MetricCalculator.RetrievalMetrics(0, 0, 0));
    }
}
