package com.opsautoagent.domain.codeops.agent.fixture;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.opsautoagent.domain.codeops.agent.evidence.IncidentEvidenceExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class IncidentFixtureEvidenceService {

    private final IncidentEvidenceExtractor evidenceExtractor;

    public IncidentFixtureEvidenceService(IncidentEvidenceExtractor evidenceExtractor) {
        this.evidenceExtractor = evidenceExtractor;
    }

    public IncidentFixtureEvidence load(String fixtureCasePath) {
        if (isBlank(fixtureCasePath)) {
            return IncidentFixtureEvidence.builder()
                    .available(false)
                    .errorMessage("fixtureCase is blank")
                    .evidenceSources(List.of())
                    .codeHints(List.of())
                    .build();
        }
        Path casePath = resolveInputPath(fixtureCasePath);
        if (!Files.exists(casePath)) {
            return IncidentFixtureEvidence.builder()
                    .available(false)
                    .basePath(casePath.toString())
                    .errorMessage("fixtureCase does not exist: " + casePath)
                    .evidenceSources(List.of())
                    .codeHints(List.of())
                    .build();
        }
        try {
            JSONObject evalCase = readJson(casePath);
            JSONObject fixtures = evalCase.getJSONObject("fixtures");
            Path root = casePath.getParent();
            Map<String, Object> alert = readFixture(root, fixtures, "alert");
            Map<String, Object> prometheus = readFixture(root, fixtures, "prometheus");
            Map<String, Object> logs = readFixture(root, fixtures, "logs");
            Map<String, Object> trace = readFixture(root, fixtures, "trace");
            String allText = JSON.toJSONString(Map.of(
                    "alert", alert,
                    "prometheus", prometheus,
                    "logs", logs,
                    "trace", trace
            ));
            return IncidentFixtureEvidence.builder()
                    .available(true)
                    .caseId(evalCase.getString("caseId"))
                    .basePath(root == null ? "" : root.toString())
                    .alert(alert)
                    .prometheus(prometheus)
                    .logs(logs)
                    .trace(trace)
                    .evidenceSources(buildEvidenceSources(alert, prometheus, logs, trace))
                    .codeHints(evidenceExtractor.extractCodeHints(allText))
                    .reportSummary(buildReportSummary(evalCase, alert, prometheus, logs, trace))
                    .build();
        } catch (Exception e) {
            log.warn("Load incident fixture evidence failed. fixtureCase={}", casePath, e);
            return IncidentFixtureEvidence.builder()
                    .available(false)
                    .basePath(casePath.toString())
                    .errorMessage(e.getMessage())
                    .evidenceSources(List.of())
                    .codeHints(List.of())
                    .build();
        }
    }

    private Map<String, Object> readFixture(Path root, JSONObject fixtures, String key) throws IOException {
        if (fixtures == null || isBlank(fixtures.getString(key))) {
            return Map.of();
        }
        Path path = resolveFixturePath(root, fixtures.getString(key));
        if (!Files.exists(path)) {
            return Map.of("missingFixture", path.toString());
        }
        return new LinkedHashMap<>(readJson(path));
    }

    private Path resolveFixturePath(Path root, String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cwdPath = cwd.resolve(rawPath).normalize();
        if (Files.exists(cwdPath)) {
            return cwdPath;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentPath = parent.resolve(rawPath).normalize();
            if (Files.exists(parentPath)) {
                return parentPath;
            }
        }
        return (root == null ? Path.of("").toAbsolutePath() : root).resolve(rawPath).normalize();
    }

    private Path resolveInputPath(String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cwdPath = cwd.resolve(rawPath).normalize();
        if (Files.exists(cwdPath)) {
            return cwdPath;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentPath = parent.resolve(rawPath).normalize();
            if (Files.exists(parentPath)) {
                return parentPath;
            }
        }
        Path grandParent = parent == null ? null : parent.getParent();
        if (grandParent != null) {
            Path grandParentPath = grandParent.resolve(rawPath).normalize();
            if (Files.exists(grandParentPath)) {
                return grandParentPath;
            }
        }
        return cwdPath;
    }

    private JSONObject readJson(Path path) throws IOException {
        return JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
    }

    private List<String> buildEvidenceSources(Map<String, Object> alert,
                                              Map<String, Object> prometheus,
                                              Map<String, Object> logs,
                                              Map<String, Object> trace) {
        List<String> sources = new ArrayList<>();
        if (alert != null && !alert.isEmpty()) {
            sources.add("Alertmanager fixture");
        }
        if (prometheus != null && !prometheus.isEmpty()) {
            sources.add("Prometheus fixture");
        }
        if (logs != null && !logs.isEmpty()) {
            sources.add("Elasticsearch logs fixture");
        }
        if (trace != null && !trace.isEmpty()) {
            sources.add("SkyWalking trace fixture");
        }
        return sources;
    }

    private String buildReportSummary(JSONObject evalCase,
                                      Map<String, Object> alert,
                                      Map<String, Object> prometheus,
                                      Map<String, Object> logs,
                                      Map<String, Object> trace) {
        List<String> parts = new ArrayList<>();
        parts.add("fixtureCase=" + value(evalCase.getString("caseId")));
        parts.add("alert=" + abbreviate(JSON.toJSONString(alert), 260));
        parts.add("metrics=" + abbreviate(JSON.toJSONString(prometheus), 260));
        parts.add("logs=" + abbreviate(JSON.toJSONString(logs), 360));
        parts.add("trace=" + abbreviate(JSON.toJSONString(trace), 320));
        return String.join("\n", parts);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
