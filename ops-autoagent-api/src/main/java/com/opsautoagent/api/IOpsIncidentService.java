package com.opsautoagent.api;

import com.opsautoagent.api.dto.OpsIncidentAnalyzeRequestDTO;
import com.opsautoagent.api.dto.OpsIncidentDiagnosisRecordDTO;
import com.opsautoagent.api.response.Response;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * Intelligent ops incident diagnosis API.
 */
public interface IOpsIncidentService {

    ResponseBodyEmitter analyzeIncident(OpsIncidentAnalyzeRequestDTO request, HttpServletResponse response);

    Response<OpsIncidentDiagnosisRecordDTO> queryDiagnosisRecord(String diagnosisId);

}

