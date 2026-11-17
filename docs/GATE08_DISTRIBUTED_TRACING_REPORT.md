# GATE 08 — Distributed Tracing com OpenTelemetry, Collector e Grafana Tempo

Data da execução: 2026-11-13 (America/Sao_Paulo)

## 1. Resultado

**GATE 08 — DISTRIBUTED TRACING = PASS**

O tracing distribuído foi implementado nos cinco serviços Java sem alterar a arquitetura transacional. Há traces reais consultáveis no Tempo pelo Grafana, propagação W3C persistida na Transactional Outbox, spans Kafka/HTTP/PostgreSQL, spans de negócio, Span Link entre reconciliação e operação original e correlação com os logs JSON no Elasticsearch/Kibana.

Evidências de regressão e segurança financeira:

- 54 testes JUnit 5/Testcontainers aprovados, sem falhas ou testes ignorados: os 52 anteriores e 2 testes novos do contexto W3C.
- Cenários APPROVED, APPROVED_TIMEOUT, REJECTED_TIMEOUT, falha do Relay, duplicação pós-ACK, fencing obsoleto e indisponibilidade do tracing foram executados.
- Em todos os pagamentos sintéticos verificados, Inbox e PSP permaneceram unitários.
- Collector e Tempo foram desligados durante T07; o pagamento concluiu como `SUCCEEDED` e o tracing foi restaurado saudável.
- `git diff --check` retornou exit code 0.

## 2. Auditoria inicial

- HEAD antes do GATE 08: `2df0a9a feat(observability): add local metrics logs and dashboards`.
- GATE 06 confirmado: `ff884fb feat(reconciliation): recover unknown payment outcomes safely`.
- LAB de resiliência preservado: `efc8fd4 test(resilience): add deterministic outbox failure lab`.
- `docs/GATE07_OBSERVABILITY_REPORT.md` foi lido integralmente.
- A árvore de trabalho estava limpa antes da implementação.
- `README.md`, `docs/TEST_REPORT.md` e `docs/ETAPA02_DEMO.md` não foram modificados no GATE 08.
- `compose.ha.yaml` não foi modificado.
- O Docker Desktop reportou 5.930 MiB disponíveis como limite total.

## 3. Arquitetura implementada

Fluxo síncrono e assíncrono observado:

```text
Payment API
  -> PostgreSQL: payment + outbox + traceparent/tracestate na mesma transação
  -> Outbox Relay restaura o contexto persistido
  -> Kafka recebe traceparent/tracestate nos headers
  -> Payment Worker recupera o contexto e chama o PSP por HTTP
  -> PSP Simulator persiste/consulta a referência idempotente

UNKNOWN
  -> Reconciliation Worker cria um novo trace por tentativa
  -> reconciliation.resolve contém Span Link para o trace original
  -> consulta PSP somente leitura
  -> resultado transacional + nova Outbox

Cinco serviços -> OTLP/gRPC -> OpenTelemetry Collector -> Tempo -> Grafana Explore
Cinco serviços -> JSON stdout -> Fluent Bit -> Elasticsearch -> Kibana
```

Versões fixadas:

- OpenTelemetry Java Agent `2.20.1`.
- OpenTelemetry API `1.54.1`.
- OpenTelemetry Collector Contrib `0.137.0`.
- Grafana Tempo `2.9.5`.
- Grafana `12.1.0` e Prometheus `3.5.0` preservados do GATE 07.

## 4. Compose, Collector e Tempo

O overlay `compose.tracing.yaml` é aditivo. A stack padrão continua sem variáveis OTEL e sem os serviços `tempo` e `otel-collector`; a validação de `docker compose -f compose.yaml config` confirmou ambos ausentes. O tracing é ativado explicitamente pelo profile `observe-traces` e usa o profile `observe-metrics` para disponibilizar o Grafana.

Collector:

- OTLP/gRPC interno em `4317` e OTLP/HTTP interno em `4318`.
- Processadores `memory_limiter` (192 MiB, pico 48 MiB) e `batch`.
- Exportação OTLP interna para `tempo:4317`.
- Healthcheck em `13133`, logs diagnósticos em nível `info`.
- Limite de 256 MiB, 0,5 CPU e `no-new-privileges`.

Tempo:

- Backend local persistido no volume nomeado `pixflow-lab-tempo-data`.
- WAL e blocos locais; retenção de 24 horas.
- Receivers OTLP exclusivamente na rede Docker.
- Limite de 512 MiB, 0,5 CPU e `no-new-privileges`.
- Telemetria de uso desativada.

