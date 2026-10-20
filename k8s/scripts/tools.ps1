# Which kubectl the scripts use. Dot-source it: . "$PSScriptRoot\tools.ps1"
# Docker Desktop's kubectl (1.32) is too old for the kind node (1.34): kubectl
# supports only one minor version of difference with the cluster. So prefer, in order:
#   1. $env:KUBECTL, if set
#   2. kubectl.exe in the same folder as kind.exe (where this machine keeps 1.34)
#   3. whatever kubectl is on PATH
$script:kubectl = $env:KUBECTL
if (-not $script:kubectl) {
    $kind = Get-Command kind -ErrorAction SilentlyContinue
    if ($kind) {
        $candidate = Join-Path (Split-Path $kind.Source) 'kubectl.exe'
        if (Test-Path $candidate) { $script:kubectl = $candidate }
    }
}
if (-not $script:kubectl) { $script:kubectl = 'kubectl' }
