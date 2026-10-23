# Infraestrutura local — ETAPA 01

## Componentes fixados

| Componente | Imagem | Limite | Persistência |
|---|---|---:|---|
| PostgreSQL | `postgres:18.4-alpine` | 768 MiB / 1 CPU | `pixflow-lab-postgres-data` |
| Kafka KRaft single-node | `apache/kafka:4.1.1` | 1 GiB / 1 CPU | `pixflow-lab-kafka-data` |
| Flyway | `flyway/flyway:11.20.2-alpine` | 256 MiB / 0,5 CPU | job descartável |
| Kafka init | `apache/kafka:4.1.1` | 256 MiB / 0,5 CPU | job descartável |

Todos os recursos pertencem ao projeto Compose `pixflow-lab` e à rede bridge dedicada `pixflow-lab-backend`. Não existe `container_name`. Portas publicadas aceitam conexões somente via loopback:

- PostgreSQL: host `127.0.0.1:55432`; containers `postgres:5432`.
- Kafka: host `127.0.0.1:19092`; containers `kafka:9092`.
- Controller KRaft `29093` não é publicado.

O Kafka é single-node e não demonstra alta disponibilidade. O listener local usa PLAINTEXT exclusivamente para laboratório; não é configuração de produção.

## Operação PowerShell

```powershell
cd C:\poc\pixflow-lab
Copy-Item .env.example .env # opcional; bootstrap não sobrescreve um .env existente
.\scripts\up.ps1
.\scripts\verify.ps1
.\scripts\status.ps1
.\scripts\logs.ps1 -Service kafka
.\scripts\down.ps1
```

`down.ps1` preserva dados. `reset-lab.ps1 -ConfirmReset` exige uma segunda confirmação textual e remove somente recursos declarados pelo projeto `pixflow-lab`. Não há comando prune nos scripts.

## Migração V001

Flyway cria `payments`, `idempotency_keys`, `outbox_events`, `inbox_processed` e `payment_audit`, incluindo constraints e índices iniciais. O relógio PostgreSQL é usado nos defaults temporais. A migração é validada e reaplicações são idempotentes pelo histórico Flyway.

## Tópicos explícitos

- `payment.requested.v1`
- `payment.result.v1`
- `payment.dlt.v1`

Autocreate está desabilitado. Cada tópico possui três partições e fator de replicação 1, adequado somente ao laboratório single-node.

## Isolamento

Volumes: `pixflow-lab-postgres-data` e `pixflow-lab-kafka-data`. Rede: `pixflow-lab-backend`. Nenhum volume QPO é referenciado pelo Compose ou pelos scripts.
