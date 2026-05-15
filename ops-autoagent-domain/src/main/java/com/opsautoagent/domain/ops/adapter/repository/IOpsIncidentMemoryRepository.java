package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.model.entity.HistoricalIncidentMemoryEntity;

import java.util.List;

public interface IOpsIncidentMemoryRepository {

    void saveHistoricalMemory(HistoricalIncidentMemoryEntity memory);

    List<HistoricalIncidentMemoryEntity> querySimilarMemories(String serviceName, String keyword, int limit);

}

