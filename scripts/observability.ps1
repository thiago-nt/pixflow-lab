param(
    [ValidateSet("Up", "Stop", "Status")]
    [string]$Action = "Status",
    [ValidateSet("Metrics", "Logs", "All")]
    [string]$Profile = "Metrics"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$compose = @("compose", "-f", "compose.yaml", "-f", "compose.observe.yaml")
$secretDir = Join-Path $repo "secrets"

function New-SecretIfMissing([string]$name) {
    New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
    $path = Join-Path $secretDir $name
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 32
        $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $generator.GetBytes($bytes)
        } finally {
            $generator.Dispose()
        }
        $value = [Convert]::ToBase64String($bytes).Replace("+", "-").Replace("/", "_").TrimEnd("=")
        [IO.File]::WriteAllText($path, $value, [Text.UTF8Encoding]::new($false))
    }
}

function Convert-ToMiB([string]$usage) {
    $value, $unit = $usage.Trim() -split '(?=[A-Za-z])', 2
    switch -Regex ($unit) {
        '^GiB' { return [double]$value * 1024 }
        '^MiB' { return [double]$value }
        '^KiB' { return [double]$value / 1024 }
        default { return 0 }
    }
}

function Assert-LogProfileCapacity {
    $totalBytes = [double](docker info --format '{{.MemTotal}}')
    $totalMiB = $totalBytes / 1MB
    $usedMiB = 0
    docker stats --no-stream --format '{{.MemUsage}}' | ForEach-Object {
        $used = ($_ -split '/')[0]
        $usedMiB += Convert-ToMiB $used
    }
    $availableMiB = $totalMiB - $usedMiB
    Write-Host ("Docker memory: {0:N0} MiB total, {1:N0} MiB used, {2:N0} MiB available" -f $totalMiB, $usedMiB, $availableMiB)
    if ($availableMiB -lt 2300) {
        throw "observe-logs requires at least 2300 MiB of currently available Docker memory; profile was not started"
    }
}

$metricsServices = @("prometheus", "grafana")
$logServices = @("elasticsearch", "elasticsearch-init", "kibana", "kibana-init", "fluent-bit")
$selectedServices = if ($Profile -eq "Metrics") { $metricsServices } elseif ($Profile -eq "Logs") { $logServices } else { $metricsServices + $logServices }
$profiles = if ($Profile -eq "Metrics") { @("--profile", "observe-metrics") } elseif ($Profile -eq "Logs") { @("--profile", "observe-logs") } else { @("--profile", "observe-metrics", "--profile", "observe-logs") }

Push-Location $repo
try {
    if ($Action -eq "Up") {
        if ($Profile -in @("Metrics", "All")) { New-SecretIfMissing "grafana_admin_password.txt" }
        if ($Profile -in @("Logs", "All")) {
            Assert-LogProfileCapacity
            New-SecretIfMissing "elastic_password.txt"
        }
        & docker @compose @profiles up -d @selectedServices
    } elseif ($Action -eq "Stop") {
        & docker @compose @profiles stop @selectedServices
    } else {
        & docker @compose @profiles ps @selectedServices
    }
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose returned exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
