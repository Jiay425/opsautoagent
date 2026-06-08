package com.opsautoagent.domain.codeops.model.entity;

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
public class CodeContextPackEntity {

    private String strategy;

    private List<String> primaryFiles;

    private List<String> candidateFiles;

    private List<String> supportFiles;

    private List<String> relatedTests;

    private List<String> buildFiles;

    private Map<String, String> contextReasons;

    private List<CodeSnippetEntity> snippets;

    private Integer availableSnippetCount;

    private Integer totalSnippetCount;

    private List<String> missingFiles;

}
