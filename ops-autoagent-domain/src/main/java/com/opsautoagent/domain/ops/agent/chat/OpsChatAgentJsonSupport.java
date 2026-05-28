package com.opsautoagent.domain.ops.agent.chat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class OpsChatAgentJsonSupport {

    private OpsChatAgentJsonSupport() {
    }

    public static JSONObject parseObject(String content) {
        String json = extractJsonObject(content);
        if (isBlank(json)) {
            throw new IllegalArgumentException("Chat Agent output is blank or does not contain a JSON object.");
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            String repaired = repairLlmJson(json);
            if (repaired != null && !repaired.equals(json)) {
                try {
                    return JSON.parseObject(repaired);
                } catch (Exception ignored) {
                }
            }
            throw e;
        }
    }

    private static String repairLlmJson(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                result.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (inString && isLikelyInnerQuote(json, i)) {
                    result.append("\\\"");
                    continue;
                }
                inString = !inString;
                result.append(c);
                continue;
            }
            if (inString) {
                switch (c) {
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    case '\b' -> result.append("\\b");
                    case '\f' -> result.append("\\f");
                    default -> result.append(c);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static boolean isLikelyInnerQuote(String json, int quoteIndex) {
        for (int j = quoteIndex + 1; j < json.length(); j++) {
            char next = json.charAt(j);
            if (next == ' ' || next == '\t' || next == '\n' || next == '\r') {
                continue;
            }
            return next != ':' && next != ',' && next != '}' && next != ']';
        }
        return false;
    }

    public static String extractJsonObject(String content) {
        if (isBlank(content)) {
            return "";
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                text = text.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

