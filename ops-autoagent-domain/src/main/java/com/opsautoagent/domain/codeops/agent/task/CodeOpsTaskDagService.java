package com.opsautoagent.domain.codeops.agent.task;

import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskDagNodeEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CodeOpsTaskDagService {

    public static final String TASK_DAG_NODES_KEY = "taskDagNodes";

    public String markSkillRunning(EngineeringTaskEntity task, int stepNo, String skillId, String reason) {
        return upsertSkillNode(task, stepNo, skillId, "RUNNING", reason, Map.of());
    }

    public String markSkillCompleted(EngineeringTaskEntity task,
                                     int stepNo,
                                     String skillId,
                                     String status,
                                     String summary,
                                     Map<String, Object> artifacts) {
        return upsertSkillNode(task, stepNo, skillId, normalizeStatus(status), summary, artifacts);
    }

    @SuppressWarnings("unchecked")
    public List<EngineeringTaskDagNodeEntity> nodes(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return List.of();
        }
        Object value = task.getContext().get(TASK_DAG_NODES_KEY);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<EngineeringTaskDagNodeEntity> nodes = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof EngineeringTaskDagNodeEntity node) {
                nodes.add(node);
            } else if (item instanceof Map<?, ?> map) {
                nodes.add(EngineeringTaskDagNodeEntity.builder()
                        .nodeId(stringValue(map.get("nodeId")))
                        .stepNo(integerValue(map.get("stepNo")))
                        .skillId(stringValue(map.get("skillId")))
                        .stage(stringValue(map.get("stage")))
                        .status(stringValue(map.get("status")))
                        .owner(stringValue(map.get("owner")))
                        .blockedBy(stringList(map.get("blockedBy")))
                        .summary(stringValue(map.get("summary")))
                        .artifacts(mapValue(map.get("artifacts")))
                        .createTime(timeValue(map.get("createTime")))
                        .updateTime(timeValue(map.get("updateTime")))
                        .build());
            }
        }
        return nodes;
    }

    private String upsertSkillNode(EngineeringTaskEntity task,
                                   int stepNo,
                                   String skillId,
                                   String status,
                                   String summary,
                                   Map<String, Object> artifacts) {
        if (task == null) {
            return "";
        }
        Map<String, Object> context = mutableContext(task);
        List<EngineeringTaskDagNodeEntity> nodes = new ArrayList<>(nodes(task));
        String nodeId = nodeId(stepNo, skillId);
        LocalDateTime now = LocalDateTime.now();
        EngineeringTaskDagNodeEntity target = null;
        for (EngineeringTaskDagNodeEntity node : nodes) {
            if (nodeId.equals(node.getNodeId())) {
                target = node;
                break;
            }
        }
        if (target == null) {
            target = EngineeringTaskDagNodeEntity.builder()
                    .nodeId(nodeId)
                    .stepNo(stepNo)
                    .skillId(skillId)
                    .stage(stageOf(skillId))
                    .owner("agent_loop")
                    .blockedBy(previousNodeIds(nodes))
                    .createTime(now)
                    .build();
            nodes.add(target);
        }
        target.setStatus(status);
        target.setSummary(summary == null ? "" : summary);
        target.setArtifacts(artifacts == null ? Map.of() : artifacts);
        target.setUpdateTime(now);
        context.put(TASK_DAG_NODES_KEY, nodes);
        task.setContext(context);
        return nodeId;
    }

    private Map<String, Object> mutableContext(EngineeringTaskEntity task) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        return context;
    }

    private List<String> previousNodeIds(List<EngineeringTaskDagNodeEntity> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        EngineeringTaskDagNodeEntity previous = nodes.get(nodes.size() - 1);
        return previous.getNodeId() == null || previous.getNodeId().isBlank()
                ? List.of()
                : List.of(previous.getNodeId());
    }

    private String nodeId(int stepNo, String skillId) {
        return "step-" + stepNo + "-" + (skillId == null || skillId.isBlank() ? "unknown" : skillId);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return switch (status.toUpperCase()) {
            case "SUCCESS", "NO_DIFF", "SKIPPED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "STOPPED" -> "BLOCKED";
            default -> status.toUpperCase();
        };
    }

    private String stageOf(String skillId) {
        if ("agent_loop_investigation".equals(skillId) || "repo_understanding".equals(skillId)) {
            return "code_localization";
        }
        if ("engineering_knowledge_rag".equals(skillId)) {
            return "knowledge_rag";
        }
        if ("bug_fix".equals(skillId)) {
            return "code_repair";
        }
        if ("test_verification".equals(skillId)) {
            return "test_verification";
        }
        if ("release_risk_analysis".equals(skillId)) {
            return "release_risk";
        }
        return skillId == null ? "unknown" : skillId;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime timeValue(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
