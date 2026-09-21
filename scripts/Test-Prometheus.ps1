#requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryPath = Split-Path -Parent $PSScriptRoot
# Always build/test the current source with Java 21 first; never silently use a stale host JAR.
& (Join-Path $PSScriptRoot 'Test-Java21.ps1')
$runId = [Guid]::NewGuid().ToString('N')
$networkName = "greenai-smoke-$runId"
$evidencePath = Join-Path $repositoryPath "target/prometheus-smoke/$runId"
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null
$jarPath = Join-Path $repositoryPath 'target/java21-evidence/monitoring-service-0.0.1-SNAPSHOT.jar'
$smokePath = Join-Path $PSScriptRoot 'smoke'
$javaImage = 'eclipse-temurin:21-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438'
$prometheusImage = 'prom/prometheus:v3.13.3@sha256:6976aa8a60fec930796ce5772b8d12da7a318a5daa8d40d69c5c7819a05eeed7'
$exporterImage = 'prom/node-exporter:v1.12.1@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0'
$containerIds = [System.Collections.Generic.List[string]]::new()
$networkCreated = $false
function Invoke-Docker([string[]]$DockerArguments) {
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($DockerArguments[0]) (exit $LASTEXITCODE)" }
}
$javaImage, $prometheusImage, $exporterImage | Set-Content -LiteralPath (Join-Path $evidencePath 'images.txt')
try {
    Invoke-Docker @('network', 'create', '--label', "greenai.test.run=$runId", $networkName) | Out-Null
    $networkCreated = $true
    $exporterId = Invoke-Docker @('run', '-d', '--name', "$networkName-exporter", '--label', "greenai.test.run=$runId",
        '--network', $networkName, '--network-alias', 'exporter', '--cpus', '0.5', '--memory', '128m', $exporterImage)
    $containerIds.Add($exporterId)
    $prometheusId = Invoke-Docker @('run', '-d', '--name', "$networkName-prometheus", '--label', "greenai.test.run=$runId",
        '--network', $networkName, '--network-alias', 'prometheus', '--cpus', '1', '--memory', '512m',
        '--tmpfs', '/prometheus:rw,size=128m,mode=1777', '--mount', "type=bind,source=$smokePath/prometheus.yaml,target=/etc/prometheus/prometheus.yml,readonly",
        $prometheusImage, '--config.file=/etc/prometheus/prometheus.yml', '--storage.tsdb.path=/prometheus', '--storage.tsdb.retention.time=1h')
    $containerIds.Add($prometheusId)
    $monitoringId = Invoke-Docker @('run', '-d', '--name', "$networkName-monitoring", '--label', "greenai.test.run=$runId",
        '--network', $networkName, '--network-alias', 'monitoring', '--cpus', '1', '--memory', '512m',
        '--mount', "type=bind,source=$jarPath,target=/app/service.jar,readonly",
        '-e', 'MONITORING_PROMETHEUS_URL=http://prometheus:9090', '-e', 'MONITORING_PROMETHEUS_DEFAULT_CLUSTER=smoke-lab',
        $javaImage, 'java', '-Xmx256m', '-jar', '/app/service.jar')
    $containerIds.Add($monitoringId)
    $clientScript = 'set -eu; mkdir /tmp/client; cd /tmp/client; jar xf /app/service.jar BOOT-INF/lib; java -cp "BOOT-INF/lib/*" /smoke/MonitoringSmoke.java'
    $clientArguments = @('run', '--rm', '--network', $networkName, '--cpus', '1', '--memory', '512m',
        '--mount', "type=bind,source=$jarPath,target=/app/service.jar,readonly",
        '--mount', "type=bind,source=$smokePath,target=/smoke,readonly",
        '--mount', "type=bind,source=$evidencePath,target=/evidence", $javaImage, 'sh', '-c')
    Invoke-Docker ($clientArguments + $clientScript) | Tee-Object -FilePath (Join-Path $evidencePath 'smoke.log')
    # Only stop the Prometheus instance created by this run to exercise failure handling.
    Invoke-Docker @('stop', $prometheusId) | Out-Null
    Invoke-Docker ($clientArguments + "$clientScript unavailable") | Tee-Object -FilePath (Join-Path $evidencePath 'unavailable.log')
    'PASS' | Set-Content -LiteralPath (Join-Path $evidencePath 'result.txt')
    Write-Output "Real Prometheus smoke passed. Evidence: $evidencePath"
} finally {
    foreach ($containerId in $containerIds) {
        & docker logs $containerId 2>&1 | Set-Content -LiteralPath (Join-Path $evidencePath "$containerId.log")
        $owner = & docker inspect --format '{{ index .Config.Labels "greenai.test.run" }}' $containerId
        if ($LASTEXITCODE -eq 0 -and $owner -eq $runId) { & docker rm -f $containerId | Out-Null }
    }
    if ($networkCreated) {
        $owner = & docker network inspect --format '{{ index .Labels "greenai.test.run" }}' $networkName
        if ($LASTEXITCODE -eq 0 -and $owner -eq $runId) { & docker network rm $networkName | Out-Null }
    }
}
