package com.opsautoagent.domain.codeops.agent.fixture;

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
public class IncidentFixtureEvidence {

    private boolean available;

    private String caseId;

    private String basePath;

    private Map<String, Object> alert;

    private Map<String, Object> prometheus;

    private Map<String, Object> logs;

    private Map<String, Object> trace;

    private List<String> evidenceSources;

    private List<String> codeHints;

    private String reportSummary;

    private String errorMessage;

}
