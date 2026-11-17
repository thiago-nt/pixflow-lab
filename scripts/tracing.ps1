[CmdletBinding()]
param(
    [ValidateSet("Up", "Stop", "Status")]
    [string]$Action = "Status",
    [ValidateRange(0.0, 1.0)]
    [double]$SampleRatio = 1.0
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$compose = @("compose", "-f", "compose.yaml", "-f", "compose.observe.yaml", "-f", "compose.tracing.yaml")
$tracingServices = @("tempo", "otel-collector")
$instrumentedServices = @("payment-api", "outbox-relay", "payment-worker", "psp-simulator", "reconciliation-worker")

function Assert-TracingCapacity {
    $total = [int64](docker info --format '{{.MemTotal}}')
    $used = [int64]0
    docker stats --no-stream --format '{{.MemUsage}}' | ForEach-Object {
        if ($_ -match '^([0-9.]+)(KiB|MiB|GiB) /') {
            $factor = switch ($matches[2]) { "KiB" { 1KB }; "MiB" { 1MB }; "GiB" { 1GB } }
            $used += [int64]([double]$matches[1] * $factor)
        }
    }
    $availableMiB = [math]::Floor(($total - $used) / 1MB)
    if ($availableMiB -lt 900) {
        throw "Tracing requires at least 900 MiB available; Docker currently has $availableMiB MiB."
    }
    Write-Host "Docker memory available: $availableMiB MiB (tracing preflight passed)."
}

switch ($Action) {
    "Up" {
        Assert-TracingCapacity
        $env:PIXFLOW_TRACE_SAMPLE_RATIO = $SampleRatio.ToString([System.Globalization.CultureInfo]::InvariantCulture)
        & docker @compose run --rm flyway
        if ($LASTEXITCODE -ne 0) { throw "Flyway migration failed." }
        & docker @compose --profile observe-metrics --profile observe-traces up -d --build @tracingServices prometheus grafana @instrumentedServices
        if ($LASTEXITCODE -ne 0) { throw "Could not start tracing profile." }
    }
    "Stop" {
        & docker @compose --profile observe-traces stop @tracingServices
        if ($LASTEXITCODE -ne 0) { throw "Could not stop tracing services." }
    }
    "Status" {
        & docker @compose --profile observe-metrics --profile observe-traces ps @tracingServices prometheus grafana @instrumentedServices
    }
}
