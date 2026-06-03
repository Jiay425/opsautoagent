package com.opsautoagent.domain.codeops.agent.llm;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class CodeOpsCompatibleChatClient {

    private final RestClient restClient = RestClient.builder().build();

    @Value("${codeops.agent.llm.compatible-client.enabled:true}")
    private boolean enabled;

    @Value("${spring.ai.openai.base-url:}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:${spring.ai.openai.chat.model:gpt-4o-mini}}")
    private String model;

    @Value("${codeops.agent.llm.compatible-client.path:/chat/completions}")
    private String chatPath;

    @Value("${codeops.agent.llm.compatible-client.temperature:0.1}")
    private double temperature;

    @Value("${codeops.agent.llm.compatible-client.max-tokens:8192}")
    private int maxTokens;

    public boolean available() {
        return enabled && !isBlank(baseUrl) && !isBlank(apiKey) && !isBlank(model);
    }

    public String unavailableReason() {
        if (!enabled) return "CodeOps compatible LLM client is disabled.";
        if (isBlank(baseUrl)) return "spring.ai.openai.base-url is blank.";
        if (isBlank(apiKey)) return "spring.ai.openai.api-key is blank.";
        if (isBlank(model)) return "spring.ai.openai.chat.options.model is blank.";
        return "";
    }

    public String call(String prompt) {
        return call(prompt, null);
    }

    public String call(String prompt, String modelOverride) {
        return call(prompt, modelOverride, null);
    }

    public String call(String prompt, String modelOverride, Integer maxTokensOverride) {
        if (!available()) throw new IllegalStateException(unavailableReason());

        JSONObject request = new JSONObject();
        request.put("model", modelOverride != null && !modelOverride.isBlank() ? modelOverride : model);
        request.put("messages", List.of(message("user", prompt)));
        request.put("temperature", temperature);
        request.put("max_tokens", maxTokensOverride != null && maxTokensOverride > 0 ? maxTokensOverride : maxTokens);
        request.put("stream", false);

        String responseText;
        try {
            responseText = restClient.post()
                    .uri(chatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request.toJSONString())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Compatible LLM request failed: " + e.getMessage(), e);
        }

        String content = extractContent(responseText);
        if (isBlank(content)) {
            throw new IllegalStateException("Compatible LLM returned blank content. response=" + abbreviate(responseText, 1200));
        }
        return content;
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
        return message;
    }

    private String chatCompletionsUrl() {
        String root = baseUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        String path = isBlank(chatPath) ? "/chat/completions" : chatPath.trim();
        if (!path.startsWith("/")) path = "/" + path;
        return root + path;
    }

    private String extractContent(String responseText) {
        JSONObject response = JSONObject.parseObject(responseText);
        JSONArray choices = response.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JSONObject first = choices.getJSONObject(0);
        if (first == null) return "";
        JSONObject message = first.getJSONObject("message");
        if (message != null) {
            if (!isBlank(message.getString("content"))) return message.getString("content");
            if (!isBlank(message.getString("reasoning_content"))) return message.getString("reasoning_content");
        }
        return first.getString("text");
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