Nenhuma porta do Collector ou Tempo está publicada no host. O datasource provisionado no Grafana foi validado como `Tempo`, UID `tempo`, URL interna `http://tempo:3200`; `/ready` respondeu HTTP 200 pelo proxy do Grafana.

## 5. Instrumentação Java

O Java Agent é o mecanismo principal e fica inativo na stack padrão porque `JAVA_TOOL_OPTIONS` só existe no overlay de tracing. Cada serviço usa um `OTEL_SERVICE_NAME` distinto:

- `payment-api`
- `outbox-relay`
- `payment-worker`
- `psp-simulator`
- `reconciliation-worker`

Configuração comum:

- `OTEL_TRACES_EXPORTER=otlp`
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`
- `OTEL_EXPORTER_OTLP_PROTOCOL=grpc`
- `OTEL_PROPAGATORS=tracecontext,baggage`
- `deployment.environment.name=pixflow-lab`
- sampler `parentbased_traceidratio`
- amostragem padrão segura de 10%; o script usa 100% nos testes sintéticos por padrão.
- exportadores OTEL de métricas e logs desativados para não duplicar Micrometer/Prometheus nem Fluent Bit.
- MDC do Logback habilitado; auto-instrumentação de scheduling desabilitada para evitar spans periódicos sem valor operacional.

Spans automáticos comprovados: HTTP client/server, Kafka publish/process e comandos PostgreSQL `SELECT`, `INSERT` e `UPDATE`.

Spans manuais adicionados somente nos limites de negócio:

- `payment.create`
- `outbox.persist`
- `outbox.publish`
- `payment.consume`
- `payment.idempotency_check`
- `payment.lease.acquire`
- `payment.fencing.validate`
- `psp.payment.execute`
- `psp.payment.persist`
- `psp.payment.lookup`
- `reconciliation.psp.lookup`
- `reconciliation.resolve`

Os atributos contêm somente identificadores sintéticos e metadados operacionais (`payment.id`, `event.id`, `outcome`, `retry.count`, fencing token e status). Não há tokens, credenciais, payload financeiro completo ou dados pessoais nos spans.

## 6. Propagação pela Transactional Outbox

A migration aditiva `V007__outbox_trace_context.sql` acrescenta colunas opcionais:

- `trace_parent VARCHAR(55)`
- `trace_state VARCHAR(512)`

A versão `007:outbox trace context:true` foi confirmada em `flyway_schema_history`.

Comportamento:

1. `payment.create` recebe/cria o trace HTTP.
2. `outbox.persist` captura o W3C Trace Context.
3. Payment e Outbox, incluindo `trace_parent`/`trace_state`, continuam na mesma transação existente.
4. O Relay lê o contexto junto com a claim da Outbox.
5. Cada tentativa de `outbox.publish` cria um span novo, filho do contexto persistido.
6. O contexto corrente é injetado nos headers Kafka `traceparent`/`tracestate`.
7. A auto-instrumentação Kafka extrai o contexto no Worker; `payment.consume` é filho do span de consumo.
8. A auto-instrumentação HTTP propaga o contexto para o PSP.

As colunas são nullable e `TraceContextSnapshot` trata contexto ausente/inválido. Assim, eventos antigos continuam processáveis. O teste `acceptsLegacyEventWithoutTraceContext` comprova essa compatibilidade.

O contexto persistido não usa `paymentId`, `eventId` ou `correlationId` como substitutos de `traceId`.

## 7. Reconciliação e Span Links

Cada tentativa de reconciliação cria um trace independente, porque o trabalho pode ocorrer após backoff, restart e nova aquisição. O span raiz `reconciliation.resolve` usa `setNoParent()` e adiciona um Span Link ao `traceparent` original persistido na Outbox. Não há compartilhamento de span ativo entre processos.

No cenário T02:

- trace original: `6715be457b7c658f522a70a09e065de0` (46 spans).
- trace de reconciliação: `a0aed49b3cd4d76d0c759b3c561ad014` (19 spans).
- `reconciliation.resolve` não possui parent.
- o Span Link retornado pelo Tempo aponta para `6715be457b7c658f522a70a09e065de0`, o trace original.
- serviços do trace original: Payment API, Outbox Relay, Payment Worker e PSP Simulator.
- serviços do trace de reconciliação: Reconciliation Worker, PSP Simulator e Outbox Relay.

Para navegar entre traces no Grafana, abra os detalhes de `reconciliation.resolve`, copie o trace ID exibido em `Links` e consulte-o por Trace ID no Explore. Como são traces distintos, eles não aparecem artificialmente em uma única árvore.

## 8. Logs e correlação

Os cinco `application.yml` mantêm JSON stdout e agora padronizam os campos MDC como `traceId` e `spanId`, preservando `correlationId`, `service`, `operation` e `timestamp` do GATE 07.

No fluxo T02, o Elasticsearch retornou 10 documentos em `pixflow-logs-*`, cobrindo os cinco serviços e os dois trace IDs:

- `6715be457b7c658f522a70a09e065de0`
- `a0aed49b3cd4d76d0c759b3c561ad014`

Consulta no Kibana Discover:

```text
traceId : "6715be457b7c658f522a70a09e065de0"
```

ou, para acompanhar a reconciliação completa:

```text
correlationId : "gate08-t02-1794580158"
```

Não foi criada uma integração nativa clicável Grafana/Elasticsearch: Grafana e Kibana usam backends distintos. A navegação é feita pelo mesmo `traceId`, explicitamente documentado, sem alegar capacidade inexistente.

## 9. Evidências dos testes obrigatórios

| Teste | Resultado | Evidência principal |
|---|---|---|
| T01 — APPROVED | PASS | Payment `4df5c5f1-46c6-406b-b63d-0648fbcccca5`; `SUCCEEDED`; Inbox 1; PSP 1; trace `7b35f4f66373408a26f9e56a8144f3ef`, 52 spans e 4 serviços. |
| T02 — APPROVED_TIMEOUT | PASS | Payment `11dc4954-d459-4699-808d-200f745c4da6`; `UNKNOWN -> SUCCEEDED`; reconciliação `CONFIRMED_APPROVED`; traces original e reconciliador ligados por Span Link; Inbox 1; PSP 1. |
| T03 — REJECTED_TIMEOUT | PASS | Payment `348d0902-8cbd-4435-a564-17cd27c55900`; `UNKNOWN -> FAILED`; `CONFIRMED_REJECTED`; trace `eb5806e9238885ac30830f53db8fb0ff`, 45 spans; Inbox 1; PSP 1. |
| T04 — falha de publicação | PASS | Payment `50a2bb91-01c4-49ef-b5bc-7c9cfee86719`; Relay falhou com broker inválido e recuperou após restart; Outbox 2 tentativas; `trace_parent` preservado; `SUCCEEDED`; Inbox 1; PSP 1. |
| T05 — duplicação pós-ACK | PASS | Payment `7c2e4c2d-1b58-49c0-8ca3-7ca03bdc6933`; trace `03c76260fd6acabd9b9644fb23a28c09`; spans `outbox.publish` separados para `crash_after_ack` e `published`; Kafka publicou 2 vezes; Inbox 1; PSP 1. |
| T06 — lease/fencing | PASS | 11 testes focados aprovados; cenário live `cbb1b097-423e-4e40-b366-8b342ccec84f`; trace `f7f633a505f71e42a2619cc16643e638` contém `payment.lease.acquire` e `payment.fencing.validate outcome=rejected`; token 1 rejeitado, token 2 concluiu; `SUCCEEDED`; PSP 1. |
| T07 — Collector/Tempo indisponíveis | PASS | Ambos foram parados; payment `54093a1a-80e1-4a6f-89a9-496c48bd77b8` concluiu `SUCCEEDED`, Inbox 1, PSP 1; ambos restaurados `healthy`. |
| T08 — regressão completa | PASS | `mvnw.cmd -B -ntp test`: 54 testes, 0 failures, 0 errors, 0 skipped; reactor `BUILD SUCCESS`. |

Transições auditadas no PostgreSQL:

```text
T02: PENDING -> PROCESSING -> SENT_TO_PSP -> UNKNOWN -> SUCCEEDED
T03: PENDING -> PROCESSING -> SENT_TO_PSP -> UNKNOWN -> FAILED
```

Resumo financeiro final dos cenários T01–T05 e T07:

```text
paymentId                              status     outbox attempts  inbox  PSP
4df5c5f1-46c6-406b-b63d-0648fbcccca5  SUCCEEDED  1                1      1
11dc4954-d459-4699-808d-200f745c4da6  SUCCEEDED  1                1      1
348d0902-8cbd-4435-a564-17cd27c55900  FAILED     1                1      1
50a2bb91-01c4-49ef-b5bc-7c9cfee86719  SUCCEEDED  2                1      1
7c2e4c2d-1b58-49c0-8ca3-7ca03bdc6933  SUCCEEDED  2                1      1
54093a1a-80e1-4a6f-89a9-496c48bd77b8  SUCCEEDED  1                1      1
```

## 10. Grafana/Tempo

URLs locais:

- Grafana Explore: `http://127.0.0.1:3000/explore`
- Prometheus: `http://127.0.0.1:9090`
- Kibana Discover: `http://127.0.0.1:5601/app/discover`
- Payment API: `http://127.0.0.1:8080`

