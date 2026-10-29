# Demonstração da ETAPA 03

Fluxo: Payment API → PostgreSQL (outbox_events) → Outbox Relay → Kafka payment.requested.v1. Os dados são sintéticos. Worker, PSP e reconciliação ainda não foram implementados.

## Iniciar e verificar

```powershell
cd C:\poc\pixflow-lab
.\scripts\up.ps1
.\scripts\verify.ps1
```

Abra PixFlow Live em http://127.0.0.1:8080/lab, Kafka UI em http://127.0.0.1:8085 e PostgreSQL Viewer em http://127.0.0.1:8086. Os painéis podem ser iniciados separadamente com .\scripts\inspect.ps1 Start.

## Criar um pagamento sintético

```powershell
$headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$body = @{
  accountId = 'test-account-1'
  amount = 12.34
  currency = 'BRL'
  merchantReference = ('demo-' + [guid]::NewGuid().ToString('N'))
} | ConvertTo-Json

$payment = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/v1/payments' -Headers $headers -ContentType 'application/json' -Body $body
$payment
```

No PixFlow Live, abra o pagamento para ver paymentId, eventId, tentativas e published_at reais. No Kafka UI, selecione payment.requested.v1 e localize a mensagem pela chave paymentId. O payload contém o mesmo eventId criado pela API.

## Consultas SQL de leitura

No pgweb, use o usuário inspector e execute:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT event_id, aggregate_id AS payment_id, topic, event_type,
       partition_key, attempt_count, published_at, last_error
FROM outbox_events
ORDER BY created_at DESC
LIMIT 20;
```

O usuário do pgweb é somente leitura.

## Garantias e limite desta etapa

O relay aguarda confirmação Kafka antes de preencher published_at. Uma falha após ACK e antes do update pode gerar duplicata após expirar o claim; Inbox/idempotência do consumidor será responsabilidade da etapa seguinte. Não há garantia exactly-once ponta a ponta. A recuperação por parada/reinício do broker foi validada na stack PixFlow sem remover volumes.
