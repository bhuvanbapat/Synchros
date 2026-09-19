package com.Synchros.config;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Explicit OTLP span-exporter wiring. Spring Boot 4's actuator OTLP
 * plumbing targets metrics; the trace exporter bean is not auto-registered
 * by the tracing starters alone, so it is declared here — created ONLY
 * when OTEL_EXPORTER_OTLP_ENDPOINT is set to a non-blank value. Blank or
 * absent endpoint => no bean => tracing stays dormant with zero export
 * overhead (the always-on baseline remains request/event correlation
 * IDs, see docs/OBSERVABILITY.md).
 *
 * Transport: HTTP protobuf POST to <endpoint>/v1/traces (collector's
 * OTLP HTTP receiver; 4318 in the shipped collector config).
 *
 * NOTE: @ConditionalOnProperty alone is WRONG here — it treats an
 * empty-string property as present, which builds an exporter with a
 * blank endpoint and throws "Invalid endpoint" at boot. The custom
 * NonBlankPropertyCondition exists for exactly that failure mode.
 */
@Configuration
public class TracingExportConfig {

    /**
     * Created ONLY when management.otlp.tracing.endpoint is set to a
     * non-blank value. The inner static condition class implements
     * Condition so the check reads the real property value (blank string
     * must mean "dormant", which @ConditionalOnProperty would get wrong).
     */
    @org.springframework.context.annotation.Conditional(TracingEnabled.class)
    @Bean
    @ConditionalOnMissingBean(io.opentelemetry.sdk.trace.export.SpanExporter.class)
    io.opentelemetry.sdk.trace.export.SpanExporter otlpSpanExporter(
            @Value("${management.otlp.tracing.endpoint}") String endpoint) {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(normalize(endpoint))
                .build();
    }

    static class TracingEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment()
                    .getProperty("management.otlp.tracing.endpoint");
            return value != null && !value.isBlank();
        }
    }

    /**
     * Accepts either a base ("http://collector:4318") — the exporter
     * appends /v1/traces — or a full signal URL. Keeps .env/compose
     * values simple while staying compatible with explicit URLs.
     */
    private static String normalize(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "OTLP endpoint must not be blank — unset OTEL_EXPORTER_OTLP_ENDPOINT to disable tracing");
        }
        if (endpoint.endsWith("/v1/traces")) {
            return endpoint;
        }
        return endpoint.endsWith("/") ? endpoint + "v1/traces" : endpoint + "/v1/traces";
    }
}
