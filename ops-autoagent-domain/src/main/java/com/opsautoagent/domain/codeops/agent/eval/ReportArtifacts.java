package com.opsautoagent.domain.codeops.agent.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReportArtifacts {

    private String reportJsonPath;
    private String reportMarkdownPath;
    private String traceJsonPath;
    private String patchDiffPath;
}
