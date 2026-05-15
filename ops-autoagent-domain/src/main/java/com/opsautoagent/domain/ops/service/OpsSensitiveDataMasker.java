package com.opsautoagent.domain.ops.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class OpsSensitiveDataMasker {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)(api[-_ ]?key|token|authorization|password|secret)([\\\"'\\s:=]+)([^\\\"'\\s,}]+)");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[a-z0-9._\\-]+");
    private static final Pattern SK_PATTERN = Pattern.compile("sk-[a-zA-Z0-9]{8,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    public String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = API_KEY_PATTERN.matcher(value).replaceAll("$1$2***");
        masked = BEARER_PATTERN.matcher(masked).replaceAll("Bearer ***");
        masked = SK_PATTERN.matcher(masked).replaceAll("sk-***");
        masked = PHONE_PATTERN.matcher(masked).replaceAll("***PHONE***");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("***EMAIL***");
        return masked;
    }

}

