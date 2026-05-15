package com.opsautoagent.domain.agent.service.armory;

import com.opsautoagent.domain.agent.model.entity.ArmoryCommandEntity;
import com.opsautoagent.domain.agent.model.valobj.enums.AiAgentEnumVO;
import com.opsautoagent.domain.agent.model.valobj.AiClientApiVO;
import com.opsautoagent.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import com.opsautoagent.domain.common.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAI API配置节点
 *
 * @author ops-autoagent ops-autoagent.local @Ops AutoAgent
 * 2025/7/1 07:09
 */
@Slf4j
@Service
public class AiClientApiNode extends AbstractArmorySupport {

    @Resource
    private AiClientToolMcpNode aiClientToolMcpNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，API 接口请求{}", JSON.toJSONString(requestParameter));

        List<AiClientApiVO> aiClientApiList = dynamicContext.getValue(dataName());

        if (aiClientApiList == null || aiClientApiList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client api");
            return router(requestParameter, dynamicContext);
        }

        for (AiClientApiVO aiClientApiVO : aiClientApiList) {
            // 构建 OpenAiApi
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(resolvePlaceholder(aiClientApiVO.getBaseUrl()))
                    .apiKey(resolvePlaceholder(aiClientApiVO.getApiKey()))
                    .completionsPath(resolvePlaceholder(aiClientApiVO.getCompletionsPath()))
                    .embeddingsPath(resolvePlaceholder(aiClientApiVO.getEmbeddingsPath()))
                    .build();

            // 注册 OpenAiApi Bean 对象
            registerBean(beanName(aiClientApiVO.getApiId()), OpenAiApi.class, openAiApi);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientToolMcpNode;
    }

    @Override
    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_API.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_API.getDataName();
    }

    private String resolvePlaceholder(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
            return value;
        }
        String body = trimmed.substring(2, trimmed.length() - 1);
        String key = body;
        String defaultValue = "";
        int defaultSeparator = body.indexOf(':');
        if (defaultSeparator >= 0) {
            key = body.substring(0, defaultSeparator);
            defaultValue = body.substring(defaultSeparator + 1);
        }
        String systemProperty = System.getProperty(key);
        if (systemProperty != null && !systemProperty.isBlank()) {
            return systemProperty;
        }
        String environmentValue = System.getenv(key);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return defaultValue;
    }

}



