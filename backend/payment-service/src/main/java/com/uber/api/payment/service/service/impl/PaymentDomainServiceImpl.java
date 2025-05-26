package com.uber.api.payment.service.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.api.payment.service.entity.Balance;
import com.uber.api.payment.service.entity.PaymentOutbox;
import com.uber.api.payment.service.entity.Transaction;
import com.uber.api.payment.service.repository.BalanceRepository;
import com.uber.api.payment.service.repository.PaymentOutboxRepository;
import com.uber.api.payment.service.repository.TransactionRepository;
import com.uber.api.payment.service.service.PaymentDomainService;
import com.uber.api.shared.constants.PaymentStatus;
import com.uber.api.shared.events.PaymentRequestEvent;
import com.uber.api.shared.events.PaymentResponseEvent;
import com.uber.api.shared.events.PaymentRefundEvent;
import com.uber.api.shared.outbox.OutboxStatus;
import com.uber.api.shared.saga.SagaStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentDomainServiceImpl implements PaymentDomainService {

    private final BalanceRepository balanceRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public PaymentResponseEvent processPayment(PaymentRequestEvent paymentRequest) {
        log.info("Processing payment for customer: {} amount: {}",
                paymentRequest.getCustomerEmail(), paymentRequest.getAmount());

        try {
            // **FIX 1: Atomic balance check and deduction**
            Balance balance = findOrCreateBalance(paymentRequest.getCustomerEmail());

            if (!balance.hasSufficientBalance(paymentRequest.getAmount())) {
                log.warn("Insufficient balance for customer: {} required: {} available: {}",
                        paymentRequest.getCustomerEmail(), paymentRequest.getAmount(), balance.getAmount());

                return createFailedPaymentResponse(paymentRequest, "Insufficient balance");
            }

            // **FIX 2: Atomic balance deduction with optimistic locking**
            balance.deductAmount(paymentRequest.getAmount());
            balanceRepository.save(balance);

            // Create successful transaction record
            Transaction transaction = Transaction.builder()
                    .customerEmail(paymentRequest.getCustomerEmail())
                    .rideRequestId(paymentRequest.getRideRequestId())
                    .sagaId(paymentRequest.getSagaId())
                    .amount(paymentRequest.getAmount())
                    .status(PaymentStatus.COMPLETED)
                    .type(Transaction.TransactionType.DEBIT)
                    .description(paymentRequest.getDescription())
                    .createdAt(ZonedDateTime.now())
                    .processedAt(ZonedDateTime.now())
                    .build();

            transactionRepository.save(transaction);

            // Create successful payment response
            PaymentResponseEvent response = PaymentResponseEvent.builder()
                    .sagaId(paymentRequest.getSagaId())
                    .rideRequestId(paymentRequest.getRideRequestId())
                    .customerEmail(paymentRequest.getCustomerEmail())
                    .amount(paymentRequest.getAmount())
                    .status(PaymentStatus.COMPLETED)
                    .build();

            saveToOutbox(response, paymentRequest.getSagaId(), "payment-responses");

            log.info("Payment processed successfully for customer: {} amount: {}",
                    paymentRequest.getCustomerEmail(), paymentRequest.getAmount());

            return response;

        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic locking failure during payment processing, retrying...");
            throw e; // Will be retried by @Retryable
        } catch (Exception e) {
            log.error("Error processing payment for customer: {}", paymentRequest.getCustomerEmail(), e);
            return createFailedPaymentResponse(paymentRequest, "Payment processing failed: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    @Retryable(value = {OptimisticLockingFailureException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public PaymentResponseEvent refundPayment(PaymentRequestEvent refundRequest) {
        log.info("Processing refund for customer: {} amount: {}",
                refundRequest.getCustomerEmail(), refundRequest.getAmount());

        try {
            // **FIX 3: Atomic refund processing**
            Balance balance = findOrCreateBalance(refundRequest.getCustomerEmail());

            // Add refund amount to balance
            balance.addAmount(refundRequest.getAmount());
            balanceRepository.save(balance);

            // Create refund transaction record
            Transaction transaction = Transaction.builder()
                    .customerEmail(refundRequest.getCustomerEmail())
                    .rideRequestId(refundRequest.getRideRequestId())
                    .sagaId(refundRequest.getSagaId())
                    .amount(refundRequest.getAmount())
                    .status(PaymentStatus.COMPLETED)
                    .type(Transaction.TransactionType.REFUND)
                    .description("Refund: " + refundRequest.getDescription())
                    .createdAt(ZonedDateTime.now())
                    .processedAt(ZonedDateTime.now())
                    .build();

            transactionRepository.save(transaction);

            // Create refund response
            PaymentResponseEvent response = PaymentResponseEvent.builder()
                    .sagaId(refundRequest.getSagaId())
                    .rideRequestId(refundRequest.getRideRequestId())
                    .customerEmail(refundRequest.getCustomerEmail())
                    .amount(refundRequest.getAmount())
                    .status(PaymentStatus.COMPLETED)
                    .build();

            saveToOutbox(response, refundRequest.getSagaId(), "payment-responses");

            log.info("Refund processed successfully for customer: {} amount: {}",
                    refundRequest.getCustomerEmail(), refundRequest.getAmount());

            return response;

        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic locking failure during refund processing, retrying...");
            throw e; // Will be retried by @Retryable
        } catch (Exception e) {
            log.error("Error processing refund for customer: {}", refundRequest.getCustomerEmail(), e);
            return createFailedPaymentResponse(refundRequest, "Refund processing failed: " + e.getMessage());
        }
    }

    /**
     * **FIX 4: Handle refund requests from Kafka**
     */
    @Transactional
    public void processRefundRequest(PaymentRefundEvent refundEvent) {
        log.info("Processing refund request for customer: {} amount: {}",
                refundEvent.getCustomerEmail(), refundEvent.getAmount());

        try {
            Balance balance = findOrCreateBalance(refundEvent.getCustomerEmail());

            // Add refund amount to balance
            balance.addAmount(refundEvent.getAmount());
            balanceRepository.save(balance);

            // Create refund transaction
            Transaction transaction = Transaction.builder()
                    .customerEmail(refundEvent.getCustomerEmail())
                    .rideRequestId(refundEvent.getRideRequestId())
                    .sagaId(refundEvent.getSagaId())
                    .amount(refundEvent.getAmount())
                    .status(PaymentStatus.COMPLETED)
                    .type(Transaction.TransactionType.REFUND)
                    .description("Refund: " + refundEvent.getReason())
                    .createdAt(ZonedDateTime.now())
                    .processedAt(ZonedDateTime.now())
                    .build();

            transactionRepository.save(transaction);

            log.info("Refund processed successfully for customer: {} amount: {}",
                    refundEvent.getCustomerEmail(), refundEvent.getAmount());

        } catch (Exception e) {
            log.error("Error processing refund request for customer: {}", refundEvent.getCustomerEmail(), e);
        }
    }

    @Override
    public BigDecimal getBalance(String customerEmail) {
        return findOrCreateBalance(customerEmail).getAmount();
    }

    @Override
    @Transactional
    public void addBalance(String customerEmail, BigDecimal amount) {
        Balance balance = findOrCreateBalance(customerEmail);
        balance.addAmount(amount);
        balanceRepository.save(balance);

        log.info("Added {} to balance for customer: {}", amount, customerEmail);
    }

    private Balance findOrCreateBalance(String customerEmail) {
        return balanceRepository.findByCustomerEmail(customerEmail)
                .orElseGet(() -> {
                    Balance newBalance = Balance.builder()
                            .customerEmail(customerEmail)
                            .amount(BigDecimal.valueOf(100.00)) // Default balance $100
                            .lastUpdated(ZonedDateTime.now())
                            .build();
                    return balanceRepository.save(newBalance);
                });
    }

    private PaymentResponseEvent createFailedPaymentResponse(PaymentRequestEvent request, String failureReason) {
        // Create failed transaction record
        Transaction transaction = Transaction.builder()
                .customerEmail(request.getCustomerEmail())
                .rideRequestId(request.getRideRequestId())
                .sagaId(request.getSagaId())
                .amount(request.getAmount())
                .status(PaymentStatus.FAILED)
                .type(Transaction.TransactionType.DEBIT)
                .description(request.getDescription())
                .createdAt(ZonedDateTime.now())
                .processedAt(ZonedDateTime.now())
                .build();

        transactionRepository.save(transaction);

        PaymentResponseEvent response = PaymentResponseEvent.builder()
                .sagaId(request.getSagaId())
                .rideRequestId(request.getRideRequestId())
                .customerEmail(request.getCustomerEmail())
                .amount(request.getAmount())
                .status(PaymentStatus.FAILED)
                .failureReason(failureReason)
                .build();

        saveToOutbox(response, request.getSagaId(), "payment-responses");

        return response;
    }

    private void saveToOutbox(Object event, UUID sagaId, String eventType) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            PaymentOutbox outboxEvent = PaymentOutbox.builder()
                    .sagaId(sagaId)
                    .eventType(eventType)
                    .payload(payload)
                    .status(OutboxStatus.PENDING)
                    .sagaStatus(SagaStatus.PROCESSING)
                    .createdAt(ZonedDateTime.now())
                    .build();

            paymentOutboxRepository.save(outboxEvent);

        } catch (Exception e) {
            log.error("Failed to save event to outbox", e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }
    }
}
