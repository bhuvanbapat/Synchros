package com.synchros.kafka;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Consumers register only when synchros.kafka.enabled is true
 * (default true; tests and Redis/Kafka-less profiles set it false).
 * The outbox publisher is likewise conditional.
 */
public class ConditionalOnKafkaEnabled implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String enabled = context.getEnvironment()
                .getProperty("synchros.kafka.enabled", "true");
        return Boolean.parseBoolean(enabled);
    }
}
