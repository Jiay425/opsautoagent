package com.opsautoagent.domain.ops.service.notify;

import com.opsautoagent.domain.ops.adapter.repository.IOpsNotificationRepository;
import com.opsautoagent.domain.ops.model.entity.OpsServiceOwnerEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Service
public class OpsServiceOwnerService {

    @Resource
    private IOpsNotificationRepository opsNotificationRepository;

    public OpsServiceOwnerEntity queryServiceOwner(String serviceName) {
        return opsNotificationRepository.queryServiceOwner(serviceName);
    }

}

