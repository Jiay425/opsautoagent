package com.opsautoagent.infrastructure.adapter.repository;

import com.opsautoagent.domain.ops.adapter.repository.IOpsNotificationRepository;
import com.opsautoagent.domain.ops.model.entity.OpsNotificationRecordEntity;
import com.opsautoagent.domain.ops.model.entity.OpsServiceOwnerEntity;
import com.opsautoagent.infrastructure.dao.IOpsNotificationRecordDao;
import com.opsautoagent.infrastructure.dao.IOpsServiceOwnerDao;
import com.opsautoagent.infrastructure.dao.po.OpsNotificationRecord;
import com.opsautoagent.infrastructure.dao.po.OpsServiceOwner;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

@Repository
public class OpsNotificationRepository implements IOpsNotificationRepository {

    @Resource
    private IOpsServiceOwnerDao opsServiceOwnerDao;

    @Resource
    private IOpsNotificationRecordDao opsNotificationRecordDao;

    @Override
    public OpsServiceOwnerEntity queryServiceOwner(String serviceName) {
        OpsServiceOwner po = opsServiceOwnerDao.queryByServiceName(serviceName);
        if (po == null) {
            return null;
        }
        return OpsServiceOwnerEntity.builder()
                .id(po.getId())
                .serviceName(po.getServiceName())
                .ownerName(po.getOwnerName())
                .ownerEmail(po.getOwnerEmail())
                .ownerWecom(po.getOwnerWecom())
                .ownerDingTalk(po.getOwnerDingTalk())
                .backupOwnerEmail(po.getBackupOwnerEmail())
                .enabled(po.getEnabled())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }

    @Override
    public void saveNotificationRecord(OpsNotificationRecordEntity notificationRecord) {
        opsNotificationRecordDao.insert(OpsNotificationRecord.builder()
                .notificationId(notificationRecord.getNotificationId())
                .diagnosisId(notificationRecord.getDiagnosisId())
                .serviceName(notificationRecord.getServiceName())
                .channel(notificationRecord.getChannel())
                .receiver(notificationRecord.getReceiver())
                .severity(notificationRecord.getSeverity())
                .subject(notificationRecord.getSubject())
                .sendStatus(notificationRecord.getSendStatus())
                .retryCount(notificationRecord.getRetryCount())
                .errorMessage(notificationRecord.getErrorMessage())
                .sendTime(notificationRecord.getSendTime())
                .createTime(notificationRecord.getCreateTime())
                .build());
    }

}

