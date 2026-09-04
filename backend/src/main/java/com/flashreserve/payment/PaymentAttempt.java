package com.flashreserve.payment;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(nullable = false)
    private String outcome;   // SUCCESS | FAILURE | TIMEOUT | DUPLICATE_CALLBACK

    @Column(name = "provider_ref")
    private String providerRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PaymentAttempt() {
    }

    public PaymentAttempt(Long paymentId, String outcome, String providerRef) {
        this.paymentId = paymentId;
        this.outcome = outcome;
        this.providerRef = providerRef;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getOutcome() { return outcome; }
    public String getProviderRef() { return providerRef; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
