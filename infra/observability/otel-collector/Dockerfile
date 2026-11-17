FROM otel/opentelemetry-collector-contrib:0.137.0 AS collector
FROM alpine:3.22.1
RUN addgroup -S otel && adduser -S -G otel otel
COPY --from=collector /otelcol-contrib /otelcol-contrib
USER otel
ENTRYPOINT ["/otelcol-contrib"]
