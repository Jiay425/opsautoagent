package com.opsautoagent.domain.codeops.service;

import com.opsautoagent.api.dto.CodeOpsAgentLoopRunRequestDTO;
import com.opsautoagent.domain.codeops.agent.llm.CodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.llm.MockCodeOpsAgentLoopModelClient;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopRequest;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopService;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class CodeOpsAgentLoopApplicationService {

    private final AgentLoopService agentLoopService;
    private final CodeOpsAgentLoopModelClient modelClient;
    private final MockCodeOpsAgentLoopModelClient mockModelClient;

    public CodeOpsAgentLoopApplicationService(AgentLoopService agentLoopService,
                                              CodeOpsAgentLoopModelClient modelClient,
                                              MockCodeOpsAgentLoopModelClient mockModelClient) {
        this.agentLoopService = agentLoopService;
        this.modelClient = modelClient;
        this.mockModelClient = mockModelClient;
    }

    public AgentLoopResult run(CodeOpsAgentLoopRunRequestDTO request) {
        EngineeringTaskEntity task = EngineeringTaskEntity.builder()
                .taskId("agent-loop-debug-" + UUID.randomUUID())
                .taskType("AGENT_LOOP_DEBUG")
                .goal(request.getGoal())
                .repository(request.getRepository())
                .changeRef(request.getChangeRef())
                .focusAreas(request.getFocusAreas())
                .context(request.getContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getContext()))
                .status("RUNNING")
                .maxRounds(request.getMaxTurns() == null ? 8 : request.getMaxTurns())
                .maxToolCalls(20)
                .usedToolCalls(0)
                .steps(new ArrayList<>())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        AgentLoopRequest loopRequest = AgentLoopRequest.builder()
                .goal(request.getGoal())
                .task(task)
                .maxTurns(request.getMaxTurns() == null ? 8 : request.getMaxTurns())
                .metadata(request.getContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getContext()))
                .build();
        AgentLoopResult result = agentLoopService.run(loopRequest, Boolean.TRUE.equals(request.getDryRun()) ? mockModelClient : modelClient);
        if (!Boolean.TRUE.equals(request.getIncludeSteps())) {
            result.setSteps(List.of());
        }
        return result;
    }

}
