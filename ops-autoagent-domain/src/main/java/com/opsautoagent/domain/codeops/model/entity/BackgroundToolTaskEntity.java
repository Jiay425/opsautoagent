package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackgroundToolTaskEntity {

    private String backgroundTaskId;

    private String taskId;

    private String nodeId;

    private String toolName;

    private String status;

    private String requestSummary;

    private String resultSummary;

    private String errorMessage;

    private List<String> command;

    private Map<String, Object> artifacts;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
