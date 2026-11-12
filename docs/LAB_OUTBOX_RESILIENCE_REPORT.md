# Lab Outbox/Resiliência — 2026-11-12

Todos os dados usados neste relatório são sintéticos. Horários do PostgreSQL e dos logs estão em UTC; entre parênteses está o horário local de São Paulo (UTC-03:00).

## Resultado executivo

| Teste | Estado | Evidência principal |
|---|---|---|
| Auditoria inicial | PASS | GATES 03, 04 e 05 presentes; migrations V001–V005 aplicadas; stack saudável; 17 pagamentos anteriores preservados. |
| T01 — falha determinística após COMMIT | PASS | Falha pós-COMMIT retornou HTTP 500; pagamento e Outbox persistiram; zero mensagem Kafka com Relay parado; replay retornou o mesmo pagamento. |
| T01 — crash real da JVM no ponto exato | NOT_RUN | A instrumentação comprova `transactionActive=false`, mas não encerra a JVM dentro do hook; a instância isolada foi parada em seguida. |
| T02 — recuperação do Relay | PASS | Relay publicou o evento pendente; Worker, Inbox e PSP concluíram; pagamento `SUCCEEDED`. |
| T03 — falha após ACK Kafka | PASS | Primeira mensagem recebeu ACK, a Outbox permaneceu pendente, e o restart publicou a mesma mensagem novamente; Inbox e PSP ficaram unitários. |
| T04 — reenvio da API | PASS | Mesmo payload: HTTP 200 e mesmo `paymentId`; payload diferente: HTTP 409; nenhuma nova operação lógica. |
| T05 — três réplicas Docker | PASS | HAProxy distribuiu tráfego entre três APIs; com uma parada, as outras duas responderam; a réplica retornou e voltou a receber tráfego. Não é recuperação Kubernetes. |
| T05 — recuperação de pods Kubernetes | NOT_RUN | Kubernetes não foi implantado; nenhum teste kind, k3d ou AKS foi iniciado. |
| JUnit 5 / Testcontainers | PASS | Reactor completo: sete módulos `SUCCESS`; 41 testes, zero falhas/erros/skips; testes de integração usam PostgreSQL 18.4 Testcontainers. |

## Auditoria e baseline

- Commit inicial: `169e24f feat(processing): add payment lease and fencing protection`.
- Mudanças preexistentes preservadas: `README.md`, `docs/TEST_REPORT.md` e `docs/ETAPA02_DEMO.md`.
- Migrations aplicadas no volume persistente: V001, V002, V003, V004 e V005.
- Baseline antes do ensaio: 17 pagamentos, 34 eventos Outbox, 17 Inbox e 17 transações PSP.
- Volumes `pixflow-lab-postgres-data` e `pixflow-lab-kafka-data` foram preservados. Nenhum recurso `qpo_*` foi modificado.
- `scripts/verify.ps1`: `INFRASTRUCTURE_VERIFY=PASS`.
- Baseline de memória: aproximadamente 1,48 GiB para os oito containers ativos medidos.

O código confirma:

- `PaymentService` grava pagamento, idempotência, Outbox e auditoria dentro do mesmo `TransactionTemplate`.
- O Relay encerra a transação de claim, aguarda `KafkaTemplate.send(...).get(...)` e só depois executa `markPublished`.
- O Worker verifica `inbox_processed`, usa lease/fencing e grava Inbox, estado final e Outbox de resultado em transação local.
- O PSP Simulator usa referência estável e restrição de unicidade, retornando a transação existente em replay.

## T01 e T02 — COMMIT, replay e recuperação

Identificadores:

| Campo | Valor |
|---|---|
| Idempotency-Key | `lab-t01-20261112-1059-a7d1` |
| paymentId | `9715df8f-466d-4e10-858d-43a18c4716c4` |
| eventId requested | `50a558af-4210-4cdf-9ba6-870a24d5d7d8` |
| eventId result | `d542da96-412f-4bf4-b962-6947f4646d28` |
| correlationId original | `lab-t01-corr-20261112-1059-a7d1` |
| PSP transactionId | `dd9d763e-3b1e-42d6-9530-e9cee87b2ead` |

Linha do tempo:

