# Gate 05 - lease e fencing

## Decisao e persistencia

PostgreSQL e a autoridade da posse. A migration V005 adiciona `payment_lease_audit` e `worker_deferred_events`; V001-V004 permanecem intactas. `payments.fencing_token` existente e incrementado atomicamente em cada concessao; `lease_owner` e `lease_until` sao atualizados no mesmo `UPDATE`. O banco calcula concessao, renovacao, liberacao e vencimento com `clock_timestamp()`.

Toda transicao protegida usa `WHERE payment_id=? AND status=? AND lease_owner=? AND fencing_token=? AND lease_until > clock_timestamp()`. Zero linhas significa perda da posse: nao existe fallback de escrita incondicional. Auditoria, Inbox e Outbox do resultado sao persistidos na mesma transacao local. Nenhuma transacao permanece aberta durante GET/POST HTTP ao simulador.

O PSP recebe sempre `external_reference=pxf-{paymentId}`. O Worker consulta essa referencia antes de enviar POST. Um timeout ambiguo permanece UNKNOWN; o resultado externo nunca e inferido apenas pelo lease. O simulador e idempotente por referencia.

## Mensagens quando o lease esta ocupado

O listener grava o evento integral em `worker_deferred_events` e so entao confirma o offset Kafka. O publicador agenda itens vencidos com `FOR UPDATE SKIP LOCKED`, fecha a transacao antes do envio, aguarda o ACK do Kafka e apaga apenas a linha cuja claim/token ainda possui. Falhas mantem a linha, incrementam a tentativa e aplicam backoff exponencial. A entrega continua at-least-once; a Inbox permanece como deduplicacao do consumidor.

## Evidencia automatizada

`LeaseServiceIntegrationTest` cobre aquisicao concorrente, expiracao, token crescente, renovacao valida/obsoleta, adiamento duravel e envio adiado com falha/ACK. `PaymentProcessingIntegrationTest.staleWorkerCannotOverwriteResultAfterLeaseExpiresDuringPspCall` bloqueia A depois da chamada sintetica, vence o lease no banco, faz B consultar a mesma referencia e concluir, depois libera A. O teste exige token de B maior, UPDATE obsoleto rejeitado, estado final SUCCEEDED e uma chamada PSP; tambem verifica que a chamada PSP ocorre sem transacao ativa.

Esses testes usam PostgreSQL Testcontainers e dois proprietarios de Worker no caso A/B. Nao representam duas replicas Docker do servico em execucao. O teste de disputa de aquisicao executa concorrencia real no PostgreSQL, mas nao duas JVMs.

## Evidencia local observada

Pagamento sintetico `b99044bf-dfc1-418e-baa2-5c8621564807`: HTTP 201; Outbox `bb048601-44e2-482c-a09f-fcbfb53271a2`; estado PostgreSQL SUCCEEDED; fencing token 1; uma transacao PSP APPROVED para `pxf-b99044bf-dfc1-418e-baa2-5c8621564807`; Inbox concluida. A consulta `GET /internal/lab/payments/{paymentId}` apresenta o owner/token e os eventos ACQUIRED/RELEASED lidos do banco. A timeline preserva o historico apos liberacao do lease.

## Limites conhecidos

- A expiracao do lease impede gravacoes obsoletas no PostgreSQL; somente a idempotencia/consulta externa evita efeito PSP duplicado.
- A renovacao existe e tem teste para owner/token atuais, mas o Worker nao mantem heartbeat durante uma chamada externa demorada. O timeout/configuracao do PSP deve permanecer menor que o TTL em uso; se o lease vencer durante a chamada, fencing rejeita o resultado antigo e a repeticao consulta a mesma referencia.
- O adiamento cobre lease ocupado. Falha total do PostgreSQL impede gravar a fila duravel, portanto o offset nao e confirmado e Kafka conserva a mensagem para nova tentativa.
- Os estados UNKNOWN ainda aguardam o reconciliador do Gate 06.
- Um teste de lease nao prova garantia de exactly-once entre Kafka, banco e PSP.
