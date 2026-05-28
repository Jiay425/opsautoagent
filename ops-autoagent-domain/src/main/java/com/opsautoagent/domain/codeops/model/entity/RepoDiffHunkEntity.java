package com.opsautoagent.domain.codeops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoDiffHunkEntity {

    private String filePath;

    private Integer oldStartLine;

    private Integer newStartLine;

    private Integer newEndLine;

    private String header;

    private List<String> addedLines;

    private String snippet;

}
