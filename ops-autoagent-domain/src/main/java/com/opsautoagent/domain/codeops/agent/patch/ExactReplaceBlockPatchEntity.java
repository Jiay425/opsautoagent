package com.opsautoagent.domain.codeops.agent.patch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExactReplaceBlockPatchEntity {

    private String filePath;

    private String oldText;

    private String newText;

    private String reasoning;
}
