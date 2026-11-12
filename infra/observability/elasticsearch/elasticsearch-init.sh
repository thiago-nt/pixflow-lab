#!/bin/sh
set -eu

password="$(cat /run/secrets/elastic_password)"
base="http://elasticsearch:9200"
auth="elastic:${password}"

curl -fsS -u "${auth}" -H 'Content-Type: application/json' -X POST \
  "${base}/_security/user/kibana_system/_password" \
  -d "{\"password\":\"${password}\"}" >/dev/null

curl -fsS -u "${auth}" -H 'Content-Type: application/json' -X PUT \
  "${base}/_security/role/pixflow_log_ingest" \
  -d '{"cluster":["monitor"],"indices":[{"names":["pixflow-logs-*"],"privileges":["auto_configure","create_index","create","index","write"]}]}' >/dev/null

curl -fsS -u "${auth}" -H 'Content-Type: application/json' -X POST \
  "${base}/_security/user/pixflow_ingest" \
  -d "{\"password\":\"${password}\",\"roles\":[\"pixflow_log_ingest\"]}" >/dev/null

curl -fsS -u "${auth}" -H 'Content-Type: application/json' -X PUT \
  "${base}/_ilm/policy/pixflow-short-retention" \
  -d '{"policy":{"phases":{"hot":{"actions":{}},"delete":{"min_age":"2d","actions":{"delete":{}}}}}}' >/dev/null

curl -fsS -u "${auth}" -H 'Content-Type: application/json' -X PUT \
  "${base}/_index_template/pixflow-logs" \
  -d '{"index_patterns":["pixflow-logs-*"],"template":{"settings":{"index.lifecycle.name":"pixflow-short-retention","index.number_of_shards":1,"index.number_of_replicas":0},"mappings":{"properties":{"correlationId":{"type":"keyword"},"paymentId":{"type":"keyword"},"eventId":{"type":"keyword"},"service":{"type":"keyword"},"operation":{"type":"keyword"},"outcome":{"type":"keyword"},"errorCode":{"type":"keyword"},"durationMs":{"type":"long"}}}}}' >/dev/null

echo "Elasticsearch users, role, template and 2-day retention configured."