Tempo não é exposto diretamente no host; o acesso é pelo proxy/datasource do Grafana.

No Grafana Explore, selecione `Tempo` e use a consulta por Trace ID. Exemplo real:

```text
7b35f4f66373408a26f9e56a8144f3ef
```

Esse trace retornou 52 spans de `payment-api`, `outbox-relay`, `payment-worker` e `psp-simulator`, incluindo:

- HTTP `POST /api/v1/payments`, GET/POST do PSP.
- Kafka `payment.requested.v1 publish/process` e `payment.result.v1 publish`.
- PostgreSQL SELECT/INSERT/UPDATE.
- spans de negócio de API, Outbox, idempotência, lease e PSP.
- duração por span, atributos, status e exceções.

Busca TraceQL útil para localizar os traces original e de reconciliação pelo pagamento sintético:

```text
{ span.payment.id = "11dc4954-d459-4699-808d-200f745c4da6" }
```

## 11. Consumo de recursos

Snapshot real obtido com todos os profiles ativos. Os totais por combinação são somas dos containers do mesmo snapshot, portanto evitam variação entre reinícios:

| Configuração | Memória |
|---|---:|
| Stack principal | 1.764,7 MiB |
| Principal + métricas | 1.956,1 MiB |
| Principal + tracing | 1.915,8 MiB |
| Principal + métricas + tracing | 2.107,2 MiB |
| Principal + métricas + logs + tracing | 3.457,3 MiB |
| Todos os containers, incluindo ferramentas `inspect` | 3.645,1 MiB |

