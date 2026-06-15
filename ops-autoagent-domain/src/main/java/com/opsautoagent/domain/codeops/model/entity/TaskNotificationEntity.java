package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskNotificationEntity {

    private String notificationId;

    private String taskId;

    private String nodeId;

    private String backgroundTaskId;

    private String type;

    private String status;

    private String summary;

    private Map<String, Object> payload;

    private Boolean consumed;

    private String consumedBy;

    private LocalDateTime consumedTime;

    private LocalDateTime createTime;
}
