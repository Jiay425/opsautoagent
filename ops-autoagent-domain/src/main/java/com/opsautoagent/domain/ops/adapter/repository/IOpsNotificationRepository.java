package com.opsautoagent.domain.ops.adapter.repository;

import com.opsautoagent.domain.ops.model.entity.OpsNotificationRecordEntity;
import com.opsautoagent.domain.ops.model.entity.OpsServiceOwnerEntity;

public interface IOpsNotificationRepository {

    OpsServiceOwnerEntity queryServiceOwner(String serviceName);

    void saveNotificationRecord(OpsNotificationRecordEntity notificationRecord);

}

