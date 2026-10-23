param(
    [Parameter(Position = 0)]
    [ValidateSet('Start', 'Status', 'Stop')]
    [string]$Action = 'Status'
)

. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository

$secretDirectory = Join-Path $script:ProjectRoot 'secrets'
$secretFile = Join-Path $secretDirectory 'pgweb_password.txt'

function Initialize-InspectorSecret {
    if (Test-Path -LiteralPath $secretFile) { return }
    New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    [System.IO.File]::WriteAllText($secretFile, $password, [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Senha local do PostgreSQL Viewer criada em arquivo ignorado pelo Git.'
}

switch ($Action) {
    'Start' {
        Initialize-InspectorSecret
        Invoke-PixFlowCompose --profile inspect run --rm inspector-db-init
        Invoke-PixFlowCompose --profile inspect up -d kafka-ui pgweb
        Invoke-PixFlowCompose --profile inspect ps kafka-ui pgweb
    }
    'Status' {
        Invoke-PixFlowCompose --profile inspect ps kafka-ui pgweb
        $ids = docker compose -p $script:ComposeProject --profile inspect ps -q kafka-ui pgweb
        if ($ids) {
            docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' $ids
        }
    }
    'Stop' {
        Invoke-PixFlowCompose --profile inspect stop kafka-ui pgweb
        Write-Host 'Painéis inspect parados; PostgreSQL, Kafka, rede e volumes base foram preservados.'
    }
}
