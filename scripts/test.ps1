. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
& (Join-Path $PSScriptRoot 'verify.ps1')
