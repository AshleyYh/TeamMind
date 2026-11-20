package com.teammind.knowledge.infrastructure.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTokenizerTests {
    private final TextTokenizer tokenizer = new TextTokenizer();

    @Test
    void 中文二元词和代码标识符可以共同检索() {
        assertThat(tokenizer.tokenize("HikariCP：最大连接数 ERR-42。"))
                .contains("hikaricp", "err", "42", "最大", "大连", "连接", "接数")
                .doesNotContain("-");
    }

    @Test
    void 空内容与标点不产生查询运算符() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize(":(|)'*!")).isEmpty();
    }
}
