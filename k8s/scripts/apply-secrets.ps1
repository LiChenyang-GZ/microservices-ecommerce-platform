# Creates the Secrets in namespace shop from backend/.env (the same file compose reads).
#   ./k8s/scripts/apply-secrets.ps1
# Nothing is written to disk: each Secret goes straight from memory to the cluster,
# so there is no Secret file that could be committed by mistake.
# One Secret per reader, and each Secret holds only what that reader needs: a pod
# never sees another service's password (same rule as compose.yml).
#   ./k8s/scripts/apply-secrets.ps1 -EnvFile E:\study\5348\backend\.env
# (-EnvFile is for a git worktree, which has no backend/.env of its own.)
param([string]$EnvFile = (Join-Path $PSScriptRoot '..\..\backend\.env'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools.ps1"

$envFile = $EnvFile
if (-not (Test-Path $envFile)) { throw "backend/.env not found. Copy backend/.env.example and fill it in." }

$vars = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=(.*)$') { $vars[$Matches[1]] = $Matches[2].Trim() }
}
function Need([string]$name) {
    if (-not $vars[$name]) { throw "$name is empty in backend/.env" }
    $vars[$name]
}

# Same check as compose's DATABASE_URL: the password sits inside a URL.
if ((Need 'TRACKING_DB_PASSWORD') -match '[@:/?#]') { throw 'TRACKING_DB_PASSWORD must not contain @ : / ? #' }

$secrets = [ordered]@{
    # Only the superuser password. The service passwords go to db-init, not here (09 B#6).
    'postgres'  = @{ POSTGRES_PASSWORD = Need 'POSTGRES_PASSWORD' }
    'db-init'   = @{
        STORE_DB_PASSWORD    = Need 'STORE_DB_PASSWORD'
        BANK_DB_PASSWORD     = Need 'BANK_DB_PASSWORD'
        DELIVERY_DB_PASSWORD = Need 'DELIVERY_DB_PASSWORD'
        EMAIL_DB_PASSWORD    = Need 'EMAIL_DB_PASSWORD'
        TRACKING_DB_PASSWORD = Need 'TRACKING_DB_PASSWORD'
    }
    'rabbitmq'  = @{ RABBITMQ_DEFAULT_USER = Need 'RABBITMQ_USERNAME'; RABBITMQ_DEFAULT_PASS = Need 'RABBITMQ_PASSWORD' }
    'store'     = @{
        STORE_DB_PASSWORD = Need 'STORE_DB_PASSWORD'
        RABBITMQ_USERNAME = Need 'RABBITMQ_USERNAME'
        RABBITMQ_PASSWORD = Need 'RABBITMQ_PASSWORD'
        JWT_SECRET        = Need 'JWT_SECRET'
    }
    'bank'      = @{ BANK_DB_PASSWORD = Need 'BANK_DB_PASSWORD' }
    'delivery'  = @{
        DELIVERY_DB_PASSWORD = Need 'DELIVERY_DB_PASSWORD'
        RABBITMQ_USERNAME    = Need 'RABBITMQ_USERNAME'
        RABBITMQ_PASSWORD    = Need 'RABBITMQ_PASSWORD'
    }
    'email'     = @{
        EMAIL_DB_PASSWORD = Need 'EMAIL_DB_PASSWORD'
        RABBITMQ_USERNAME = Need 'RABBITMQ_USERNAME'
        RABBITMQ_PASSWORD = Need 'RABBITMQ_PASSWORD'
        # Optional, as in compose: empty means email cannot send, but still starts.
        MAIL_USERNAME     = "$($vars['MAIL_USERNAME'])"
        MAIL_PASSWORD     = "$($vars['MAIL_PASSWORD'])"
    }
    # The whole URL is secret, because the password is inside it.
    'tracking'  = @{
        DATABASE_URL = "postgres://tracking_user:$(Need 'TRACKING_DB_PASSWORD')@postgres:5432/tracking_db?sslmode=disable"
    }
}

foreach ($name in $secrets.Keys) {
    $kargs = @('create', 'secret', 'generic', $name, '--namespace', 'shop', '--dry-run=client', '-o', 'yaml')
    foreach ($kv in $secrets[$name].GetEnumerator()) { $kargs += "--from-literal=$($kv.Key)=$($kv.Value)" }
    # apply instead of create, so running the script again updates the values.
    & $kubectl @kargs | & $kubectl apply -f - | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "failed to apply secret $name" }
}
