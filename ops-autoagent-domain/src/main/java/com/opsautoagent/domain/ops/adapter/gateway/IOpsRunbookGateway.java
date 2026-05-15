package com.opsautoagent.domain.ops.adapter.gateway;

import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.RootCauseCandidateEntity;
import com.opsautoagent.domain.ops.model.entity.RunbookMatchEntity;

import java.util.List;

public interface IOpsRunbookGateway {

    List<RunbookMatchEntity> search(IncidentCommandEntity command,
                                    List<RootCauseCandidateEntity> rootCauseCandidates,
                                    int topK);

    default List<RunbookMatchEntity> searchByEvidenceSignals(IncidentCommandEntity command,
                                                             List<EvidenceSignalEntity> evidenceSignals,
                                                             int topK) {
        return search(command, List.of(), topK);
    }

}

