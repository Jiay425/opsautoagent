package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EngineeringTaskEntity {

    private String taskId;

    private String taskType;

    private String goal;

    private String repository;

    private String changeRef;

    private List<String> focusAreas;

    private Map<String, Object> context;

    private String status;

    private Integer maxRounds;

    private Integer maxToolCalls;

    private Integer usedToolCalls;

    private String finalSummary;

    private List<EngineeringTaskStepEntity> steps;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public void addStep(EngineeringTaskStepEntity step) {
        if (steps == null) {
            steps = new ArrayList<>();
        }
        steps.add(step);
        updateTime = LocalDateTime.now();
    }

}
