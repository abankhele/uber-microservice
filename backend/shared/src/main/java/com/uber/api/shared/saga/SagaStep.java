package com.uber.api.shared.saga;

public interface SagaStep<T> {
    void process(T data);
    void rollback(T data);
}
