# Checklist K10/K11: does each pod get the same variable NAMES as compose gives
# that service, and no other service's password? Prints names only, never values.
#   ./k8s/scripts/check-config-keys.ps1 [-ComposeFile backend/compose.yml]
param([string]$ComposeFile = (Join-Path $PSScriptRoot '..\..\backend\compose.yml'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools.ps1"

# Variables that exist only in Kubernetes, on purpose (probes, JVM memory).
$k8sOnly = 'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED', 'MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE', 'JAVA_TOOL_OPTIONS'

# Read the environment: block of each service straight from compose.yml (names only).
$compose = @{}; $svc = $null; $inEnv = $false
foreach ($line in Get-Content $ComposeFile) {
    if ($line -match '^  ([a-z][a-z0-9-]*):\s*$') { $svc = $Matches[1]; $inEnv = $false; $compose[$svc] = @(); continue }
    if ($line -match '^    environment:\s*$') { $inEnv = $true; continue }
    if ($inEnv -and $line -match '^      ([A-Z_][A-Z0-9_]*):') { $compose[$svc] += $Matches[1]; continue }
    if ($line -match '^    \S') { $inEnv = $false }
}

$fail = $false
foreach ($name in 'store', 'bank', 'delivery', 'email', 'tracking') {
    $cm = (& $kubectl -n shop get configmap $name -o json | ConvertFrom-Json).data.PSObject.Properties.Name
    $sec = (& $kubectl -n shop get secret $name -o json | ConvertFrom-Json).data.PSObject.Properties.Name
    $k8s = @($cm) + @($sec) | Where-Object { $_ -notin $k8sOnly } | Sort-Object -Unique
    $want = $compose[$name] | Sort-Object -Unique
    $missing = $want | Where-Object { $_ -notin $k8s }
    $extra = $k8s | Where-Object { $_ -notin $want }
    # Any *_PASSWORD that is not this service's own (RabbitMQ / mail are shared on purpose, as in compose).
    $foreign = $k8s | Where-Object { $_ -match '_DB_PASSWORD$' -and $_ -ne "$($name.ToUpper())_DB_PASSWORD" }
    $ok = -not ($missing -or $extra -or $foreign)
    if (-not $ok) { $fail = $true }
    "{0,-9} {1}  keys={2}  missing=[{3}] extra=[{4}] other-db-passwords=[{5}]" -f $name, ($(if ($ok) { 'OK  ' } else { 'FAIL' })), $k8s.Count, ($missing -join ','), ($extra -join ','), ($foreign -join ',')
}
if ($fail) { exit 1 }
