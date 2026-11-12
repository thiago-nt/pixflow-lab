# GATE 07 - Observabilidade local end-to-end

Data da validacao: 2026-11-12 (America/Sao_Paulo).

## Resultado executivo

O GATE 06 esta no `HEAD` `ff884fb` (`feat(reconciliation): recover unknown payment outcomes safely`) e o LAB OUTBOX/RESILIENCIA permanece no ancestral `efc8fd4`. O compose base, as migrations existentes e os volumes financeiros nao foram alterados. Os profiles opcionais foram implementados em um overlay separado e a stack financeira permaneceu disponivel durante falhas e reinicios da observabilidade.

Resultado: **PASS**, com as limitacoes explicitadas em "Riscos residuais".

## Auditoria inicial e recursos

- Stack base inicialmente saudavel, com aproximadamente 1,91 GiB em uso.
- Docker Desktop: 5.930 MiB e 4 CPUs.
- Antes de subir `observe-logs`: 5.931 MiB totais, 2.984 MiB usados e 2.947 MiB disponiveis. O minimo automatizado e 2.300 MiB; portanto o profile foi liberado.
- Snapshot com os dois profiles ativos: 3.007 MiB usados por todos os containers do projeto, abaixo do limite aproximado de 6 GiB.
- Observabilidade no snapshot: Elasticsearch 954,8 MiB/7,27% CPU; Kibana 536,9 MiB/7,72%; Grafana 96,2 MiB/0,40%; Prometheus 46,3 MiB/0,20%; Fluent Bit 29,7 MiB/0,19%.
- Os cinco servicos Java ja possuíam Actuator, mas apenas o relay expunha `prometheus` e nenhum deles possuia o registry Prometheus. Agora todos expoem somente `health,prometheus`.

## Profiles e seguranca

O arquivo `compose.observe.yaml` adiciona, sem modificar `compose.yaml`:

- `observe-metrics`: Prometheus 3.5.0 e Grafana 12.1.0.
- `observe-logs`: Elasticsearch 8.17.3, Kibana 8.17.3 e Fluent Bit 4.1.0.

Controles verificados:

- Prometheus, Grafana e Kibana publicados exclusivamente em `127.0.0.1`; Elasticsearch nao possui porta publicada no host.
- Elasticsearch com seguranca habilitada; ingestao usa usuario dedicado `pixflow_ingest` e privilegios somente no indice autorizado.
- Kibana recebe suas tres chaves de criptografia interna pelo mesmo secret local, sem valores fixos no Compose.
- Senhas aleatorias sao criadas em `secrets/`, ja ignorado pelo Git. Nenhuma credencial foi versionada ou impressa nas evidencias.
- Prometheus retém no maximo 2 dias/512 MB; Elasticsearch usa ILM de 2 dias.
- Fluent Bit coleta apenas os cinco containers Java autorizados e mantem buffer local limitado.
- Limites: Prometheus 384 MiB, Grafana 384 MiB, Elasticsearch 1.280 MiB, Kibana 768 MiB, Fluent Bit 128 MiB.
- Healthchecks reais ficaram saudaveis para os cinco componentes. Falhas iniciais de configuracao do profile nao interromperam a stack de pagamentos.
- O script `scripts/observability.ps1` verifica memoria antes de iniciar logs e usa `stop` explicito; nao executa `down`, prune ou remocao de volumes.

## Metricas e dashboards

Micrometer/Prometheus foi habilitado em Payment API, Outbox Relay, Payment Worker, PSP Simulator e Reconciliation Worker. Foram adicionadas metricas para requests de pagamento, pendencia/publicacao/falha de Outbox, tentativas e duracao de processamento, lease, expiracao, fencing, resultados do PSP, pagamentos UNKNOWN, reconciliacao e tempo de resolucao. JVM, CPU, heap, GC, threads, HikariCP, HTTP e metricas do cliente Kafka sao fornecidas pelos binders Spring/Micrometer.

Nenhum `paymentId`, `eventId` ou `correlationId` e usado como label. As dimensoes customizadas sao enumeracoes de baixa cardinalidade, como `outcome`, `operation`, `component` e `effect`.

Prometheus apresentou 6/6 targets `up`: os cinco servicos Java e o proprio Prometheus. Evidencias de series reais apos os cenarios: requests de pagamento, publicacoes/falhas do Outbox, tentativas do Worker, aquisicoes de lease, operacoes PSP, reconciliacao, UNKNOWN e lag do cliente Kafka. O lag apresentou uma serie ativa em zero e duas particoes em `NaN` logo apos rebalance, comportamento do binder do cliente.

Sete dashboards provisionados e consultando somente PromQL real:

