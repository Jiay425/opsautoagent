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
public class EngineeringTaskDagNodeEntity {

    private String nodeId;

    private Integer stepNo;

    private String skillId;

    private String stage;

    private String status;

    private String owner;

    private List<String> blockedBy;

    private String summary;

    private Map<String, Object> artifacts;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
