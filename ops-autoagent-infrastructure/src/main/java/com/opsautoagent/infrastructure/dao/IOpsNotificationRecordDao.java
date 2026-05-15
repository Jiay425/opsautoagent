package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsNotificationRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsNotificationRecordDao {

    int insert(OpsNotificationRecord notificationRecord);

}