1. PixFlow Lab - Visao geral.
2. PixFlow Lab - Payment API e JVM.
3. PixFlow Lab - Kafka e Outbox.
4. PixFlow Lab - Payment Worker, lease e fencing.
5. PixFlow Lab - PSP Simulator.
6. PixFlow Lab - Reconciliacao e UNKNOWN.
7. PixFlow Lab - Saude da infraestrutura.

A API do Grafana confirmou os 7 dashboards provisionados. Paineis sem ocorrencia exibem zero/sem serie; nao ha datasource de teste ou dado ficticio.

## Logs estruturados e correlacao

Os servicos emitem JSON Logstash com `timestamp`, `service`, `environment`, `level` e campos contextuais `operation`, `correlationId`, `paymentId`, `eventId`, `outcome`, `errorCode` e `durationMs` quando aplicaveis. `traceId` e `spanId` ficam disponiveis para uma futura instrumentacao de tracing, mas nao sao inventados nesta etapa.

A API aceita/devolve `X-Correlation-ID`; o Outbox inclui correlacao, evento e pagamento nos headers Kafka; o Worker restaura o contexto e propaga o header nas chamadas HTTP ao PSP. Worker e Reconciliation Worker propagam a correlacao para consultas PSP.

Fluent Bit indexou 504 documentos no indice `pixflow-logs-2026.11.12`. Uma busca por `gate07-final-1794515481` devolveu 16 documentos correlacionados entre Payment API, Outbox Relay, Payment Worker e PSP Simulator. O data view `pixflow-logs-*` foi criado com `timestamp` como campo temporal; `correlationId` e `paymentId` estao mapeados como `keyword` e pesquisaveis por KQL.

## Cenarios end-to-end

| Cenario | Estado PostgreSQL / efeito PSP | Metricas e dashboard | Logs Kibana | Resultado |
|---|---|---|---|---|
| A APPROVED | `8a2a502c-9701-4e53-a820-50799e02abfc`: `SUCCEEDED/APPROVED`, PSP=1, Inbox=1 | series de request, outbox, worker, lease e PSP | 8 documentos, 4 servicos, `gate07e-A-1794515623` | PASS |
| B REJECTED | `bad25d10-617d-4d88-8122-ca18c2dd44ef`: `FAILED/REJECTED`, PSP=1, Inbox=1 | resultado `failed/rejected` real | 7 documentos, 4 servicos, `gate07e-B-1794515623` | PASS |
| C timeout + reconciliacao | `969b4ca4-8fe3-4821-917d-a1439fa6f92a`: `UNKNOWN -> SUCCEEDED/RECONCILED_APPROVED`, PSP=1, Inbox=1 | worker `unknown`, lookup e reconciliacao confirmada | 10 documentos, 5 servicos, `gate07e-C-1794515640` | PASS |
| D duplicacao Kafka/Inbox | reenvio do evento de A: antes PSP=1/Inbox=1; depois `SUCCEEDED`, PSP=1/Inbox=1 | nova tentativa observada, sem novo efeito | segunda entrega visivel na mesma correlacao de A | PASS |
| E recuperacao Outbox | `16dc8916-0773-4037-966f-4118b32f8dd4`: Kafka parado, evento pendente; apos retorno `SUCCEEDED/APPROVED`, tentativa 3, PSP=1 | `outbox_publish` failed e depois published | 9 documentos, 4 servicos, `gate07e-E-1794515730` | PASS |
| F lease/fencing | Testcontainers: concorrencia, expiracao e token obsoleto; estado terminal preservado e uma chamada PSP | metricas e paineis implementados; sem injecao no banco persistente | evidencias do teste JUnit, sem execucao live destrutiva | PASS (integracao) |

O cenario F foi repetido por `LeaseServiceIntegrationTest` e `PaymentProcessingIntegrationTest`: 11 testes, 0 falhas. Inclui `expiredOwnerIsFencedAndOnlyNewTokenCanPersist`, `simultaneousAcquisitionHasOneOwner` e `staleWorkerCannotOverwriteResultAfterLeaseExpiresDuringPspCall`. Nao foi fabricado um conflito diretamente nos dados persistidos do laboratorio.

## Testes automatizados

- `mvn.cmd -B -ntp test`: **BUILD SUCCESS**, 52 testes, 0 falhas, 0 erros, 0 ignorados.
- Suite focada de lease/fencing: 11 testes, 0 falhas.
- `git diff --check`: PASS (somente avisos informativos de conversao LF/CRLF do Git no Windows).
- Compose base + overlay e os sete JSON de dashboards foram validados sintaticamente.
- Nenhuma migration nova foi necessaria.

## URLs locais

