package com.opsautoagent.domain.codeops.agent.task;

import com.opsautoagent.domain.codeops.agent.tool.EngineeringToolGateway;
import com.opsautoagent.domain.codeops.model.entity.BackgroundToolTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskDagNodeEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.TaskNotificationEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class BackgroundToolTaskService {

    public static final String BACKGROUND_TOOL_TASKS_KEY = "backgroundToolTasks";
    public static final String TASK_NOTIFICATIONS_KEY = "taskNotifications";
    public static final String CONSUMED_TASK_NOTIFICATIONS_KEY = "consumedTaskNotifications";

    private final EngineeringToolGateway toolGateway;

    private final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private final ConcurrentMap<String, BackgroundToolTaskEntity> taskIndex = new ConcurrentHashMap<>();

    public BackgroundToolTaskService(EngineeringToolGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    public BackgroundToolTaskEntity startMavenAsync(EngineeringTaskEntity task,
                                                    String nodeId,
                                                    String repository,
                                                    List<String> args,
                                                    long timeoutMillis) {
        BackgroundToolTaskEntity backgroundTask = createBackgroundTask(task, resolveNodeId(task, nodeId), "repo.maven",
                "mvn " + String.join(" ", args == null ? List.of() : args));
        addOrReplaceTask(task, backgroundTask);
        addNotification(task, backgroundTask, "BACKGROUND_TASK_STARTED",
                "后台工具任务已开始：" + backgroundTask.getRequestSummary(), Map.of("timeoutMillis", timeoutMillis));
        executorService.submit(() -> finishMaven(task, backgroundTask, repository, args, timeoutMillis));
        return backgroundTask;
    }

    public EngineeringToolGateway.CommandResult runMavenAndRecord(EngineeringTaskEntity task,
                                                                  String nodeId,
                                                                  String repository,
                                                                  List<String> args,
                                                                  long timeoutMillis) {
        BackgroundToolTaskEntity backgroundTask = createBackgroundTask(task, resolveNodeId(task, nodeId), "repo.maven",
                "mvn " + String.join(" ", args == null ? List.of() : args));
        addOrReplaceTask(task, backgroundTask);
        addNotification(task, backgroundTask, "BACKGROUND_TASK_STARTED",
                "后台工具任务已开始：" + backgroundTask.getRequestSummary(), Map.of("timeoutMillis", timeoutMillis));
        EngineeringToolGateway.CommandResult result = finishMaven(task, backgroundTask, repository, args, timeoutMillis);
        return result;
    }

    private EngineeringToolGateway.CommandResult finishMaven(EngineeringTaskEntity task,
                                                            BackgroundToolTaskEntity backgroundTask,
                                                            String repository,
                                                            List<String> args,
                                                            long timeoutMillis) {
        try {
            EngineeringToolGateway.CommandResult result = toolGateway.runMavenCommand(repository, args, timeoutMillis);
            LocalDateTime now = LocalDateTime.now();
            backgroundTask.setStatus(result.success() ? "SUCCESS" : "FAILED");
            backgroundTask.setResultSummary("exitCode=" + result.exitCode() + ", costMillis=" + result.costMillis());
            backgroundTask.setErrorMessage(result.success() ? "" : result.output());
            backgroundTask.setCommand(result.command());
            backgroundTask.setArtifacts(Map.of(
                    "exitCode", result.exitCode(),
                    "costMillis", result.costMillis(),
                    "output", abbreviate(result.output(), 1200)
            ));
            backgroundTask.setUpdateTime(now);
            addOrReplaceTask(task, backgroundTask);
            addNotification(task, backgroundTask, "BACKGROUND_TASK_FINISHED",
                    backgroundTask.getResultSummary(), backgroundTask.getArtifacts());
            return result;
        } catch (Exception e) {
            LocalDateTime now = LocalDateTime.now();
            backgroundTask.setStatus("FAILED");
            backgroundTask.setResultSummary("background Maven execution failed: " + e.getClass().getSimpleName());
            backgroundTask.setErrorMessage(e.getMessage() == null ? "" : e.getMessage());
            backgroundTask.setArtifacts(Map.of(
                    "errorType", e.getClass().getSimpleName(),
                    "errorMessage", e.getMessage() == null ? "" : e.getMessage()
            ));
            backgroundTask.setUpdateTime(now);
            addOrReplaceTask(task, backgroundTask);
            addNotification(task, backgroundTask, "BACKGROUND_TASK_FAILED",
                    backgroundTask.getResultSummary(), backgroundTask.getArtifacts());
            return new EngineeringToolGateway.CommandResult(List.of("mvn"), false, -1,
                    backgroundTask.getErrorMessage(), 0L);
        }
    }

    public void recordSkippedMaven(EngineeringTaskEntity task,
                                   String nodeId,
                                   String repository,
                                   List<String> args,
                                   String reason) {
        BackgroundToolTaskEntity backgroundTask = createBackgroundTask(task, resolveNodeId(task, nodeId), "repo.maven",
                "mvn " + String.join(" ", args == null ? List.of() : args));
        LocalDateTime now = LocalDateTime.now();
        backgroundTask.setStatus("SKIPPED");
        backgroundTask.setResultSummary(reason == null ? "Maven execution skipped" : reason);
        backgroundTask.setCommand(args == null ? List.of() : args);
        backgroundTask.setArtifacts(Map.of(
                "repository", repository == null ? "" : repository,
                "skipped", true,
                "reason", reason == null ? "" : reason
        ));
        backgroundTask.setUpdateTime(now);
        addOrReplaceTask(task, backgroundTask);
        addNotification(task, backgroundTask, "BACKGROUND_TASK_SKIPPED",
                backgroundTask.getResultSummary(), backgroundTask.getArtifacts());
    }

    public BackgroundToolTaskEntity find(String backgroundTaskId) {
        if (backgroundTaskId == null || backgroundTaskId.isBlank()) {
            return null;
        }
        return taskIndex.get(backgroundTaskId);
    }

    public boolean hasRunningTasks(EngineeringTaskEntity task) {
        return backgroundTasks(task).stream()
                .anyMatch(backgroundTask -> "RUNNING".equalsIgnoreCase(backgroundTask.getStatus()));
    }

    public List<TaskNotificationEntity> pendingTerminalNotifications(EngineeringTaskEntity task) {
        return notifications(task).stream()
                .filter(notification -> !Boolean.TRUE.equals(notification.getConsumed()))
                .filter(notification -> isTerminalNotificationType(notification.getType()))
                .toList();
    }

    public List<TaskNotificationEntity> consumeTerminalNotifications(EngineeringTaskEntity task, String consumer) {
        if (task == null) {
            return List.of();
        }
        synchronized (task) {
            List<TaskNotificationEntity> notifications = new ArrayList<>(notifications(task));
            List<TaskNotificationEntity> consumed = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (TaskNotificationEntity notification : notifications) {
                if (notification == null
                        || Boolean.TRUE.equals(notification.getConsumed())
                        || !isTerminalNotificationType(notification.getType())) {
                    continue;
                }
                notification.setConsumed(true);
                notification.setConsumedBy(consumer == null || consumer.isBlank() ? "agent-loop" : consumer);
                notification.setConsumedTime(now);
                consumed.add(notification);
            }
            if (consumed.isEmpty()) {
                return List.of();
            }
            Map<String, Object> context = mutableContext(task);
            context.put(TASK_NOTIFICATIONS_KEY, notifications);
            List<Map<String, Object>> consumedSnapshots = new ArrayList<>(consumedNotificationSnapshots(task));
            consumed.stream().map(this::notificationSnapshot).forEach(consumedSnapshots::add);
            context.put(CONSUMED_TASK_NOTIFICATIONS_KEY, consumedSnapshots);
            task.setContext(context);
            return List.copyOf(consumed);
        }
    }

    private BackgroundToolTaskEntity createBackgroundTask(EngineeringTaskEntity task,
                                                         String nodeId,
                                                         String toolName,
                                                         String requestSummary) {
        LocalDateTime now = LocalDateTime.now();
        return BackgroundToolTaskEntity.builder()
                .backgroundTaskId("bgt-" + UUID.randomUUID())
                .taskId(task == null ? "" : task.getTaskId())
                .nodeId(nodeId == null ? "" : nodeId)
                .toolName(toolName)
                .status("RUNNING")
                .requestSummary(requestSummary == null ? "" : requestSummary)
                .resultSummary("")
                .errorMessage("")
                .command(List.of())
                .artifacts(Map.of())
                .createTime(now)
                .updateTime(now)
                .build();
    }

    private String resolveNodeId(EngineeringTaskEntity task, String nodeIdOrSkillId) {
        if (task == null || nodeIdOrSkillId == null || nodeIdOrSkillId.isBlank()) {
            return nodeIdOrSkillId == null ? "" : nodeIdOrSkillId;
        }
        if (nodeIdOrSkillId.startsWith("step-")) {
            return nodeIdOrSkillId;
        }
        Object value = task.getContext() == null ? null : task.getContext().get(CodeOpsTaskDagService.TASK_DAG_NODES_KEY);
        if (!(value instanceof List<?> list)) {
            return nodeIdOrSkillId;
        }
        for (int index = list.size() - 1; index >= 0; index--) {
            Object item = list.get(index);
            if (item instanceof EngineeringTaskDagNodeEntity node
                    && nodeIdOrSkillId.equals(node.getSkillId())
                    && node.getNodeId() != null
                    && !node.getNodeId().isBlank()) {
                return node.getNodeId();
            }
            if (item instanceof Map<?, ?> map
                    && nodeIdOrSkillId.equals(String.valueOf(map.get("skillId")))) {
                String nodeId = stringValue(map.get("nodeId"));
                if (!nodeId.isBlank()) {
                    return nodeId;
                }
            }
        }
        return nodeIdOrSkillId;
    }

    private void addOrReplaceTask(EngineeringTaskEntity task, BackgroundToolTaskEntity backgroundTask) {
        if (backgroundTask == null) {
            return;
        }
        taskIndex.put(backgroundTask.getBackgroundTaskId(), backgroundTask);
        if (task == null) {
            return;
        }
        synchronized (task) {
            Map<String, Object> context = mutableContext(task);
            List<BackgroundToolTaskEntity> tasks = new ArrayList<>(backgroundTasks(task));
            tasks.removeIf(existing -> backgroundTask.getBackgroundTaskId().equals(existing.getBackgroundTaskId()));
            tasks.add(backgroundTask);
            context.put(BACKGROUND_TOOL_TASKS_KEY, tasks);
            task.setContext(context);
        }
    }

    private void addNotification(EngineeringTaskEntity task,
                                 BackgroundToolTaskEntity backgroundTask,
                                 String type,
                                 String summary,
                                 Map<String, Object> payload) {
        if (task == null || backgroundTask == null) {
            return;
        }
        synchronized (task) {
            Map<String, Object> context = mutableContext(task);
            List<TaskNotificationEntity> notifications = new ArrayList<>(notifications(task));
            notifications.add(TaskNotificationEntity.builder()
                    .notificationId("ntf-" + UUID.randomUUID())
                    .taskId(task.getTaskId())
                    .nodeId(backgroundTask.getNodeId())
                    .backgroundTaskId(backgroundTask.getBackgroundTaskId())
                    .type(type)
                    .status(backgroundTask.getStatus())
                    .summary(summary == null ? "" : summary)
                    .payload(payload == null ? Map.of() : payload)
                    .consumed(false)
                    .createTime(LocalDateTime.now())
                    .build());
            context.put(TASK_NOTIFICATIONS_KEY, notifications);
            task.setContext(context);
        }
    }

    @SuppressWarnings("unchecked")
    public List<BackgroundToolTaskEntity> backgroundTasks(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return List.of();
        }
        Object value = task.getContext().get(BACKGROUND_TOOL_TASKS_KEY);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<BackgroundToolTaskEntity> tasks = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof BackgroundToolTaskEntity backgroundTask) {
                tasks.add(backgroundTask);
            } else if (item instanceof Map<?, ?> map) {
                tasks.add(BackgroundToolTaskEntity.builder()
                        .backgroundTaskId(stringValue(map.get("backgroundTaskId")))
                        .taskId(stringValue(map.get("taskId")))
                        .nodeId(stringValue(map.get("nodeId")))
                        .toolName(stringValue(map.get("toolName")))
                        .status(stringValue(map.get("status")))
                        .requestSummary(stringValue(map.get("requestSummary")))
                        .resultSummary(stringValue(map.get("resultSummary")))
                        .errorMessage(stringValue(map.get("errorMessage")))
                        .command(stringList(map.get("command")))
                        .artifacts(mapValue(map.get("artifacts")))
                        .build());
            }
        }
        return tasks;
    }

    @SuppressWarnings("unchecked")
    public List<TaskNotificationEntity> notifications(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return List.of();
        }
        Object value = task.getContext().get(TASK_NOTIFICATIONS_KEY);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<TaskNotificationEntity> notifications = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof TaskNotificationEntity notification) {
                notifications.add(notification);
            } else if (item instanceof Map<?, ?> map) {
                notifications.add(TaskNotificationEntity.builder()
                        .notificationId(stringValue(map.get("notificationId")))
                        .taskId(stringValue(map.get("taskId")))
                        .nodeId(stringValue(map.get("nodeId")))
                        .backgroundTaskId(stringValue(map.get("backgroundTaskId")))
                        .type(stringValue(map.get("type")))
                        .status(stringValue(map.get("status")))
                        .summary(stringValue(map.get("summary")))
                        .payload(mapValue(map.get("payload")))
                        .consumed(booleanValue(map.get("consumed")))
                        .consumedBy(stringValue(map.get("consumedBy")))
                        .build());
            }
        }
        return notifications;
    }

    private boolean isTerminalNotificationType(String type) {
        return "BACKGROUND_TASK_FINISHED".equals(type)
                || "BACKGROUND_TASK_FAILED".equals(type)
                || "BACKGROUND_TASK_SKIPPED".equals(type);
    }

    private Map<String, Object> notificationSnapshot(TaskNotificationEntity notification) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("notificationId", notification.getNotificationId());
        item.put("taskId", notification.getTaskId());
        item.put("nodeId", notification.getNodeId());
        item.put("backgroundTaskId", notification.getBackgroundTaskId());
        item.put("type", notification.getType());
        item.put("status", notification.getStatus());
        item.put("summary", notification.getSummary());
        item.put("payload", notification.getPayload() == null ? Map.of() : notification.getPayload());
        item.put("consumed", Boolean.TRUE.equals(notification.getConsumed()));
        item.put("consumedBy", notification.getConsumedBy() == null ? "" : notification.getConsumedBy());
        item.put("consumedTime", notification.getConsumedTime() == null ? "" : notification.getConsumedTime().toString());
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consumedNotificationSnapshots(EngineeringTaskEntity task) {
        if (task == null || task.getContext() == null) {
            return List.of();
        }
        Object value = task.getContext().get(CONSUMED_TASK_NOTIFICATIONS_KEY);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                map.forEach((key, rawValue) -> snapshot.put(String.valueOf(key), rawValue));
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    private Map<String, Object> mutableContext(EngineeringTaskEntity task) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (task.getContext() != null) {
            context.putAll(task.getContext());
        }
        return context;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }
}
