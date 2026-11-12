# ETAPA 02 - Demonstração

Todos os dados abaixo são sintéticos. Nesta etapa não existe publicação Kafka, Worker, PSP, cobrança ou integração Pix real.

## Inicialização

```powershell
cd C:\poc\pixflow-lab
.\scripts\up.ps1
.\scripts\verify.ps1
```

Abra:

- PixFlow Live: http://127.0.0.1:8080/lab
- Kafka UI preservada: http://127.0.0.1:8085
- PostgreSQL Viewer preservado: http://127.0.0.1:8086

## Fluxo reproduzível

No PixFlow Live, informe conta e referência sintéticas, valor positivo em BRL e uma chave de idempotência. A primeira submissão retorna HTTP 201 e estado `PENDING`. O painel exibe somente registros persistidos em `payments`, `idempotency_keys`, `outbox_events` e `payment_audit`.

Use **Reenviar igual** para obter HTTP 200 e o mesmo `paymentId`. Use **Alterar valor e reenviar** para demonstrar HTTP 409. As colunas Kafka, Worker, PSP e Reconciliação permanecem cinza e marcadas `AINDA NÃO IMPLEMENTADO`.

Exemplo por PowerShell:

```powershell
$key = 'demo-' + [guid]::NewGuid()
$headers = @{ 'Idempotency-Key'=$key; 'X-Correlation-ID'=('corr-' + [guid]::NewGuid()) }
$body = @{ accountId='test-account-1'; amount=123.45; currency='BRL'; merchantReference=('demo-' + [guid]::NewGuid()) } | ConvertTo-Json
$created = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/payments -Headers $headers -ContentType application/json -Body $body
$replay = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/payments -Headers $headers -ContentType application/json -Body $body
$created
$replay
```

No pgweb, use consultas somente leitura:

```sql
SELECT payment_id, tenant_id, amount_minor, currency, status, merchant_reference, created_at FROM payments ORDER BY created_at DESC LIMIT 20;
SELECT event_id, aggregate_id, topic, event_type, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 20;
SELECT payment_id, previous_status, new_status, correlation_id, occurred_at FROM payment_audit ORDER BY occurred_at DESC LIMIT 20;
```

`published_at` permanece nulo nesta etapa, pois o Outbox Relay pertence à Etapa 03.
