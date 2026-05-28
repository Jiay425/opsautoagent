package com.opsautoagent.domain.codeops.agent.patch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileRewritePatchEntity {

    private String filePath;

    private String newContent;

    private String reasoning;

}
