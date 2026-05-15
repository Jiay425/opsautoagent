package com.opsautoagent.domain.ops.service.execute;

import com.opsautoagent.domain.ops.agent.memory.OpsIncidentWorkingMemory;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceSemanticEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultOpsAgentExecuteStrategyFactory {

    private final OpsRootNode opsRootNode;

    public DefaultOpsAgentExecuteStrategyFactory(OpsRootNode opsRootNode) {
        this.opsRootNode = opsRootNode;
    }

    public StrategyHandler<IncidentCommandEntity, DynamicContext, String> opsAgentStrategyHandler() {
        return opsRootNode;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        private ResponseBodyEmitter emitter;

        private OpsIncidentWorkingMemory workingMemory;

        private MetricEvidenceEntity metricEvidence;

        private LogEvidenceEntity logEvidence;

        private TraceEvidenceEntity traceEvidence;

        private List<EvidenceSignalEntity> evidenceSignals;

        private List<EvidenceSemanticEntity> evidenceSemantics;

        private List<RootCauseCandidateEntity> rootCauseCandidates;

        private List<RunbookMatchEntity> runbookMatches;

        private String report;

        private String status;

        private String errorMessage;

        @Builder.Default
        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

    }

}