- Payment API/PixFlow Live: http://127.0.0.1:8080
- Prometheus: http://127.0.0.1:9090
- Grafana: http://127.0.0.1:3000
- Kibana: http://127.0.0.1:5601
- Kafka UI: http://127.0.0.1:8085
- pgweb: http://127.0.0.1:8086

Elasticsearch permanece acessivel apenas pela rede Docker.
Grafana usa o usuario `pixflow_admin` e o arquivo local `secrets/grafana_admin_password.txt`; Kibana usa `elastic` e `secrets/elastic_password.txt`. Os arquivos sao ignorados pelo Git.

## Operacao dos profiles

```powershell
# Subir
.\scripts\observability.ps1 -Action Up -Profile Metrics
.\scripts\observability.ps1 -Action Up -Profile Logs
.\scripts\observability.ps1 -Action Up -Profile All

# Status
.\scripts\observability.ps1 -Action Status -Profile All

# Parar sem remover containers ou volumes
.\scripts\observability.ps1 -Action Stop -Profile Logs
.\scripts\observability.ps1 -Action Stop -Profile Metrics
```

## Riscos residuais

- Nao ha tracing distribuido completo. Correlacao HTTP/Kafka foi verificada, mas OpenTelemetry/collector e propagacao comprovada de spans ficam para evolucao futura.
- Nao ha exporter de broker Kafka nem cAdvisor/node-exporter. Lag do consumidor vem do cliente Spring Kafka; ISR, disco do broker e metricas por container nao estao no Prometheus. O dashboard de infraestrutura usa `up`, JVM/processo e Hikari; consumo de container foi comprovado por `docker stats`.
- O endpoint Prometheus da Payment API e acessivel no localhost junto com a API. Os demais endpoints Actuator ficam somente na rede interna do Compose.
- Elasticsearch/Kibana consomem a maior parte da memoria adicional. O preflight reduz o risco, mas outros workloads simultaneos podem exigir manter `observe-logs` desligado.
- Campos opcionais de log aparecem apenas quando ha contexto; por exemplo, `errorCode` em falhas e `durationMs` em operacoes cronometradas.

## Lista minima para commit manual

Incluir somente os arquivos do GATE 07 listados abaixo. Excluir explicitamente `README.md`, `docs/TEST_REPORT.md` e `docs/ETAPA02_DEMO.md`, que ja estavam alterados antes deste GATE. Nao incluir `secrets/`, `target/`, logs ou volumes.

```text
compose.observe.yaml
docs/GATE07_OBSERVABILITY_REPORT.md
scripts/observability.ps1
infra/observability/**
payment-api/pom.xml
payment-api/src/main/java/dev/pixflow/lab/payment/application/PaymentService.java
payment-api/src/main/java/dev/pixflow/lab/payment/infrastructure/observability/CorrelationFilter.java
payment-api/src/main/java/dev/pixflow/lab/payment/infrastructure/observability/PaymentStateMetrics.java
payment-api/src/main/resources/application.yml
outbox-relay/pom.xml
outbox-relay/src/main/java/dev/pixflow/lab/outbox/OutboxPublisher.java
outbox-relay/src/main/resources/application.yml
outbox-relay/src/test/java/dev/pixflow/lab/outbox/OutboxPublisherTest.java
payment-worker/pom.xml
payment-worker/src/main/java/dev/pixflow/lab/worker/LeaseService.java
payment-worker/src/main/java/dev/pixflow/lab/worker/PaymentProcessingService.java
payment-worker/src/main/java/dev/pixflow/lab/worker/PaymentRequestedListener.java
payment-worker/src/main/java/dev/pixflow/lab/worker/WorkerConfiguration.java
payment-worker/src/main/resources/application.yml
payment-worker/src/test/java/dev/pixflow/lab/worker/LeaseServiceIntegrationTest.java
payment-worker/src/test/java/dev/pixflow/lab/worker/PaymentProcessingIntegrationTest.java
payment-worker/src/test/java/dev/pixflow/lab/worker/PaymentRequestedListenerTest.java
psp-simulator/pom.xml
psp-simulator/src/main/java/dev/pixflow/lab/psp/PspCorrelationFilter.java
psp-simulator/src/main/java/dev/pixflow/lab/psp/PspSimulatorController.java
psp-simulator/src/main/java/dev/pixflow/lab/psp/PspSimulatorService.java
psp-simulator/src/main/resources/application.yml
reconciliation-worker/pom.xml
reconciliation-worker/src/main/java/dev/pixflow/lab/reconciliation/ReconciliationConfiguration.java
reconciliation-worker/src/main/java/dev/pixflow/lab/reconciliation/ReconciliationService.java
reconciliation-worker/src/main/resources/application.yml
reconciliation-worker/src/test/java/dev/pixflow/lab/reconciliation/ReconciliationServiceIntegrationTest.java
```
