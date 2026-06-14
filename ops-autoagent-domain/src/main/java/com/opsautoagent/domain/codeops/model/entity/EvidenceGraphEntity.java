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
public class EvidenceGraphEntity {

    private List<EvidenceNodeEntity> nodes;

    private List<EvidenceEdgeEntity> edges;

    private List<String> localizationSeeds;

    private List<String> rankedCodeNodes;

    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EvidenceNodeEntity {

        private String id;

        private String type;

        private String label;

        private String source;

        private String detail;

        private Integer score;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EvidenceEdgeEntity {

        private String from;

        private String to;

        private String relation;

        private String reason;

        private Integer weight;
    }
}
