param([ValidateSet('postgres','kafka','flyway','kafka-init','payment-api')][string]$Service)
. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
if ($Service) { Invoke-PixFlowCompose logs --tail 200 $Service } else { Invoke-PixFlowCompose logs --tail 200 }
