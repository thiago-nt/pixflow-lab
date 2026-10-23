. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository

foreach ($command in 'docker','git') {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Pré-requisito ausente: $command" }
}
docker version | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop não está acessível.' }

$envFile = Join-Path $script:ProjectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $script:ProjectRoot '.env.example') -Destination $envFile
    Write-Host '.env criado a partir de .env.example (credenciais locais sem valor real).'
} else {
    Write-Host '.env existente preservado.'
}

& (Join-Path $PSScriptRoot 'up.ps1')
& (Join-Path $PSScriptRoot 'verify.ps1')
