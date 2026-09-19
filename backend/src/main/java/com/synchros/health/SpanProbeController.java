package com.Synchros.health;

import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Opt-in span probe: emits one OTel span directly through the SDK Tracer
 * bean, bypassing HTTP-observation sampling. Used by the tracing
 * verification drill (tools/smoke.ps1 -Tracing): if this span reaches the
 * collector, exporter wiring is proven independently of server
 * instrumentation config. Dormant unless SPAN_PROBE_ENABLED=true —
 * a prod deployment never registers the endpoint.
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "Synchros.span-probe.enabled", havingValue = "true")
public class SpanProbeController {

    private final Tracer tracer;

    public SpanProbeController(Tracer tracer) {
        this.tracer = tracer;
    }

    @PostMapping("/span-probe")
    public Map<String, String> probe() {
        var span = tracer.spanBuilder("Synchros.span-probe").startSpan();
        try (var scope = span.makeCurrent()) {
            span.setAttribute("probe", "manual");
        } finally {
            span.end();
        }
        return Map.of("status", "span-ended");
    }
}
