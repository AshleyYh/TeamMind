package com.teammind.ai.api;

import java.util.HashSet;
import java.util.Set;

/**
 * 流式回答的引用过滤器。
 *
 * <p>流式输出已经发送的文本无法撤回，因此不能像非流式回答那样整段拒绝。
 * 这里只缓存可能构成 [n] 的最短片段，遇到非法编号时丢弃标记，保留其余文本，
 * 并记录被移除的数量供调用方写入警告。</p>
 */
public class CitationStreamSanitizer {
    /** 超过该长度仍未闭合的 [ 视为普通文本，避免无限缓存。 */
    private static final int MAX_MARKER_LENGTH = 12;

    private final int evidenceCount;
    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder output = new StringBuilder();
    private final Set<Integer> cited = new HashSet<>();
    private int removed;

    public CitationStreamSanitizer(int evidenceCount) {
        this.evidenceCount = Math.max(0, evidenceCount);
    }

    /** 接收模型增量，返回当前已经可以安全发送的文本。 */
    public synchronized String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        buffer.append(delta);
        StringBuilder emitted = new StringBuilder();
        int cursor = 0;
        while (cursor < buffer.length()) {
            int open = buffer.indexOf("[", cursor);
            if (open < 0) {
                emitted.append(buffer, cursor, buffer.length());
                cursor = buffer.length();
                break;
            }
            emitted.append(buffer, cursor, open);
            int close = buffer.indexOf("]", open + 1);
            if (close < 0) {
                if (buffer.length() - open > MAX_MARKER_LENGTH) {
                    emitted.append('[');
                    cursor = open + 1;
                    continue;
                }
                // 可能是一个跨增量的引用标记，保留到下一次 feed。
                cursor = open;
                break;
            }
            String inner = buffer.substring(open + 1, close);
            if (inner.chars().allMatch(Character::isDigit) && !inner.isEmpty()) {
                int index = parseIndex(inner);
                if (index >= 1 && index <= evidenceCount) {
                    emitted.append('[').append(inner).append(']');
                    cited.add(index);
                } else {
                    // 越界或溢出编号直接移除，避免把不存在的证据发给用户。
                    removed++;
                }
            } else {
                emitted.append('[').append(inner).append(']');
            }
            cursor = close + 1;
        }
        buffer.delete(0, cursor);
        output.append(emitted);
        return emitted.toString();
    }

    /** 结束流式输出，返回剩余未处理文本并清空缓存。 */
    public synchronized String flush() {
        if (buffer.isEmpty()) {
            return "";
        }
        String remaining = buffer.toString();
        buffer.setLength(0);
        output.append(remaining);
        return remaining;
    }

    public synchronized String output() {
        return output.toString();
    }

    public synchronized Set<Integer> cited() {
        return Set.copyOf(cited);
    }

    public synchronized int removed() {
        return removed;
    }

    private int parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