- 2026-11-12 13:59:28.915 UTC (10:59:28.915 BRT): pagamento e Outbox criados atomicamente.
- O hook pós-COMMIT registrou `transactionActive=false` e lançou a falha sintética uma única vez. A resposta foi HTTP 500.
- Com o Relay parado: pagamento `PENDING`, Outbox `attempt_count=0`, `published_at=NULL`, `claimed_by=NULL`; busca Kafka encontrou zero ocorrências do `eventId`.
- Replay igual: HTTP 200, `idempotentReplay=true`, mesmo `paymentId`.
- Replay divergente: HTTP 409 com `Idempotency conflict`.
- 2026-11-12 14:00:19.627 UTC (11:00:19.627 BRT): Relay marcou o requested como publicado, tentativa 1.
- 2026-11-12 14:00:22.818 UTC: PSP gravou um único efeito `APPROVED`.
- 2026-11-12 14:00:22.941 UTC: Inbox concluiu o requested.
- Estado final: pagamento `SUCCEEDED`, uma chave idempotente, um requested, uma Inbox e um efeito PSP.

Limitação explícita: a API temporária não foi terminada de dentro do ponto pós-COMMIT. O teste usou uma exceção determinística depois do retorno do `TransactionTemplate` e depois encerrou a instância isolada. Portanto, isto comprova o invariante pós-COMMIT, mas não deve ser descrito como crash real da JVM nesse ponto exato.

## T03 — ACK, republicação e deduplicação

Identificadores:

| Campo | Valor |
|---|---|
| paymentId | `e1d04f4d-2d01-44fc-8a4e-75d3e3374cb1` |
| eventId requested | `331ddea3-4049-4850-b52e-4e68dabd482f` |
| eventId result | `17e201b5-f3fa-40de-8de4-aea060cd1caf` |
| correlationId | `lab-t03-corr-20261112-1101-b8e2` |
| PSP transactionId | `43f0bcea-d128-4da6-bc73-04746ce810a6` |

Evidência:

- 2026-11-12 14:01:43.016 UTC (11:01:43.016 BRT): o hook executou após o retorno do ACK Kafka e antes de `markPublished`; o Relay registrou `aborted_after_ack`.
- Após a falha controlada: Kafka tinha uma ocorrência, enquanto a Outbox continuava com `published_at=NULL`, `attempt_count=1`, claim de `outbox-relay-t03`.
- A instância isolada foi parada. Depois da expiração do claim, o Relay padrão recuperou a linha.
- 2026-11-12 14:02:26.604 UTC: Outbox marcada como publicada com `attempt_count=2`.
- Leitura direta do tópico encontrou duas mensagens com o mesmo `eventId`, `paymentId` e `correlationId`.
- Estado de negócio final: uma linha Inbox, uma transação PSP `APPROVED`, um evento `payment.result.v1` e pagamento `SUCCEEDED`.

Isto é entrega **at-least-once**: a mesma mensagem pode ser publicada mais de uma vez. A Inbox do Worker e a referência idempotente do PSP tornam o processamento de negócio idempotente; não existe exactly-once distribuído.

A falha pós-ACK foi uma exceção controlada seguida da parada da instância isolada, não uma pane espontânea da JVM.

## T05 — tolerância a uma réplica no Docker

- Override: `compose.ha.yaml`; balanceador: HAProxy com health checks e descoberta DNS Docker.
- Nove requisições iniciais foram distribuídas em round-robin por `api1`, `api2` e `api3`.
- Pagamento sintético: `92dea456-c511-46f3-ad2b-b0ae9717d61d`.
- `api2` foi parada. Seis health checks continuaram `UP` por `api1/api3`.
- Replay durante a falha: HTTP 200, mesmo `paymentId`, `idempotentReplay=true`.
- Banco: um pagamento, uma chave idempotente e um requested.
- `api2` voltou saudável e, após atualização DNS, voltou a receber tráfego.
- A topologia padrão de uma API foi restaurada ao final; o pagamento permaneceu consultável.

Snapshot HA medido: APIs 152,4 + 141,8 + 154,9 MiB; HAProxy 55,3 MiB; PostgreSQL 70,6 MiB; Kafka 424 MiB; Relay 165,3 MiB; Worker 219 MiB; PSP 170,8 MiB. Total aproximado dos componentes medidos: 1,55 GiB. O limite do HAProxy foi ajustado para 128 MiB após o ensaio.

Este teste é uma simulação de tolerância no Docker Compose. Ele não prova rescheduling, readiness, PodDisruptionBudget ou recuperação de pods pelo Kubernetes, e não iniciou kind, k3d, AKS ou os GATES 06/07.

## Alterações de código

- Hook pós-COMMIT da API, opt-in, uma única execução e chave-alvo explícita.
- Hook pós-ACK do Relay, opt-in, uma única execução e `eventId` alvo explícito.
- Ambos ficam inativos por padrão e só armam com `pixflow.lab.failure-injection.enabled=true` mais o alvo específico.
- Testes unitários cobrem desativação, seleção do alvo e execução única.
- `compose.ha.yaml` e `infra/haproxy/payment-api.cfg` fornecem a demonstração opcional de três réplicas.

