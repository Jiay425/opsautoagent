package com.opsautoagent.domain.common.tree;

@FunctionalInterface
public interface StrategyHandler<T, D, R> {

    R apply(T requestParameter, D dynamicContext) throws Exception;

}

