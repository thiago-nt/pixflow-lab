# GATE 06 — Reconciliation Worker e recuperação de UNKNOWN

Data da validação: 2026-11-12  
Base preservada: `efc8fd4` (`test(resilience): add deterministic outbox failure lab`)

## Resultado

**GATE 06 = PASS**

O worker consulta o PSP exclusivamente por `GET` e pela referência externa idempotente original. Ele não possui operação de cobrança. Aquisição de trabalho usa `FOR UPDATE ... SKIP LOCKED`, lease no pagamento, token de fencing e escrita final condicional. APPROVED e REJECTED confirmados são persistidos na mesma transação que o evento Outbox e as auditorias. Ausência de evidência mantém UNKNOWN com retry ou termina em MANUAL_REVIEW; nunca vira rejeição automática.

## Migration

- `V006__reconciliation_worker.sql`
- Cria `reconciliation_jobs` e `reconciliation_audit`, índices de vencimento/histórico e constraints de claim/auditoria.
- Amplia somente o cenário sintético do PSP para `REJECTED_TIMEOUT`, permitindo provar uma rejeição cujo HTTP de resposta foi perdido.
- Flyway: 6 migrations validadas; schema `public` avançou de `005` para `006` sem remover volumes.

## Evidências E2E reais

| Cenário | paymentId | Antes | Depois | Tentativas | Efeito PSP |
|---|---|---:|---:|---:|---:|
| PSP aprovou e resposta se perdeu | `3941e428-909f-4c19-9575-63982f8729d9` | UNKNOWN | SUCCEEDED | 1 | 1 |
| PSP rejeitou e resposta se perdeu | `cc9b87c2-e7bf-4a14-8f70-2ee5496287a2` | UNKNOWN | FAILED | 1 | 1 |
| PSP indisponível e depois recuperado | `a191108d-f2d5-46b9-a821-5065d76623b1` | UNKNOWN | SUCCEEDED | 5 | 1 |
| Referência inexistente | `bba057d2-16a7-46a4-94d4-87fc96918e78` | UNKNOWN | MANUAL_REVIEW | 5 | 0 |
| APPROVED normal + replay HTTP | `0b76b20a-c228-4135-ad6b-58c66995bffb` | PENDING | SUCCEEDED | n/a | 1 |

No caso indisponível, as quatro falhas temporárias registraram backoff aproximado de 2, 4, 8 e 16 segundos com jitter; o quinto GET recuperou APPROVED. No caso inexistente, quatro respostas NOT_FOUND foram reagendadas e a quinta tentativa encaminhou para MANUAL_REVIEW com `RECONCILIATION_EXHAUSTED`. Não houve linha no PSP nem segunda cobrança.

As auditorias persistidas contêm `payment_id`, `correlation_id`, número da tentativa, owner, fencing token, referência externa, outcome, PSP transaction id, detalhe e timestamp. O PixFlow Live expõe os mesmos dados reais, incluindo próxima tentativa, último resultado/erro e histórico de transição.

## Testes

Comando final:

```powershell
mvn.cmd -B -ntp test
```

Resultado: **52 testes, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**.

Cobertura nova/diretamente relevante:

- UNKNOWN → SUCCEEDED e UNKNOWN → FAILED.
- PSP indisponível, status ainda desconhecido e NOT_FOUND com retry seguro.
- Restart após lookup e recuperação de claim expirado.
- Duas instâncias disputando o mesmo job.
- Preservação de estado terminal e rejeição de fencing obsoleto.
- Retry esgotado para MANUAL_REVIEW.
- PSP `REJECTED_TIMEOUT` e idempotência por referência.
- Replay Inbox/Kafka sem novo efeito financeiro (cobertura anterior preservada).

Validação da stack:

```text
INFRASTRUCTURE_VERIFY=PASS
```

## Memória observada

Uso total aproximado: **1.65 GiB** de 5.93 GiB disponíveis no Docker Desktop.

| Serviço | Uso |
|---|---:|
| reconciliation-worker | 143.9 MiB |
| psp-simulator | 142.3 MiB |
| payment-api | 144.7 MiB |
| payment-worker | 184.9 MiB |
| outbox-relay | 170.7 MiB |
| PostgreSQL | 63.61 MiB |
| Kafka | 509.8 MiB |
| Kafka UI + pgweb | 324.84 MiB |

## Riscos residuais

- MANUAL_REVIEW é um estado seguro e auditável, mas a fila operacional humana/alerta externo fica para etapa futura; nenhum GATE 07 foi iniciado.
- O endpoint de consulta do PSP é interno à rede do laboratório e não modela autenticação de provedor real.
- O polling é apropriado ao laboratório; em escala de produção exigiria métricas/SLOs e dimensionamento de índices e lotes.

## Exclusões preservadas

Não pertencem ao GATE 06 e não devem ser incluídos no commit:

- `README.md`
- `docs/TEST_REPORT.md`
- `docs/ETAPA02_DEMO.md`
- `.env`, `secrets/`, `target/`, logs e dados persistidos

`compose.ha.yaml`, volumes PostgreSQL/Kafka e recursos existentes não foram alterados.
