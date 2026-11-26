package com.teammind.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationStreamSanitizerTests {
    @Test
    void 跨增量的合法引用被保留() {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(2);

        StringBuilder emitted = new StringBuilder();
        emitted.append(sanitizer.accept("最大连接数是 30 ["));
        emitted.append(sanitizer.accept("1"));
        emitted.append(sanitizer.accept("]。"));

        assertThat(emitted.toString()).isEqualTo("最大连接数是 30 [1]。");
        assertThat(sanitizer.cited()).containsExactly(1);
        assertThat(sanitizer.removed()).isZero();
    }

    @Test
    void 越界引用编号被移除() {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(1);

        String emitted = sanitizer.accept("答案 [2]") + sanitizer.flush();

        assertThat(emitted).isEqualTo("答案 ");
        assertThat(sanitizer.cited()).isEmpty();
        assertThat(sanitizer.removed()).isEqualTo(1);
    }

    @Test
    void 非数字方括号保持原样() {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(3);

        String emitted = sanitizer.accept("参见 [附录A] 和 [1]") + sanitizer.flush();

        assertThat(emitted).isEqualTo("参见 [附录A] 和 [1]");
        assertThat(sanitizer.cited()).containsExactly(1);
    }

    @Test
    void 未闭合方括号按普通文本输出() {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(3);

        String emitted = sanitizer.accept("编号 [12") + sanitizer.flush();

        assertThat(emitted).isEqualTo("编号 [12");
        assertThat(sanitizer.cited()).isEmpty();
    }

    @Test
    void 零和溢出编号被移除() {
        CitationStreamSanitizer sanitizer = new CitationStreamSanitizer(1);

        String emitted = sanitizer.accept("[0] [1] [999999999999]") + sanitizer.flush();

        assertThat(emitted).isEqualTo(" [1] ");
        assertThat(sanitizer.removed()).isEqualTo(2);
    }
}
