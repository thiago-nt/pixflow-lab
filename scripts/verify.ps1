. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
$failures = 0

$resolvedConfigJson = docker compose -p $script:ComposeProject config --format json 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível resolver a configuração Compose.' }
$resolvedConfig = $resolvedConfigJson | ConvertFrom-Json
$databaseUser = $resolvedConfig.services.postgres.environment.POSTGRES_USER
$databaseName = $resolvedConfig.services.postgres.environment.POSTGRES_DB
if ([string]::IsNullOrWhiteSpace($databaseUser) -or [string]::IsNullOrWhiteSpace($databaseName)) {
    throw 'POSTGRES_USER ou POSTGRES_DB ausente na configuração Compose resolvida.'
}

function Gate([string]$Name, [scriptblock]$Check) {
    try { & $Check; if ($LASTEXITCODE -ne 0) { throw "exit $LASTEXITCODE" }; Write-Host "PASS  $Name" -ForegroundColor Green }
    catch { $script:failures++; Write-Host "FAIL  $Name - $($_.Exception.Message)" -ForegroundColor Red }
}

Gate 'Compose config válido' { docker compose -p $script:ComposeProject config --quiet }
Gate 'PostgreSQL healthy' { docker compose -p $script:ComposeProject exec -T postgres pg_isready -U $databaseUser -d $databaseName }
Gate 'Flyway aplicado' { docker compose -p $script:ComposeProject exec -T postgres psql -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -Atc "SELECT CASE WHEN COUNT(*) = 1 THEN 'V001_OK' ELSE 'INVALID' END FROM flyway_schema_history WHERE success" | Select-String 'V001_OK' | Out-Null }
Gate 'Tabelas obrigatórias' { docker compose -p $script:ComposeProject exec -T postgres psql -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -Atc "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('payments','idempotency_keys','outbox_events','inbox_processed','payment_audit')" | Select-String '^5$' | Out-Null }
Gate 'Kafka healthy' { docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | Out-Null }
Gate 'Tópicos explícitos' { $topics = docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list; foreach ($required in 'payment.requested.v1','payment.result.v1','payment.dlt.v1') { if ($required -notin $topics) { throw "tópico ausente: $required" } } }

Gate 'Kafka publish/consume' {
    $probe = 'pixflow-probe-' + [guid]::NewGuid().ToString('N')
    $topic = 'infra.verify.' + [guid]::NewGuid().ToString('N')
    docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic $topic --partitions 1 --replication-factor 1 | Out-Null
    try {
        $probe | docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $topic
        $received = docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic $topic --from-beginning --max-messages 1 --timeout-ms 10000
        if ($received -notcontains $probe) { throw "mensagem esperada não consumida" }
    } finally {
        docker compose -p $script:ComposeProject exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic $topic | Out-Null
    }
}

docker compose -p $script:ComposeProject ps
if ($failures -gt 0) { throw "$failures gate(s) falharam." }
Write-Host 'INFRASTRUCTURE_VERIFY=PASS' -ForegroundColor Green
