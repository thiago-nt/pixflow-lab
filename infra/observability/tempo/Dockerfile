FROM grafana/tempo:2.9.5 AS tempo
FROM alpine:3.22.1
RUN addgroup -S tempo && adduser -S -G tempo tempo && mkdir -p /var/tempo/wal /var/tempo/blocks && chown -R tempo:tempo /var/tempo
COPY --from=tempo /tempo /tempo
USER tempo
ENTRYPOINT ["/tempo"]
