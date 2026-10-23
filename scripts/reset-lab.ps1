param([switch]$ConfirmReset)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
if (-not $ConfirmReset) { throw 'Reset recusado. Use .\scripts\reset-lab.ps1 -ConfirmReset para remover SOMENTE volumes pixflow-lab.' }
$answer = Read-Host 'Digite PIXFLOW-LAB para confirmar a remoção exclusiva dos volumes desta POC'
if ($answer -cne 'PIXFLOW-LAB') { throw 'Confirmação inválida; nada removido.' }
Invoke-PixFlowCompose down --volumes --remove-orphans
Write-Host 'Somente recursos declarados pelo projeto pixflow-lab foram removidos.'
