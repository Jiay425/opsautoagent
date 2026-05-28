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
public class CodeSnippetEntity {

    private String filePath;

    private Integer startLine;

    private Integer endLine;

    private List<String> lines;

    private Boolean available;

    private String errorMessage;

}
