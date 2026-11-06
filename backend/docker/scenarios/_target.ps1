# 场景脚本的公共部分：同一套剧情，跑在 compose 或 kind 上。在脚本开头 dot-source：
#   param([ValidateSet('compose', 'kind')][string]$Target = 'compose')
#   . "$PSScriptRoot\_target.ps1" $Target
#
#                compose（backend/compose.yml）        kind（k8s/，统一入口 http://localhost）
#   store API    http://localhost:8082/api             http://localhost/api/store（入口把 /api/store/x 转成 store 的 /api/x）
#   tracking     http://localhost:8085                 入口不放行（前端不调），临时 kubectl port-forward
#   查库         docker compose exec postgres          kubectl exec postgres-0
#   关/开 Kafka、RabbitMQ   docker compose stop / up --wait    StatefulSet 缩到 0 / 扩回 1（数据在 PVC 里，不会丢）
#   丢件率       临时 compose 覆盖文件                  kubectl set env deploy/delivery
#   服务健康     服务自己的 /actuator/health            Deployment 的 Ready 副本数（就是 readiness 探针的结果）
param([ValidateSet('compose', 'kind')][string]$Target = 'compose')

$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$compose = Join-Path $repo 'backend/compose.yml'
if ($Target -eq 'kind') {
    . (Join-Path $repo 'k8s/scripts/tools.ps1') # 选对版本的 kubectl
    $store = 'http://localhost/api/store'
} else {
    $store = 'http://localhost:8082/api'
}
"[目标：$Target]"

function Sql($db, $query) {
    if ($Target -eq 'kind') { & $kubectl -n shop exec postgres-0 -- psql -U postgres -d $db -tAc $query }
    else { docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query }
}

# 关掉一个中间件（'kafka' 或 'rabbitmq'），等它真的停了
function Stop-Infra($name) {
    if ($Target -eq 'kind') {
        & $kubectl -n shop scale statefulset/$name --replicas=0 | Out-Null
        & $kubectl -n shop wait --for=delete pod/$name-0 --timeout=120s 2>&1 | Out-Null
    } else {
        docker compose -f $compose stop $name 2>&1 | Out-Null
    }
}

# 把它开回来，等它 Ready
function Start-Infra($name) {
    if ($Target -eq 'kind') {
        & $kubectl -n shop scale statefulset/$name --replicas=1 | Out-Null
        $deadline = (Get-Date).AddMinutes(4)
        do { Start-Sleep -Seconds 3; & $kubectl -n shop get pod/$name-0 *> $null } until ($LASTEXITCODE -eq 0 -or (Get-Date) -gt $deadline)
        & $kubectl -n shop wait --for=condition=Ready pod/$name-0 --timeout=240s | Out-Null
    } else {
        docker compose -f $compose up -d --wait $name 2>&1 | Out-Null
    }
}

# 'UP' 或 'DOWN'。compose 问服务自己；kind 看 Deployment 有没有 Ready 的副本（被摘掉就收不到流量）
function Health($service) {
    if ($Target -eq 'kind') {
        $ready = & $kubectl -n shop get deploy/$service -o jsonpath='{.status.readyReplicas}'
        if ([int]$ready -ge 1) { 'UP' } else { 'DOWN' }
    } else {
        $port = @{ store = 8082; delivery = 8081 }[$service]
        try { (Invoke-RestMethod "http://localhost:$port/actuator/health").status } catch { 'DOWN' }
    }
}

# 用给定的丢件率（比如 '1.0'）重启 delivery；$null = 换回正常配置
function Set-DeliveryLossRate($rate) {
    if ($Target -eq 'kind') {
        $arg = if ($rate) { "DELIVERY_SIMULATION_LOSSRATE=$rate" } else { 'DELIVERY_SIMULATION_LOSSRATE-' }
        & $kubectl -n shop set env deploy/delivery -c delivery $arg | Out-Null
        & $kubectl -n shop rollout status deploy/delivery --timeout=300s | Out-Null
    } elseif ($rate) {
        $lossy = Join-Path ([IO.Path]::GetTempPath()) 'shop-delivery-loss-rate.yml'
        "services:`n  delivery:`n    environment:`n      DELIVERY_SIMULATION_LOSSRATE: '$rate'" | Set-Content $lossy
        docker compose -f $compose -f $lossy up -d --wait delivery 2>&1 | Out-Null
        Remove-Item $lossy
    } else {
        docker compose -f $compose up -d --wait delivery 2>&1 | Out-Null
    }
}

# tracking 时间线里的事件类型，按发生先后。带上登录时的 token，tracking 拿它回 store 校验
function Timeline($orderId, $token) {
    $headers = @{ Authorization = "Bearer $token" }
    if ($Target -ne 'kind') {
        return @((Invoke-RestMethod "http://localhost:8085/api/tracking/orders/$orderId/timeline" -Headers $headers).eventType)
    }
    $forward = Start-Process $kubectl -ArgumentList '-n', 'shop', 'port-forward', 'svc/tracking', '18085:8085' `
        -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds(20)
        while ($true) {
            try {
                return @((Invoke-RestMethod "http://localhost:18085/api/tracking/orders/$orderId/timeline" -Headers $headers).eventType)
            } catch {
                if ((Get-Date) -gt $deadline) { throw }
                Start-Sleep -Milliseconds 500 # port-forward 还没连上
            }
        }
    } finally {
        Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue
    }
}

# 注册 + 激活 + 登录一个新用户（激活只要邮箱，不用真的收信）。返回登录结果，外加 email 和 bank 账号
function New-Shopper($prefix) {
    $email = "$prefix-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
    $password = 'Smoke-Test-1'
    Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
        -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
    Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
        -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
    $login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
        -Body (@{ email = $email; password = $password } | ConvertTo-Json)
    $login | Add-Member -Force email $email
    $login | Add-Member -Force account (Sql 'store_db' "select bank_account_number from account where email = '$email'")
    $login
}

# 最便宜、还有库存的商品（iPhone 13 库存少，脚本跑几次就没了）
function Cheapest-InStock {
    $name = Sql 'store_db' "select p.name from products p join warehouse_products w on w.product_id = p.id group by p.id having sum(w.quantity) > 0 order by p.price limit 1"
    (Invoke-RestMethod "$store/products").products | Where-Object name -eq $name
}

function Balance($account) { Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'" }
function Stock($productId) { Sql 'store_db' "select sum(quantity) from warehouse_products where product_id = $productId" }
function Order($orderId) { Invoke-RestMethod "$store/store/orders/$orderId/with-payment" }
function Place-Order($shopper, $product) {
    (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $shopper.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
}