Detalhe do tracing no snapshot:

- Tempo: 107,0 MiB e 0,59% CPU.
- Collector: 44,1 MiB e 0,48% CPU.
- total tracing: 151,1 MiB.

A combinação completa ficou abaixo do limite aproximado de 6 GB. Elasticsearch, Kibana e Fluent Bit puderam permanecer ligados; nenhum teste ficou `NOT_RUN` por memória.

## 12. Segurança e tolerância a falhas

- Collector e Tempo não publicam portas no host.
- Nenhuma credencial nova foi criada ou versionada.
- `secrets/` continua ignorado pelo Git; não há arquivo rastreado nesse diretório.
- O Collector não recebe logs nem métricas, evitando coleta duplicada.
- Nenhum payload financeiro completo é anexado a spans.
- A indisponibilidade do pipeline de tracing não provoca rollback nem interrompe pagamentos, comprovado em T07.
- `compose.ha.yaml`, recursos QPO, volumes PostgreSQL/Kafka e dados anteriores foram preservados.
- Não foi executado `prune`, `down -v`, `git add`, commit ou push.

## 13. Comandos de operação e reprodução

Subir métricas, tracing e os cinco serviços instrumentados com 100% de amostragem sintética:

```powershell
.\scripts\tracing.ps1 -Action Up -SampleRatio 1.0
```

Usar amostragem normal de 10%:

```powershell
.\scripts\tracing.ps1 -Action Up -SampleRatio 0.10
```

Consultar estado:

```powershell
.\scripts\tracing.ps1 -Action Status
```

Parar somente Collector e Tempo, sem remover containers ou volumes:

```powershell
.\scripts\tracing.ps1 -Action Stop
```

Regressão automatizada:

```powershell
.\mvnw.cmd -B -ntp test
git diff --check
docker compose -f compose.yaml config
docker compose -f compose.yaml -f compose.observe.yaml -f compose.tracing.yaml --profile observe-metrics --profile observe-traces config
```

## 14. Arquivos criados

- `compose.tracing.yaml`
- `docs/GATE08_DISTRIBUTED_TRACING_REPORT.md`
- `infra/observability/grafana/provisioning/datasources/tempo.yml`
- `infra/observability/otel-collector/Dockerfile`
- `infra/observability/otel-collector/config.yaml`
- `infra/observability/tempo/Dockerfile`
- `infra/observability/tempo/tempo.yaml`
- `infra/postgres/migrations/V007__outbox_trace_context.sql`
- `scripts/tracing.ps1`
- `shared-observability/pom.xml`
- `shared-observability/src/main/java/dev/pixflow/lab/observability/TraceContextSnapshot.java`
- `shared-observability/src/test/java/dev/pixflow/lab/observability/TraceContextSnapshotTest.java`

## 15. Arquivos modificados

Raiz:

- `pom.xml`

