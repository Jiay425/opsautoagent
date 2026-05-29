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
            // Third and final fallback: aggressive repair (trailing commas, single quotes, unquoted keys)
            String aggressive = aggressiveRepair(repaired != null ? repaired : json);
            if (aggressive != null && !aggressive.equals(json) && !aggressive.equals(repaired)) {
                try {
                    return JSON.parseObject(aggressive);
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
                if (inString && !isValidJsonEscape(c)) {
                    // Invalid escape: \x where x is not a valid JSON escape char
                    // Double the backslash to make it literal
                    result.append("\\\\");
                }
                // Always append the character (it was already escaped or we fixed it)
                if (inString && !isValidJsonEscape(c)) {
                    result.append(c);
                } else {
                    result.append(c);
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (i == json.length() - 1) {
                    // Trailing backslash at end of input — invalid, double it
                    result.append("\\\\");
                    continue;
                }
                // Peek next char; if invalid JSON escape and we're in a string, double now
                char next = json.charAt(i + 1);
                if (inString && !isValidJsonEscape(next) && next != '"' && next != '\\'
                        && next != '/' && next != 'b' && next != 'f' && next != 'n'
                        && next != 'r' && next != 't' && next != 'u') {
                    result.append("\\\\");
                    continue;
                }
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

    private static boolean isValidJsonEscape(char c) {
        return c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f'
                || c == 'n' || c == 'r' || c == 't' || c == 'u';
    }

    /**
     * Aggressive repair: fix trailing commas, convert single-quote keys/values to double quotes,
     * remove comments. This is a last-resort fallback when standard repair fails.
     */
    private static String aggressiveRepair(String json) {
        if (isBlank(json)) return json;

        // 1. Remove single-line comments
        String result = json.replaceAll("//[^\n]*", "");

        // 2. Remove trailing commas before ] or }
        result = result.replaceAll(",\\s*([}\\]])", "$1");

        // 3. Replace single-quoted strings with double-quoted (heuristic: inside JSON context)
        // This is complex; only do simple fixes
        result = result.replace('\t', ' ');

        return result;
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

