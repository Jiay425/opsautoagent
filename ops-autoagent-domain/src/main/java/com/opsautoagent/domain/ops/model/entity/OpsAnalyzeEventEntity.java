package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpsAnalyzeEventEntity {

    private String type;

    private String subType;

    private Integer step;

    private String content;

    private Boolean completed;

    private Long timestamp;

    private String sessionId;

    public static OpsAnalyzeEventEntity running(String type, String subType, Integer step, String content, String sessionId) {
        return OpsAnalyzeEventEntity.builder()
                .type(type)
                .subType(subType)
                .step(step)
                .content(content)
                .completed(false)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    public static OpsAnalyzeEventEntity completed(String content, String sessionId) {
        return OpsAnalyzeEventEntity.builder()
                .type("complete")
                .subType("diagnosis_completed")
                .content(content)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

    public static OpsAnalyzeEventEntity error(String content, String sessionId) {
        return OpsAnalyzeEventEntity.builder()
                .type("error")
                .subType("diagnosis_error")
                .content(content)
                .completed(true)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .build();
    }

}

