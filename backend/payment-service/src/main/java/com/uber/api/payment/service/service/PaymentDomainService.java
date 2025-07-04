package com.uber.api.payment.service.service;

import com.uber.api.shared.events.PaymentRequestEvent;
import com.uber.api.shared.events.PaymentResponseEvent;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentDomainService {
    PaymentResponseEvent processPayment(PaymentRequestEvent paymentRequest);
    PaymentResponseEvent refundPayment(PaymentRequestEvent refundRequest);
    BigDecimal getBalance(String customerEmail);
    void addBalance(String customerEmail, BigDecimal amount);
    void saveToOutbox(Object event, UUID sagaId, String eventType);
}
