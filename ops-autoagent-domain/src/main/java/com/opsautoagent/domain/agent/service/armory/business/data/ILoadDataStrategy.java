package com.opsautoagent.domain.agent.service.armory.business.data;

import com.opsautoagent.domain.agent.model.entity.ArmoryCommandEntity;
import com.opsautoagent.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;

import java.util.List;

/**
 * 数据加载策略
 *
 * @author ops-autoagent ops-autoagent.local @Ops AutoAgent
 * 2025/6/27 17:16
 */
public interface ILoadDataStrategy {

    void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext);

}


