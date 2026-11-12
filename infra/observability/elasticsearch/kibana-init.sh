#!/bin/sh
set -eu

password="$(cat /run/secrets/elastic_password)"
curl -fsS -u "elastic:${password}" -H 'kbn-xsrf: pixflow-lab' -H 'Content-Type: application/json' \
  -X POST 'http://kibana:5601/api/data_views/data_view' \
  -d '{"data_view":{"id":"pixflow-logs","title":"pixflow-logs-*","timeFieldName":"timestamp","allowNoIndex":true},"override":true}' >/dev/null

echo "Kibana data view pixflow-logs-* configured; use KQL fields correlationId and paymentId."
