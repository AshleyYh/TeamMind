package com.teammind.knowledge.infrastructure.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 面向中英文研发文档的轻量分词：保留完整英文/代码标识符、数字和中文二元词。 */
@Component
public class TextTokenizer {
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        StringBuilder chinese = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isAsciiWord(codePoint)) {
                flushChinese(chinese, tokens);
                word.appendCodePoint(codePoint);
            } else if (isChinese(codePoint)) {
                flushWord(word, tokens);
                chinese.appendCodePoint(codePoint);
            } else {
                flushWord(word, tokens);
                flushChinese(chinese, tokens);
            }
        }
        flushWord(word, tokens);
        flushChinese(chinese, tokens);
        return List.copyOf(tokens);
    }

    private void flushWord(StringBuilder word, List<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private void flushChinese(StringBuilder chinese, List<String> tokens) {
        if (chinese.isEmpty()) {
            return;
        }
        if (chinese.codePointCount(0, chinese.length()) == 1) {
            tokens.add(chinese.toString());
        } else {
            // 按 code point 构造二元词，避免增补平面汉字被切成半个代理项。
            for (int offset = 0, next = chinese.offsetByCodePoints(offset, 1);
                    next < chinese.length();
                    offset = next, next = chinese.offsetByCodePoints(offset, 1)) {
                tokens.add(chinese.substring(offset, chinese.offsetByCodePoints(offset, 2)));
            }
        }
        chinese.setLength(0);
    }

    private boolean isAsciiWord(int codePoint) {
        return codePoint < 128 && Character.isLetterOrDigit(codePoint);
    }

    private boolean isChinese(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
