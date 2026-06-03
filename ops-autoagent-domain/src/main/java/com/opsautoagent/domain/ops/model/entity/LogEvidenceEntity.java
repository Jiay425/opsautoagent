package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LogEvidenceEntity {

    private String source;

    private boolean available;

    private String summary;

    private List<String> errorSamples;

    private String rawData;

    private Map<String, Object> sourceMetadata;

}

