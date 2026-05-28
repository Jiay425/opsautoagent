package com.opsautoagent.trigger.http;

import com.opsautoagent.api.ICodeOpsTaskService;
import com.opsautoagent.api.dto.CodeOpsSkillDTO;
import com.opsautoagent.api.dto.CodeOpsTaskDTO;
import com.opsautoagent.api.dto.CodeOpsTaskStepDTO;
import com.opsautoagent.api.dto.CodeOpsTaskSubmitRequestDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.service.EngineeringTaskAgentService;
import com.opsautoagent.domain.codeops.service.EngineeringTaskTraceService;
import com.opsautoagent.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/codeops/task")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CodeOpsTaskController implements ICodeOpsTaskService {

    @Resource
    private EngineeringTaskAgentService engineeringTaskAgentService;

    @Resource
    private EngineeringTaskTraceService engineeringTaskTraceService;

    @Override
    @RequestMapping(value = "submit", method = RequestMethod.POST)
    public Response<CodeOpsTaskDTO> submitTask(@RequestBody CodeOpsTaskSubmitRequestDTO request) {
        String validationMessage = validate(request);
        if (validationMessage != null) {
            return Response.<CodeOpsTaskDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(validationMessage)
                    .build();
        }

        EngineeringTaskEntity task = engineeringTaskAgentService.submit(EngineeringTaskEntity.builder()
                .taskType(request.getTaskType())
                .goal(request.getGoal())
                .repository(request.getRepository())
                .changeRef(request.getChangeRef())
                .focusAreas(request.getFocusAreas())
                .context(request.getContext())
                .maxRounds(request.getMaxRounds())
                .maxToolCalls(request.getMaxToolCalls())
                .build());

        return Response.<CodeOpsTaskDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(toDTO(task))
                .build();
    }

    @Override
    @RequestMapping(value = "{taskId}", method = RequestMethod.GET)
    public Response<CodeOpsTaskDTO> queryTask(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<CodeOpsTaskDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.query(taskId);
        if (task == null) {
            return Response.<CodeOpsTaskDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("CodeOps task not found")
                    .build();
        }
        return Response.<CodeOpsTaskDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(toDTO(task))
                .build();
    }

    @Override
    @RequestMapping(value = "{taskId}/trace", method = RequestMethod.GET)
    public Response<CodeOpsTaskTraceDTO> queryTaskTrace(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<CodeOpsTaskTraceDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.query(taskId);
        if (task == null) {
            return Response.<CodeOpsTaskTraceDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("CodeOps task not found")
                    .build();
        }
        return Response.<CodeOpsTaskTraceDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(engineeringTaskTraceService.buildTrace(task))
                .build();
    }

    @Override
    @RequestMapping(value = "skills", method = RequestMethod.GET)
    public Response<List<CodeOpsSkillDTO>> listSkills() {
        return Response.<List<CodeOpsSkillDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(engineeringTaskAgentService.listSkills().stream().map(this::toDTO).toList())
                .build();
    }

    @RequestMapping(value = "list/recent", method = RequestMethod.GET)
    public Response<List<CodeOpsTaskDTO>> listRecentTasks() {
        return Response.<List<CodeOpsTaskDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(engineeringTaskAgentService.listRecent(10).stream().map(this::toDTO).toList())
                .build();
    }

    private CodeOpsTaskDTO toDTO(EngineeringTaskEntity task) {
        return CodeOpsTaskDTO.builder()
                .taskId(task.getTaskId())
                .taskType(task.getTaskType())
                .goal(task.getGoal())
                .repository(task.getRepository())
                .changeRef(task.getChangeRef())
                .status(task.getStatus())
                .maxRounds(task.getMaxRounds())
                .maxToolCalls(task.getMaxToolCalls())
                .finalSummary(task.getFinalSummary())
                .steps(task.getSteps() == null ? List.of() : task.getSteps().stream().map(this::toDTO).toList())
                .createTime(task.getCreateTime() == null ? null : task.getCreateTime().toString())
                .updateTime(task.getUpdateTime() == null ? null : task.getUpdateTime().toString())
                .build();
    }

    private CodeOpsTaskStepDTO toDTO(EngineeringTaskStepEntity step) {
        return CodeOpsTaskStepDTO.builder()
                .stepNo(step.getStepNo())
                .decision(step.getDecision())
                .selectedSkill(step.getSelectedSkill())
                .reason(step.getReason())
                .expectedEvidence(step.getExpectedEvidence())
                .resultSummary(step.getResultSummary())
                .rawEvidenceJson(step.getRawEvidenceJson())
                .status(step.getStatus())
                .build();
    }

    private CodeOpsSkillDTO toDTO(EngineeringSkillEntity skill) {
        return CodeOpsSkillDTO.builder()
                .skillId(skill.getSkillId())
                .name(skill.getName())
                .description(skill.getDescription())
                .supportedTaskTypes(skill.getSupportedTaskTypes())
                .requiredTools(skill.getRequiredTools())
                .riskLevel(skill.getRiskLevel())
                .build();
    }

    private String validate(CodeOpsTaskSubmitRequestDTO request) {
        if (request == null) {
            return "request body cannot be null";
        }
        if (isBlank(request.getTaskType())) {
            return "taskType cannot be blank";
        }
        if (isBlank(request.getGoal())) {
            return "goal cannot be blank";
        }
        if (request.getGoal().length() > 4000) {
            return "goal length must be <= 4000";
        }
        if (request.getMaxRounds() != null && (request.getMaxRounds() < 1 || request.getMaxRounds() > 12)) {
            return "maxRounds must be between 1 and 12";
        }
        if (request.getMaxToolCalls() != null && (request.getMaxToolCalls() < 1 || request.getMaxToolCalls() > 50)) {
            return "maxToolCalls must be between 1 and 50";
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
