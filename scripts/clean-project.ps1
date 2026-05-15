$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$rootPath = $root.Path

$targets = @(
    ".idea",
    ".run",
    "mvn-compile.log",
    "runtime-validation-app.err.log",
    "runtime-validation-app.out.log",
    "data\log",
    "ops-autoagent-app\src\test",
    "ops-autoagent-api\target",
    "ops-autoagent-app\target",
    "ops-autoagent-domain\target",
    "ops-autoagent-infrastructure\target",
    "ops-autoagent-trigger\target",
    "ops-autoagent-types\target"
)

$removed = @()
$skipped = @()

foreach ($target in $targets) {
    $path = Join-Path $rootPath $target
    if (-not (Test-Path -LiteralPath $path)) {
        $skipped += $target
        continue
    }

    $resolved = (Resolve-Path -LiteralPath $path).Path
    if (-not $resolved.StartsWith($rootPath)) {
        throw "Refuse to delete outside workspace: $resolved"
    }

    Remove-Item -LiteralPath $resolved -Recurse -Force
    $removed += $target
}

[PSCustomObject]@{
    Root = $rootPath
    Removed = $removed
    Skipped = $skipped
}

