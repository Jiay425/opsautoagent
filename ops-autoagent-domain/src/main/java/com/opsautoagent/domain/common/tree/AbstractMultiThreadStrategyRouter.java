package com.opsautoagent.domain.common.tree;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractMultiThreadStrategyRouter<T, D, R> implements StrategyHandler<T, D, R> {

    protected final StrategyHandler<T, D, R> defaultStrategyHandler = (requestParameter, dynamicContext) -> null;

    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        multiThread(requestParameter, dynamicContext);
        return doApply(requestParameter, dynamicContext);
    }

    protected R router(T requestParameter, D dynamicContext) throws Exception {
        StrategyHandler<T, D, R> next = get(requestParameter, dynamicContext);
        if (next == null) {
            return null;
        }
        return next.apply(requestParameter, dynamicContext);
    }

    protected void multiThread(T requestParameter, D dynamicContext)
            throws ExecutionException, InterruptedException, TimeoutException {
    }

    protected abstract R doApply(T requestParameter, D dynamicContext) throws Exception;

    public abstract StrategyHandler<T, D, R> get(T requestParameter, D dynamicContext) throws Exception;

}

