package com.opsautoagent.domain.codeops.agent.evidence;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IncidentEvidenceExtractor {

    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*Exception)");

    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile("at\\s+(([a-zA-Z_$][\\w$]*\\.)+([A-Z][A-Za-z0-9_$]*)\\.([a-zA-Z_$][\\w$]*)\\(([^:()]+\\.java):(\\d+)\\))");

    private static final Pattern JAVA_SYMBOL_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(Controller|Service|Repository|Mapper|Client|Gateway|Request|Response))\\b");

    private static final Pattern METHOD_OPERATION_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*(Controller|Service|Repository|Mapper|Client|Gateway))\\.([a-zA-Z_$][\\w$]*)\\b");

    private static final Pattern ENDPOINT_PATTERN = Pattern.compile("\\b(?:GET|POST|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_./{}-]+)|(/api/[a-zA-Z0-9_./{}-]+|/[a-zA-Z0-9_./{}-]+)");

    public List<String> extractCodeHints(Object evidence) {
        return extractCodeHints(evidence == null ? "" : JSON.toJSONString(evidence));
    }

    public List<String> extractCodeHints(String evidenceText) {
        if (isBlank(evidenceText)) {
            return List.of();
        }
        Set<String> hints = new LinkedHashSet<>();
        addStackFrameHints(hints, evidenceText);
        addExceptionHints(hints, evidenceText);
        addMethodOperationHints(hints, evidenceText);
        addMatches(hints, evidenceText, JAVA_SYMBOL_PATTERN);
        addEndpointHints(hints, evidenceText);
        return hints.stream()
                .map(this::normalizeHint)
                .filter(value -> !isBlank(value) && !isGenericJavaRole(value))
                .distinct()
                .limit(40)
                .toList();
    }

    public Map<String, Object> extractStructuredSignals(Object evidence) {
        List<String> hints = extractCodeHints(evidence);
        return Map.of(
                "codeHints", hints,
                "extractor", "IncidentEvidenceExtractor",
                "signalCount", hints.size()
        );
    }

    private void addStackFrameHints(Set<String> hints, String text) {
        Matcher matcher = STACK_FRAME_PATTERN.matcher(text);
        while (matcher.find() && hints.size() < 80) {
            hints.add(matcher.group(1));
            hints.add(matcher.group(3));
            hints.add(matcher.group(4));
            hints.add(matcher.group(5));
            hints.add(matcher.group(5) + ":" + matcher.group(6));
        }
    }

    private void addExceptionHints(Set<String> hints, String text) {
        Matcher matcher = EXCEPTION_PATTERN.matcher(text);
        while (matcher.find() && hints.size() < 80) {
            String exception = matcher.group(1);
            hints.add(exception);
            int lastDot = exception.lastIndexOf('.');
            if (lastDot >= 0 && lastDot < exception.length() - 1) {
                hints.add(exception.substring(lastDot + 1));
            }
        }
    }

    private void addMethodOperationHints(Set<String> hints, String text) {
        Matcher matcher = METHOD_OPERATION_PATTERN.matcher(text);
        while (matcher.find() && hints.size() < 80) {
            hints.add(matcher.group(1));
            hints.add(matcher.group(2));
            hints.add(matcher.group(1) + "." + matcher.group(2));
        }
    }

    private void addEndpointHints(Set<String> hints, String text) {
        Matcher matcher = ENDPOINT_PATTERN.matcher(text);
        while (matcher.find() && hints.size() < 80) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                if (!isBlank(matcher.group(i))) {
                    hints.add(matcher.group(i));
                    break;
                }
            }
        }
    }

    private void addMatches(Set<String> hints, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && hints.size() < 80) {
            hints.add(matcher.group(1));
        }
    }

    private String normalizeHint(String value) {
        if (value == null) {
            return "";
        }
        String hint = value.trim();
        while (hint.startsWith("\"") || hint.startsWith("'")) {
            hint = hint.substring(1).trim();
        }
        while (hint.endsWith("\"") || hint.endsWith("'") || hint.endsWith(",") || hint.endsWith(".")) {
            hint = hint.substring(0, hint.length() - 1).trim();
        }
        return hint;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isGenericJavaRole(String value) {
        return "Controller".equals(value)
                || "Service".equals(value)
                || "Repository".equals(value)
                || "Mapper".equals(value)
                || "Client".equals(value)
                || "Gateway".equals(value);
    }
}
