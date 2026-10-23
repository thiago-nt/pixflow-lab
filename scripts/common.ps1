$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:ComposeProject = 'pixflow-lab'

function Assert-PixFlowRepository {
    $expected = [System.IO.Path]::GetFullPath('C:\poc\pixflow-lab')
    $actual = [System.IO.Path]::GetFullPath($script:ProjectRoot)
    if ($actual -ne $expected) { throw "Diretório recusado: $actual. Esperado: $expected" }
    if (-not (Test-Path -LiteralPath (Join-Path $actual 'compose.yaml'))) { throw 'compose.yaml não encontrado.' }
    Set-Location -LiteralPath $actual
}

function Invoke-PixFlowCompose {
    & docker compose -p $script:ComposeProject @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose falhou com código $LASTEXITCODE" }
}
