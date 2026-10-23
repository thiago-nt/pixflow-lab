. (Join-Path $PSScriptRoot 'common.ps1')
Assert-PixFlowRepository
Invoke-PixFlowCompose ps -a
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' $(docker compose -p $script:ComposeProject ps -q)
