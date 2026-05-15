package com.opsautoagent.infrastructure.adapter.gateway;

import com.opsautoagent.domain.ops.adapter.gateway.IOpsEmailGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class OpsEmailGateway implements IOpsEmailGateway {

    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public OpsEmailGateway(ObjectProvider<JavaMailSender> javaMailSenderProvider) {
        this.javaMailSenderProvider = javaMailSenderProvider;
    }

    @Override
    public void sendEmail(String to, String subject, String content) {
        JavaMailSender sender = javaMailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("JavaMailSender is unavailable. Please configure spring.mail.*");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        if (!isBlank(fromAddress)) {
            message.setFrom(fromAddress.trim());
        }
        message.setTo(parseRecipients(to));
        message.setSubject(subject);
        message.setText(content);
        sender.send(message);
    }

    private String[] parseRecipients(String to) {
        if (isBlank(to)) {
            throw new IllegalArgumentException("email receiver is blank");
        }
        return java.util.Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

