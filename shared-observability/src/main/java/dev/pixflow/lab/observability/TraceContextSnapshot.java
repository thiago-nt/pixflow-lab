package dev.pixflow.lab.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.HashMap;
import java.util.Map;

/** Persistable W3C trace context used to bridge the transactional Outbox boundary. */
public record TraceContextSnapshot(String traceparent, String tracestate) {
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };

    public TraceContextSnapshot {
        traceparent = blankToNull(traceparent);
        tracestate = blankToNull(tracestate);
    }

    public static TraceContextSnapshot capture() {
        var carrier = new HashMap<String, String>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, SETTER);
        return new TraceContextSnapshot(carrier.get("traceparent"), carrier.get("tracestate"));
    }

    public Context extract() {
        if (traceparent == null) return Context.root();
        var carrier = new HashMap<String, String>();
        carrier.put("traceparent", traceparent);
        if (tracestate != null) carrier.put("tracestate", tracestate);
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GETTER);
    }

    public boolean isValid() {
        return Span.fromContext(extract()).getSpanContext().isValid();
    }

    public void inject(Map<String, String> carrier) {
        if (traceparent != null) carrier.put("traceparent", traceparent);
        if (tracestate != null) carrier.put("tracestate", tracestate);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
