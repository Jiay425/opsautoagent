$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "D:\Java\jdk17"
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
Set-Location $repoRoot

$logDir = Join-Path $repoRoot "data\log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir "eval-app-start.log"

mvn -q -pl ops-autoagent-app spring-boot:run "-Dspring-boot.run.profiles=full" *> $logFile

