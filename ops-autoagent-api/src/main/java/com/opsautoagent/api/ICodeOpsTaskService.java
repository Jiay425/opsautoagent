package com.opsautoagent.api;

import com.opsautoagent.api.dto.CodeOpsSkillDTO;
import com.opsautoagent.api.dto.CodeOpsTaskDTO;
import com.opsautoagent.api.dto.CodeOpsTaskSubmitRequestDTO;
import com.opsautoagent.api.dto.CodeOpsTaskTraceDTO;
import com.opsautoagent.api.response.Response;

import java.util.List;

/**
 * CodeOps engineering task API.
 */
public interface ICodeOpsTaskService {

    Response<CodeOpsTaskDTO> submitTask(CodeOpsTaskSubmitRequestDTO request);

    Response<CodeOpsTaskDTO> queryTask(String taskId);

    Response<CodeOpsTaskTraceDTO> queryTaskTrace(String taskId);

    Response<List<CodeOpsSkillDTO>> listSkills();

}
