. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
Invoke-PixFlowCompose up -d --wait postgres kafka
Invoke-PixFlowCompose run --rm flyway
Invoke-PixFlowCompose run --rm kafka-init
Invoke-PixFlowCompose up -d --build --wait payment-api
Invoke-PixFlowCompose up -d --build --wait outbox-relay
Invoke-PixFlowCompose up -d --build --wait psp-simulator payment-worker
Invoke-PixFlowCompose ps
