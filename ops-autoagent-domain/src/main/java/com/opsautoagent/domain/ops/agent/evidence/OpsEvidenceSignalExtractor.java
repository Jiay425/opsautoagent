package com.opsautoagent.domain.ops.agent.evidence;

import com.opsautoagent.domain.ops.model.entity.EvidenceSignalEntity;
import com.opsautoagent.domain.ops.model.entity.IncidentCommandEntity;
import com.opsautoagent.domain.ops.model.entity.LogEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.MetricEvidenceEntity;
import com.opsautoagent.domain.ops.model.entity.TraceEvidenceEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class OpsEvidenceSignalExtractor {

    public List<EvidenceSignalEntity> extract(IncidentCommandEntity command,
                                              MetricEvidenceEntity metricEvidence,
                                              LogEvidenceEntity logEvidence,
                                              TraceEvidenceEntity traceEvidence) {
        List<EvidenceSignalEntity> signals = new ArrayList<>();
        extractMetrics(command, metricEvidence, signals);
        extractLogs(command, logEvidence, signals);
        extractTraces(command, traceEvidence, signals);
        return signals;
    }

    private void extractMetrics(IncidentCommandEntity command,
                                MetricEvidenceEntity evidence,
                                List<EvidenceSignalEntity> signals) {
        if (evidence == null || evidence.getObservations() == null || evidence.getObservations().isEmpty()) {
            signals.add(signal(command, "prometheus", "metric", "prometheus_collection",
                    available(evidence) ? "NO_ANOMALY" : "UNAVAILABLE", "unknown",
                    summary(evidence), raw(evidence)));
            return;
        }
        for (String observation : evidence.getObservations()) {
            if (isBlank(observation)) {
                continue;
            }
            String status = metricStatus(observation);
            String name = metricName(observation);
            signals.add(signal(command, "prometheus", "metric", name, status, severity(status),
                    observation, observation));
        }
    }

    private void extractLogs(IncidentCommandEntity command,
                             LogEvidenceEntity evidence,
                             List<EvidenceSignalEntity> signals) {
        if (evidence == null || evidence.getErrorSamples() == null || evidence.getErrorSamples().isEmpty()) {
            signals.add(signal(command, "elasticsearch", "log", "log_collection",
                    available(evidence) ? "NO_ANOMALY" : "UNAVAILABLE", "unknown",
                    summary(evidence), raw(evidence)));
            return;
        }
        int index = 1;
        for (String sample : evidence.getErrorSamples()) {
            if (isBlank(sample)) {
                continue;
            }
            signals.add(signal(command, "elasticsearch", "log", "log_sample_" + index++,
                    "OBSERVED", "medium", sample, sample));
        }
    }

    private void extractTraces(IncidentCommandEntity command,
                               TraceEvidenceEntity evidence,
                               List<EvidenceSignalEntity> signals) {
        if (evidence == null || evidence.getSpans() == null || evidence.getSpans().isEmpty()) {
            signals.add(signal(command, "skywalking", "trace", "trace_collection",
                    available(evidence) ? "NO_ANOMALY" : "UNAVAILABLE", "unknown",
                    summary(evidence), raw(evidence)));
            return;
        }
        int index = 1;
        for (String span : evidence.getSpans()) {
            if (isBlank(span)) {
                continue;
            }
            signals.add(signal(command, "skywalking", "trace", "trace_span_" + index++,
                    "OBSERVED", "medium", span, span));
        }
    }

    private EvidenceSignalEntity signal(IncidentCommandEntity command,
                                        String source,
                                        String evidenceType,
                                        String name,
                                        String status,
                                        String severity,
                                        String summary,
                                        String rawEvidence) {
        return EvidenceSignalEntity.builder()
                .signalId("signal-" + UUID.randomUUID())
                .source(source)
                .evidenceType(evidenceType)
                .name(blankToDefault(name, evidenceType + "_evidence"))
                .status(blankToDefault(status, "OBSERVED"))
                .entity(command == null ? "" : command.getServiceName())
                .severity(blankToDefault(severity, "unknown"))
                .timeWindow(timeWindow(command))
                .summary(blankToDefault(summary, ""))
                .rawEvidence(blankToDefault(rawEvidence, ""))
                .build();
    }

    private String metricStatus(String observation) {
        String lower = observation.toLowerCase(Locale.ROOT);
        if (lower.startsWith("anomaly:")) {
            return "ANOMALY";
        }
        if (lower.startsWith("no_data:")) {
            return "NO_DATA";
        }
        if (lower.startsWith("unknown:")) {
            return "UNKNOWN";
        }
        if (lower.startsWith("ok:")) {
            return "OK";
        }
        return "OBSERVED";
    }

    private String severity(String status) {
        return "ANOMALY".equals(status) ? "high" : "informational";
    }

    private String metricName(String observation) {
        String value = observation == null ? "" : observation.trim();
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1).trim();
        }
        int space = value.indexOf(' ');
        if (space > 0) {
            value = value.substring(0, space);
        }
        return blankToDefault(value, "metric_observation");
    }

    private boolean available(MetricEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private boolean available(LogEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private boolean available(TraceEvidenceEntity evidence) {
        return evidence != null && evidence.isAvailable();
    }

    private String summary(MetricEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String summary(LogEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String summary(TraceEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getSummary();
    }

    private String raw(MetricEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getRawData();
    }

    private String raw(LogEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getRawData();
    }

    private String raw(TraceEvidenceEntity evidence) {
        return evidence == null ? "" : evidence.getRawData();
    }

    private String timeWindow(IncidentCommandEntity command) {
        if (command == null) {
            return "";
        }
        return blankToDefault(command.getStartTime(), "") + " ~ " + blankToDefault(command.getEndTime(), "");
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

