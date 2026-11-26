package com.teammind.ai.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 工具参数白名单与类型校验，拒绝模型传入的未知字段和越界值。 */
public final class ToolArguments {
    private final Map<String, Object> values;

    private ToolArguments(Map<String, Object> values) {
        this.values = values;
    }

    public static ToolArguments of(Map<String, Object> values) {
        return new ToolArguments(values == null ? Map.of() : values);
    }

    public void rejectUnknown(String... allowed) {
        Set<String> allowedSet = Set.of(allowed);
        for (String key : values.keySet()) {
            if (!allowedSet.contains(key)) {
                throw new ToolArgumentException("工具参数包含未知字段：" + key);
            }
        }
    }

    public String requireString(String name, int maxLength) {
        String value = optionalString(name, maxLength);
        if (value == null || value.isBlank()) {
            throw new ToolArgumentException("缺少必填参数：" + name);
        }
        return value;
    }

    public String optionalString(String name, int maxLength) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ToolArgumentException("参数 " + name + " 必须是字符串");
        }
        String normalized = text.trim();
        if (normalized.length() > maxLength) {
            throw new ToolArgumentException("参数 " + name + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    public Integer optionalInt(String name, int min, int max) {
        Long value = optionalLong(name, min, max);
        return value == null ? null : value.intValue();
    }

    public Long requireLong(String name, long min, long max) {
        Long value = optionalLong(name, min, max);
        if (value == null) {
            throw new ToolArgumentException("缺少必填参数：" + name);
        }
        return value;
    }

    public Long optionalLong(String name, long min, long max) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        Long parsed;
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (doubleValue != Math.floor(doubleValue) || !Double.isFinite(doubleValue)) {
                throw new ToolArgumentException("参数 " + name + " 必须是整数");
            }
            parsed = number.longValue();
        } else if (value instanceof String text) {
            try {
                parsed = Long.parseLong(text.trim());
            } catch (NumberFormatException exception) {
                throw new ToolArgumentException("参数 " + name + " 必须是整数");
            }
        } else {
            throw new ToolArgumentException("参数 " + name + " 必须是整数");
        }
        if (parsed < min || parsed > max) {
            throw new ToolArgumentException("参数 " + name + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return parsed;
    }

    public List<Long> requireLongList(String name, int maxSize) {
        List<Long> values = optionalLongList(name, maxSize);
        if (values == null || values.isEmpty()) {
            throw new ToolArgumentException("缺少必填参数：" + name);
        }
        return values;
    }

    public List<Long> optionalLongList(String name, int maxSize) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new ToolArgumentException("参数 " + name + " 必须是数组");
        }
        if (list.size() > maxSize) {
            throw new ToolArgumentException("参数 " + name + " 最多包含 " + maxSize + " 项");
        }
        LinkedHashSet<Long> parsed = new LinkedHashSet<>();
        for (Object item : list) {
            Long number = toLong(item, name);
            if (number == null || number <= 0) {
                throw new ToolArgumentException("参数 " + name + " 只能包含正整数");
            }
            parsed.add(number);
        }
        return List.copyOf(parsed);
    }

    public <E extends Enum<E>> E optionalEnum(String name, Class<E> type) {
        String value = optionalString(name, 40);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            List<String> allowed = new ArrayList<>();
            for (E candidate : type.getEnumConstants()) {
                allowed.add(candidate.name());
            }
            throw new ToolArgumentException("参数 " + name + " 只能取 " + allowed);
        }
    }

    private Long toLong(Object value, String name) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (doubleValue != Math.floor(doubleValue) || !Double.isFinite(doubleValue)) {
                return null;
            }
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    /** 工具参数不合法，由注册表转换为失败 Observation，不向客户端抛异常。 */
    public static class ToolArgumentException extends RuntimeException {
        public ToolArgumentException(String message) {
            super(message);
        }
    }
}
