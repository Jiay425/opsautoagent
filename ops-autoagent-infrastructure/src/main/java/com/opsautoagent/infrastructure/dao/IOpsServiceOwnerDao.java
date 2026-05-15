package com.opsautoagent.infrastructure.dao;

import com.opsautoagent.infrastructure.dao.po.OpsServiceOwner;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IOpsServiceOwnerDao {

    OpsServiceOwner queryByServiceName(String serviceName);

}