Payment API:

- `payment-api/Dockerfile`
- `payment-api/pom.xml`
- `payment-api/src/main/java/dev/pixflow/lab/payment/application/PaymentService.java`
- `payment-api/src/main/java/dev/pixflow/lab/payment/application/PaymentStore.java`
- `payment-api/src/main/java/dev/pixflow/lab/payment/infrastructure/persistence/JdbcPaymentStore.java`
- `payment-api/src/main/resources/application.yml`
- `payment-api/src/test/java/dev/pixflow/lab/payment/PaymentApiIntegrationTest.java`

Outbox Relay:

- `outbox-relay/Dockerfile`
- `outbox-relay/pom.xml`
- `outbox-relay/src/main/java/dev/pixflow/lab/outbox/ClaimedOutboxEvent.java`
- `outbox-relay/src/main/java/dev/pixflow/lab/outbox/OutboxPublisher.java`
- `outbox-relay/src/main/java/dev/pixflow/lab/outbox/OutboxRepository.java`
- `outbox-relay/src/main/resources/application.yml`
- `outbox-relay/src/test/java/dev/pixflow/lab/outbox/LabAfterKafkaAckFailureInjectorTest.java`
- `outbox-relay/src/test/java/dev/pixflow/lab/outbox/OutboxPublisherTest.java`
- `outbox-relay/src/test/java/dev/pixflow/lab/outbox/OutboxRepositoryIntegrationTest.java`

Payment Worker:

- `payment-worker/Dockerfile`
- `payment-worker/pom.xml`
- `payment-worker/src/main/java/dev/pixflow/lab/worker/LeaseService.java`
- `payment-worker/src/main/java/dev/pixflow/lab/worker/PaymentProcessingService.java`
- `payment-worker/src/main/java/dev/pixflow/lab/worker/PaymentRequestedListener.java`
- `payment-worker/src/main/resources/application.yml`
- `payment-worker/src/test/java/dev/pixflow/lab/worker/LeaseServiceIntegrationTest.java`
- `payment-worker/src/test/java/dev/pixflow/lab/worker/PaymentProcessingIntegrationTest.java`

PSP Simulator:

- `psp-simulator/Dockerfile`
- `psp-simulator/pom.xml`
- `psp-simulator/src/main/java/dev/pixflow/lab/psp/PspSimulatorService.java`
- `psp-simulator/src/main/resources/application.yml`
- `psp-simulator/src/test/java/dev/pixflow/lab/psp/PspSimulatorIntegrationTest.java`

Reconciliation Worker:

- `reconciliation-worker/Dockerfile`
- `reconciliation-worker/pom.xml`
- `reconciliation-worker/src/main/java/dev/pixflow/lab/reconciliation/ReconciliationClaim.java`
- `reconciliation-worker/src/main/java/dev/pixflow/lab/reconciliation/ReconciliationService.java`
- `reconciliation-worker/src/main/resources/application.yml`
- `reconciliation-worker/src/test/java/dev/pixflow/lab/reconciliation/ReconciliationServiceIntegrationTest.java`

As mudanças nos testes de integração existentes somente incluem a migration V007 nos schemas Testcontainers; os testes funcionais anteriores foram preservados.

## 16. Riscos e limitações residuais

- Tempo usa backend local de instância única, adequado ao laboratório, não a produção.
- Retenção é temporal (24 horas), sem limite explícito por bytes; deve ser acompanhada se o laboratório ficar ligado continuamente.
- A amostragem em 100% é apropriada apenas para baixo volume sintético. O valor padrão do overlay é 10%.
- A relação logs/traces entre Kibana e Grafana é manual pelo `traceId`; não há link nativo entre os produtos.
- O Java Agent é baixado durante o build Maven de cada imagem, aumentando tempo de build e dependência do repositório Maven; a versão está fixada.
- Spans PostgreSQL expõem nomes de tabelas/operações, mas não payloads; futuras mudanças devem continuar evitando captura de parâmetros SQL sensíveis.
- O warning do Mockito sobre dynamic agent loading permanece preexistente nos testes e não afeta o resultado atual.

## 17. Lista mínima para commit manual

Todos e somente os arquivos das seções 14 e 15 compõem o GATE 08. Não incluir `target/`, `secrets/`, logs, `.env` ou dados persistidos. Antes do commit manual, revisar novamente:

```powershell
git status --short --untracked-files=all
git diff --check
.\mvnw.cmd -B -ntp test
```

Nenhum `git add`, commit ou push foi executado durante o GATE 08.

**GATE 08 — DISTRIBUTED TRACING = PASS**
