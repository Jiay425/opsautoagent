package com.opsautoagent.domain.ops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MetricEvidenceEntity {

    private String source;

    private boolean available;

    private String summary;

    private List<String> observations;

    private String rawData;

}

