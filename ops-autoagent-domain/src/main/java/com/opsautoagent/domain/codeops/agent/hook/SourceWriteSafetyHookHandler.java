package com.opsautoagent.domain.codeops.agent.hook;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SourceWriteSafetyHookHandler implements CodeOpsHookHandler {

    @Override
    public String name() {
        return "source_write_safety";
    }

    @Override
    public boolean supports(CodeOpsHookEvent event, String phase, Map<String, Object> payload) {
        if (event != CodeOpsHookEvent.BEFORE_TOOL_USE || payload == null) {
            return false;
        }
        return "repo.exact_replace".equals(String.valueOf(payload.getOrDefault("toolName", "")));
    }

    @Override
    public CodeOpsHookDecision handle(EngineeringTaskEntity task,
                                      CodeOpsHookEvent event,
                                      String phase,
                                      Map<String, Object> payload) {
        Object arguments = payload == null ? null : payload.get("arguments");
        if (!(arguments instanceof Map<?, ?> args)) {
            return CodeOpsHookDecision.deny(name(), "repo.exact_replace requires structured arguments");
        }
        Object filePathValue = args.get("filePath");
        String filePath = filePathValue == null ? "" : String.valueOf(filePathValue);
        String normalized = filePath.replace('\\', '/');
        if (normalized.isBlank()) {
            return CodeOpsHookDecision.deny(name(), "repo.exact_replace requires filePath");
        }
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.startsWith("\\")) {
            return CodeOpsHookDecision.deny(name(), "repo.exact_replace filePath escapes repository");
        }
        if (!normalized.startsWith("src/")) {
            return CodeOpsHookDecision.deny(name(), "repo.exact_replace is limited to repository src/** files");
        }
        return CodeOpsHookDecision.allow(name(), "source write target passed hook safety policy");
    }
}
