# The whole shop on a local kind cluster, from nothing. Safe to run again.
#   ./k8s/scripts/up.ps1                        # reads backend/.env
#   ./k8s/scripts/up.ps1 -EnvFile <path>\.env   # e.g. from a git worktree
# Needs: Docker Desktop, kind, kubectl >= 1.33, and the shop-* images built by compose:
#   docker compose -f backend/compose.yml build
param([string]$EnvFile = (Join-Path $PSScriptRoot '..\..\backend\.env'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools.ps1"
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envoyGateway = 'https://github.com/envoyproxy/gateway/releases/download/v1.9.2/install.yaml'
$images = 'shop-store', 'shop-bank', 'shop-delivery', 'shop-email', 'shop-tracking', 'shop-frontend'

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Run { & $args[0] @($args | Select-Object -Skip 1); if ($LASTEXITCODE -ne 0) { throw "failed: $args" } }

Step '1/6 kind cluster "shop"'
if ((kind get clusters) -notcontains 'shop') { Run kind create cluster --config "$root\kind-config.yaml" }
else { Write-Host 'already exists' }

Step '2/6 Envoy Gateway (the controller behind the single entry)'
Run $kubectl apply --server-side -f $envoyGateway
Run $kubectl -n envoy-gateway-system wait --for=condition=Available deployment/envoy-gateway --timeout=300s

Step '3/6 load the locally built images into the kind node'
# Public images (postgres, rabbitmq, kafka) are pulled by the node itself:
# `kind load` fails on Docker Desktop's multi-platform copies of them.
foreach ($image in $images) {
    docker image inspect "${image}:latest" *> $null
    if ($LASTEXITCODE -ne 0) { throw "${image}:latest not found. Build it first: docker compose -f backend/compose.yml build" }
}
$refs = $images | ForEach-Object { "${_}:latest" }
Run kind load docker-image --name shop @refs

Step '4/6 namespace, Secrets, PostgreSQL, RabbitMQ, Kafka'
Run $kubectl apply -f "$root\base\namespace.yaml"
& "$PSScriptRoot\apply-secrets.ps1" -EnvFile $EnvFile
Run $kubectl apply -k "$root\base"
Run $kubectl -n shop wait --for=condition=Ready pod/postgres-0 pod/rabbitmq-0 pod/kafka-0 --timeout=480s
Run $kubectl -n shop wait --for=condition=Complete job/db-init job/kafka-init --timeout=300s

Step '5/6 the six services and the gateway'
Run $kubectl apply -k "$root\apps"
Run $kubectl apply -f "$root\gateway\gateway.yaml" -f "$root\gateway\routes.yaml"
Run $kubectl -n shop wait --for=condition=Available deployment --all --timeout=600s

Step '6/6 done'
Run $kubectl -n shop get pods
Write-Host "`nOpen http://localhost  -  or run ./k8s/scripts/smoke-order.ps1" -ForegroundColor Green
