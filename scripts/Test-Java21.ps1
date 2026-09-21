[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryPath = Split-Path -Parent $PSScriptRoot
$evidencePath = Join-Path $repositoryPath 'target/java21-evidence'
New-Item -ItemType Directory -Path $evidencePath -Force | Out-Null

# Toolchain image only. This script does not create an application image or Dockerfile.
$toolchainImage = 'eclipse-temurin:21-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438'
$containerScript = @'
set -eu
mkdir -p /tmp/monitoring-build
cp /workspace/pom.xml /workspace/mvnw /tmp/monitoring-build/
cp -R /workspace/.mvn /workspace/src /tmp/monitoring-build/
cd /tmp/monitoring-build
java -version
sh ./mvnw -version
set +e
sh ./mvnw -B -ntp verify
result=$?
set -e
if [ -d target/surefire-reports ]; then
  cp -R target/surefire-reports /evidence/
fi
if [ "$result" -eq 0 ]; then
  cp target/monitoring-service-0.0.1-SNAPSHOT.jar /evidence/
fi
exit "$result"
'@

# Git on Windows may check this file out with CRLF; Linux sh requires LF.
$containerScript = $containerScript.Replace("`r`n", "`n")

$toolchainImage | Set-Content -LiteralPath (Join-Path $evidencePath 'toolchain-image.txt')
& docker run --rm --cpus 2 --memory 2g `
    --mount "type=bind,source=$repositoryPath,target=/workspace,readonly" `
    --mount "type=bind,source=$evidencePath,target=/evidence" `
    $toolchainImage sh -c $containerScript 2>&1 |
    Tee-Object -FilePath (Join-Path $evidencePath 'container.log')
$containerExit = $LASTEXITCODE
if ($containerExit -ne 0) {
    throw "Java 21 container verification failed (exit $containerExit). See $evidencePath/container.log"
}
Write-Output "Java 21 verification passed. Evidence: $evidencePath"
