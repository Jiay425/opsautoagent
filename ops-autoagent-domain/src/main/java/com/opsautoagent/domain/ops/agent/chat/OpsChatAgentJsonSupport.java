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
        return JSON.parseObject(json);
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

