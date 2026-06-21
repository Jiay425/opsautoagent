package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepairObservationEntity {

    private String observationId;

    private String taskId;

    private String phase;

    private String source;

    private String action;

    private String status;

    private Boolean success;

    private String summary;

    private String errorType;

    private String errorMessage;

    @Builder.Default
    private Map<String, Object> input = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> output = new LinkedHashMap<>();

    private LocalDateTime createTime;

    public Map<String, Object> toRawOutput() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("observationId", observationId == null ? "" : observationId);
        raw.put("taskId", taskId == null ? "" : taskId);
        raw.put("phase", phase == null ? "" : phase);
        raw.put("source", source == null ? "" : source);
        raw.put("action", action == null ? "" : action);
        raw.put("status", status == null ? "" : status);
        raw.put("success", Boolean.TRUE.equals(success));
        raw.put("summary", summary == null ? "" : summary);
        raw.put("errorType", errorType == null ? "" : errorType);
        raw.put("errorMessage", errorMessage == null ? "" : errorMessage);
        raw.put("input", input == null ? Map.of() : input);
        raw.put("output", output == null ? Map.of() : output);
        raw.put("createTime", createTime == null ? "" : createTime.toString());
        return raw;
    }
}
