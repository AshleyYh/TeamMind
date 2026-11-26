package com.teammind.ai.api;

import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 非流式回答的引用校验：出现越界或非数字引用时拒绝整次回答，避免伪造证据。 */
public final class CitationValidator {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private CitationValidator() {
    }

    public static Set<Integer> validate(String answer, int evidenceCount) {
        Set<Integer> indexes = new HashSet<>();
        if (answer == null || answer.isEmpty()) {
            return indexes;
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "模型返回了不存在的证据引用，已拒绝本次回答");
            }
            if (index < 1 || index > evidenceCount) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                        "模型返回了不存在的证据引用，已拒绝本次回答");
            }
            indexes.add(index);
        }
        return indexes;
    }
}
