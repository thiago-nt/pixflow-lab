. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
Invoke-PixFlowCompose down --remove-orphans
Write-Host 'Volumes pixflow-lab preservados. Nenhum recurso global foi removido.'
