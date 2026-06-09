package com.opsautoagent.domain.codeops.agent.loop;

import java.util.List;

public interface AgentLoopModelClient {

    AgentLoopDecision next(AgentLoopRequest request, List<AgentLoopStep> previousSteps);

}
