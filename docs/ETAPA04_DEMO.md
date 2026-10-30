# GATE 04 — Worker e simulador de PSP

O perfil local processa mensagens `payment.requested.v1` com o grupo `pixflow-payment-worker-v1`. O Worker grava `PROCESSING` e `SENT_TO_PSP` em transações PostgreSQL curtas antes de chamar o PSP HTTP. O ledger fictício `psp_simulator.transactions` persiste uma única operação por `payment_id` e `external_reference`. Após obter resultado, o Worker grava status, auditoria, Inbox e evento `payment.result.v1` na mesma transação local. O Relay existente entrega esse resultado ao Kafka.

## Inicialização

```powershell
cd C:\poc\pixflow-lab
.\scripts\up.ps1
.\scripts\verify.ps1
```

O painel fica em http://127.0.0.1:8080/lab. Selecione o cenário sintético `APPROVED`, `REJECTED` ou `TIMEOUT` antes de criar um pagamento. `TIMEOUT` persiste um efeito aprovado no ledger do PSP, perde a resposta HTTP e deixa o pagamento em `UNKNOWN`; resolução automática é responsabilidade futura do GATE 06.

No detalhe do pagamento, a API consulta status, auditoria, Outbox, Inbox e transação do simulador diretamente no PostgreSQL. A evidência do Worker inclui consumer group, eventId e completedAt. Kafka UI continua em http://127.0.0.1:8085.

## Consultas somente de leitura

```sql
SELECT payment_id, status, external_reference, result_code, version
FROM payments ORDER BY created_at DESC LIMIT 20;

SELECT consumer_name, event_id, completed_at
FROM inbox_processed ORDER BY completed_at DESC LIMIT 20;

SELECT payment_id, external_reference, simulation_scenario, outcome, response_lost
FROM psp_simulator.transactions ORDER BY created_at DESC LIMIT 20;

SELECT aggregate_id AS payment_id, event_id, topic, published_at
FROM outbox_events WHERE topic = 'payment.result.v1' ORDER BY created_at DESC LIMIT 20;
```

## Semântica e limites

- O ACK manual do offset acontece depois do resultado durável, Inbox e Outbox; evento inválido só é reconhecido depois de ACK do tópico DLT. Falha de banco/PSP não reconhecida propaga erro e deixa o offset sem ACK.
- A intenção `SENT_TO_PSP` é persistida antes da rede. Após redelivery, o Worker consulta a referência estável antes de reenviar; o POST do simulador é idempotente por referência.
- `UNKNOWN` é persistido quando o PSP retorna a resposta perdida simulada. Não há retry cego nem reconciliação nesta etapa.
- Não há lease/fencing; configure uma instância do Worker nesta etapa. O PSP é uma simulação local, sem conectividade com Pix, SPI, DICT ou adquirente real.
- A entrega Kafka segue at-least-once. A tabela Inbox protege efeitos locais da repetição; não existe exactly-once distribuído.

## Evidência E2E observada

Execução local via Payment API, PostgreSQL, Outbox Relay, Kafka, Worker e PSP Simulator:

| Cenário | paymentId | Status PostgreSQL | Resultado PSP | eventId `payment.requested` | eventId `payment.result` |
|---|---|---|---|---|---|
| APPROVED | `fd799b85-d2e4-422a-9955-bcc194dbfc20` | `SUCCEEDED` | `APPROVED` | `23dbd997-f0ce-48af-af1e-a81c762ffd97` | `43ecb416-d99e-442d-a541-afdfed9a82a8` |
| REJECTED | `d62d8ba4-1914-49d5-aa26-f75d38523ef0` | `FAILED` | `REJECTED` | `6add9a0c-170a-43d2-a6b2-f00b1482c5fb` | `b5f91546-3cb9-441b-9c0d-9ad1bbb4c740` |
| TIMEOUT | `cf408a9f-db2a-47ed-b5f4-07cfddc6b406` | `UNKNOWN` | `APPROVED`, resposta perdida | `bff7fad0-dcaf-422f-b9a7-e0f304155a3e` | `81d6a0a2-6857-4748-acb9-d97c005ce94c` |

Consultas no PostgreSQL mostraram uma Inbox, um efeito do simulador e um evento de resultado por pagamento; Outbox requested/result estavam publicados. Kafka foi lido diretamente e confirmou as mesmas chaves `paymentId` e eventos acima. Reenviei o primeiro evento requested com o mesmo `eventId`: depois da redelivery, o pagamento continuou `SUCCEEDED`, com Inbox=1, PSP=1 e Outbox result=1. Após restart do Worker, os três pagamentos mantiveram status e contagens; o consumer group terminou com lag 0. O snapshot de memória dos containers do projeto ficou em aproximadamente 1,5 GiB de um limite Docker de cerca de 5,8 GiB.
