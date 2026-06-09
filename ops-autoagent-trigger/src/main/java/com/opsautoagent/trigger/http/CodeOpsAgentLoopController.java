package com.opsautoagent.trigger.http;

import com.opsautoagent.api.dto.CodeOpsAgentLoopRunRequestDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.agent.loop.AgentLoopResult;
import com.opsautoagent.domain.codeops.service.CodeOpsAgentLoopApplicationService;
import com.opsautoagent.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/codeops/agent-loop")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CodeOpsAgentLoopController {

    @Resource
    private CodeOpsAgentLoopApplicationService applicationService;

    @RequestMapping(value = "run", method = RequestMethod.POST)
    public Response<AgentLoopResult> run(@RequestBody CodeOpsAgentLoopRunRequestDTO request) {
        String validation = validate(request);
        if (validation != null) {
            return Response.<AgentLoopResult>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(validation)
                    .build();
        }
        try {
            return Response.<AgentLoopResult>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(applicationService.run(request))
                    .build();
        } catch (Exception e) {
            return Response.<AgentLoopResult>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    private String validate(CodeOpsAgentLoopRunRequestDTO request) {
        if (request == null) {
            return "request cannot be null";
        }
        if (isBlank(request.getGoal())) {
            return "goal cannot be blank";
        }
        if (isBlank(request.getRepository())) {
            return "repository cannot be blank";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
