package com.uber.api.shared.saga;

public enum SagaStatus {
    STARTED,
    PROCESSING,
    SUCCEEDED,
    COMPENSATING,
    COMPENSATED,
    FAILED
}