O PixFlow Live já expõe dados reais do PostgreSQL: paymentId, status, Outbox, eventId, tentativas, Inbox, PSP, auditoria e lease. Não foram inseridos eventos fictícios na UI. ACK, duplicata e falha controlada permanecem comprovados por logs do Relay e leitura direta do Kafka; `attempt_count=2` aparece no detalhe real da Outbox.

## Comandos de reprodução

Validação automatizada:

```powershell
.\mvnw.cmd -B -ntp test
.\scripts\verify.ps1
docker compose config --quiet
docker compose -f compose.yaml -f compose.ha.yaml --profile ha config --quiet
```

T01/T02, em instância isolada:

```powershell
docker compose stop outbox-relay
docker compose build payment-api outbox-relay

$key = 'lab-t01-' + [guid]::NewGuid().ToString('N')
docker run --rm -d --name pixflow-lab-payment-api-t01 --network pixflow-lab-backend `
  -p 127.0.0.1:18080:8080 `
  -e PIXFLOW_API_BIND=0.0.0.0 `
  -e PIXFLOW_DB_URL=jdbc:postgresql://postgres:5432/pixflow `
  -e PIXFLOW_DB_USER=pixflow_app -e PIXFLOW_DB_PASSWORD=pixflow_lab_dev_only `
  -e PIXFLOW_LAB_FAILURE_INJECTION_ENABLED=true `
  -e PIXFLOW_LAB_FAILURE_INJECTION_AFTER_COMMIT_IDEMPOTENCY_KEY=$key `
  pixflow-lab-payment-api

# POST sintético para http://127.0.0.1:18080/api/v1/payments usando $key.
# Confirmar payment/outbox e ausência no Kafka, parar a instância, repetir o POST em :8080.
docker stop pixflow-lab-payment-api-t01
docker compose start outbox-relay
```

T03, depois de criar um pagamento com o Relay padrão parado e obter `$eventId`:

```powershell
docker run --rm -d --name pixflow-lab-outbox-relay-t03 --network pixflow-lab-backend `
  -e PIXFLOW_DB_URL=jdbc:postgresql://postgres:5432/pixflow `
  -e PIXFLOW_DB_USER=pixflow_app -e PIXFLOW_DB_PASSWORD=pixflow_lab_dev_only `
  -e PIXFLOW_KAFKA_BOOTSTRAP=kafka:9092 -e PIXFLOW_RELAY_OWNER=outbox-relay-t03 `
  -e PIXFLOW_RELAY_BATCH_SIZE=1 -e PIXFLOW_RELAY_CLAIM_TTL=10s `
  -e PIXFLOW_LAB_FAILURE_INJECTION_ENABLED=true `
  -e PIXFLOW_LAB_FAILURE_INJECTION_AFTER_KAFKA_ACK_EVENT_ID=$eventId `
  pixflow-lab-outbox-relay

docker logs pixflow-lab-outbox-relay-t03
docker stop pixflow-lab-outbox-relay-t03
docker compose start outbox-relay
```

T05:

```powershell
docker compose -f compose.yaml -f compose.ha.yaml --profile ha up -d --no-deps `
  --scale payment-api=3 payment-api payment-api-lb

docker stop pixflow-lab-payment-api-2
# Enviar health checks e replay pela porta 8080.
docker compose -f compose.yaml -f compose.ha.yaml --profile ha start payment-api

# Restaurar a topologia padrão sem remover volumes.
docker compose -f compose.yaml -f compose.ha.yaml --profile ha stop payment-api-lb
docker compose -f compose.yaml -f compose.ha.yaml --profile ha rm -f payment-api-lb
docker compose up -d --no-deps --scale payment-api=1 payment-api
```

## Riscos residuais

- A janela ACK/`published_at` continua at-least-once por projeto.
- O hook de T01 comprova after-commit determinístico, não morte exata do processo.
- Falhas controladas são observáveis em logs, não em uma tabela durável de incidentes.
- HAProxy depende da atualização DNS do Docker; houve poucos segundos até a réplica retornada voltar ao round-robin.
- O teste Docker não substitui um ensaio futuro em kind/k3d/AKS.

## Conclusão

**LAB OUTBOX/RESILIÊNCIA = PASS**

O resultado é fundamentado na preservação pós-COMMIT, recuperação da Outbox, duplicação real observada no Kafka, deduplicação real por Inbox, efeito PSP unitário, contrato HTTP idempotente e continuidade com uma de três APIs parada. As limitações acima não foram apresentadas como garantias mais fortes do que as evidências permitem.
