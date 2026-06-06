package com.opsautoagent.trigger.http;

import com.opsautoagent.api.ICodeOpsTaskService;
import com.opsautoagent.api.dto.CodeOpsApprovalDecisionRequestDTO;
import com.opsautoagent.api.dto.CodeOpsIncidentFixSubmitRequestDTO;
import com.opsautoagent.api.dto.CodeOpsIncidentFixViewDTO;
import com.opsautoagent.api.dto.CodeOpsSkillDTO;
import com.opsautoagent.api.dto.CodeOpsTaskDTO;
import com.opsautoagent.api.dto.CodeOpsTaskStepDTO;
import com.opsautoagent.api.dto.CodeOpsTaskSubmitRequestDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceDTO;
import com.opsautoagent.api.response.Response;
import com.opsautoagent.domain.codeops.model.entity.EngineeringSkillEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskEntity;
import com.opsautoagent.domain.codeops.model.entity.EngineeringTaskStepEntity;
import com.opsautoagent.domain.codeops.agent.security.CodeOpsSecurityGovernanceService;
import com.opsautoagent.domain.codeops.agent.security.HumanApprovalGate;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/codeops/task")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class CodeOpsTaskController implements ICodeOpsTaskService {

    @Resource
    private EngineeringTaskAgentService engineeringTaskAgentService;

    @Resource
    private EngineeringTaskTraceService engineeringTaskTraceService;

    @Resource
    private CodeOpsSecurityGovernanceService codeOpsSecurityGovernanceService;

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

    @RequestMapping(value = "incident/submit", method = RequestMethod.POST)
    public Response<CodeOpsIncidentFixViewDTO> submitIncidentToFix(@RequestBody CodeOpsIncidentFixSubmitRequestDTO request) {
        String validationMessage = validateIncidentRequest(request);
        if (validationMessage != null) {
            return Response.<CodeOpsIncidentFixViewDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(validationMessage)
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.submit(EngineeringTaskEntity.builder()
                .taskType("INCIDENT_TO_FIX")
                .goal(buildIncidentGoal(request))
                .repository(request.getRepository())
                .changeRef(request.getChangeRef())
                .focusAreas(resolveIncidentFocusAreas(request.getFocusAreas()))
                .context(buildIncidentContext(request))
                .maxRounds(request.getMaxRounds())
                .maxToolCalls(request.getMaxToolCalls())
                .build());
        return Response.<CodeOpsIncidentFixViewDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(buildIncidentView(task))
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

    @RequestMapping(value = "incident/{taskId}", method = RequestMethod.GET)
    public Response<CodeOpsIncidentFixViewDTO> queryIncidentToFixView(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<CodeOpsIncidentFixViewDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.query(taskId);
        if (task == null) {
            return Response.<CodeOpsIncidentFixViewDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("CodeOps task not found")
                    .build();
        }
        return Response.<CodeOpsIncidentFixViewDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(buildIncidentView(task))
                .build();
    }

    @RequestMapping(value = "security/governance", method = RequestMethod.GET)
    public Response<Map<String, Object>> querySecurityGovernance() {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(codeOpsSecurityGovernanceService.globalSummary())
                .build();
    }

    @RequestMapping(value = "{taskId}/security", method = RequestMethod.GET)
    public Response<Map<String, Object>> queryTaskSecurity(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        EngineeringTaskEntity task = engineeringTaskAgentService.query(taskId);
        if (task == null) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("CodeOps task not found")
                    .build();
        }
        HumanApprovalGate.ApprovalRecord approval = engineeringTaskAgentService.approvalStatus(taskId);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(codeOpsSecurityGovernanceService.taskSummary(task, approval == null ? Map.of() : approval.toMap()))
                .build();
    }

    @RequestMapping(value = "{taskId}/approval", method = RequestMethod.GET)
    public Response<Map<String, Object>> queryApproval(@PathVariable("taskId") String taskId) {
        if (isBlank(taskId)) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("taskId cannot be blank")
                    .build();
        }
        HumanApprovalGate.ApprovalRecord record = engineeringTaskAgentService.approvalStatus(taskId);
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(record == null ? "No pending approval for this task" : ResponseCode.SUCCESS.getInfo())
                .data(record == null ? Map.of() : record.toMap())
                .build();
    }

    @RequestMapping(value = "{taskId}/approval/approve", method = RequestMethod.POST)
    public Response<Map<String, Object>> approveTask(@PathVariable("taskId") String taskId) {
        try {
            HumanApprovalGate.ApprovalRecord record = engineeringTaskAgentService.approveTask(taskId);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Task approved by human reviewer")
                    .data(record.toMap())
                    .build();
        } catch (Exception e) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "{taskId}/approval/reject", method = RequestMethod.POST)
    public Response<Map<String, Object>> rejectTask(@PathVariable("taskId") String taskId,
                                                    @RequestBody CodeOpsApprovalDecisionRequestDTO request) {
        try {
            String reason = request == null || isBlank(request.getReason())
                    ? "No reason provided"
                    : request.getReason();
            HumanApprovalGate.ApprovalRecord record = engineeringTaskAgentService.rejectTask(taskId, reason);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Task rejected: " + reason)
                    .data(record.toMap())
                    .build();
        } catch (Exception e) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
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

    private CodeOpsIncidentFixViewDTO buildIncidentView(EngineeringTaskEntity task) {
        HumanApprovalGate.ApprovalRecord approval = engineeringTaskAgentService.approvalStatus(task.getTaskId());
        return engineeringTaskTraceService.buildIncidentFixView(task, approval == null ? Map.of() : approval.toMap());
    }

    private String buildIncidentGoal(CodeOpsIncidentFixSubmitRequestDTO request) {
        String serviceName = value(request.getServiceName(), "unknown-service");
        String alertRule = value(request.getAlertRule(), "unknown-alert");
        String severity = value(request.getSeverity(), "UNKNOWN");
        String problem = value(request.getProblem(), "线上异常待诊断");
        String endpoint = isBlank(request.getEndpoint()) ? "" : "，接口：" + request.getEndpoint();
        return serviceName + " 触发线上告警 [" + alertRule + "]，级别：" + severity
                + endpoint + "，问题描述：" + problem
                + "。请完成 Incident-to-Fix：采集可观测证据、定位代码、判断是否需要修复、生成最小补丁、编译测试验证并输出发布风险。";
    }

    private Map<String, Object> buildIncidentContext(CodeOpsIncidentFixSubmitRequestDTO request) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        context.put("source", value(stringValue(context.get("source")), "codeops_incident_api"));
        context.put("evidenceMode", value(stringValue(context.get("evidenceMode")), "LIVE"));
        context.put("fixtureFallbackAllowed", request.getFixtureFallbackAllowed() != null && request.getFixtureFallbackAllowed());
        putIfPresent(context, "serviceName", request.getServiceName());
        putIfPresent(context, "alertRule", request.getAlertRule());
        putIfPresent(context, "severity", request.getSeverity());
        putIfPresent(context, "problem", request.getProblem());
        putIfPresent(context, "endpoint", request.getEndpoint());
        putIfPresent(context, "traceId", request.getTraceId());
        putIfPresent(context, "startTime", request.getStartTime());
        putIfPresent(context, "endTime", request.getEndTime());
        putIfPresent(context, "repository", request.getRepository());
        context.put("allowPatchApply", request.getAllowPatchApply() == null || request.getAllowPatchApply());
        context.put("allowTestPatchApply", request.getAllowTestPatchApply() == null || request.getAllowTestPatchApply());
        context.put("alertLabels", request.getLabels() == null ? Map.of() : request.getLabels());
        context.put("alertAnnotations", request.getAnnotations() == null ? Map.of() : request.getAnnotations());
        return context;
    }

    private List<String> resolveIncidentFocusAreas(List<String> focusAreas) {
        if (focusAreas != null && !focusAreas.isEmpty()) {
            return focusAreas;
        }
        return List.of("incident", "code_location", "knowledge_rag", "bug_fix", "test_verification", "release_risk");
    }

    private String validateIncidentRequest(CodeOpsIncidentFixSubmitRequestDTO request) {
        if (request == null) {
            return "request body cannot be null";
        }
        if (isBlank(request.getProblem())) {
            return "problem cannot be blank";
        }
        if (request.getProblem().length() > 4000) {
            return "problem length must be <= 4000";
        }
        if (request.getMaxRounds() != null && (request.getMaxRounds() < 1 || request.getMaxRounds() > 12)) {
            return "maxRounds must be between 1 and 12";
        }
        if (request.getMaxToolCalls() != null && (request.getMaxToolCalls() < 1 || request.getMaxToolCalls() > 50)) {
            return "maxToolCalls must be between 1 and 50";
        }
        return null;
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String value(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
