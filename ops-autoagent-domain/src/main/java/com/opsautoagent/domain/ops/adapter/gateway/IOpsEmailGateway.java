package com.opsautoagent.domain.ops.adapter.gateway;

public interface IOpsEmailGateway {

    void sendEmail(String to, String subject, String content);

}

