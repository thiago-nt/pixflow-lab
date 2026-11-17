package dev.pixflow.lab.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

class TraceContextSnapshotTest {

    @Test
    void capturesAndRestoresW3cContext() {
        var spanContext = SpanContext.create(
                "7b35f4f66373408a26f9e56a8144f3ef",
                "72d78becdbe1dc70",
                TraceFlags.getSampled(),
                TraceState.getDefault());

        try (var ignored = Context.current().with(Span.wrap(spanContext)).makeCurrent()) {
            var snapshot = TraceContextSnapshot.capture();

            assertEquals("00-7b35f4f66373408a26f9e56a8144f3ef-72d78becdbe1dc70-01", snapshot.traceparent());
            assertTrue(snapshot.isValid());
            var restored = Span.fromContext(snapshot.extract()).getSpanContext();
            assertEquals(spanContext.getTraceId(), restored.getTraceId());
            assertEquals(spanContext.getSpanId(), restored.getSpanId());
            assertEquals(spanContext.getTraceFlags(), restored.getTraceFlags());
            assertTrue(restored.isRemote());
        }
    }

    @Test
    void acceptsLegacyEventWithoutTraceContext() {
        var snapshot = new TraceContextSnapshot(" ", null);

        assertNull(snapshot.traceparent());
        assertNull(snapshot.tracestate());
        assertFalse(snapshot.isValid());
    }
}
