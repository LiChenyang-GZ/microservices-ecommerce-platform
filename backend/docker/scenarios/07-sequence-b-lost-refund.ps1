# 场景 7 · 时序图 B：丢件 → LOST → 退款任务 → REFUNDED
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/07-sequence-b-lost-refund.ps1
# 每次注册一个新用户，可以反复跑。
#
# 怎么让它一定丢件：临时把 delivery 的丢件率设成 100%（DELIVERY_SIMULATION_LOSSRATE=1.0，
# 对应 delivery.simulation.loss-rate）重启 delivery。脚本最后一定会把 delivery 换回正常配置。
#
# 必须成立：
#   订单 REFUNDED，付款 REFUNDED；钱扣一次、退一次，余额回到原样；
#   库存**不**还（货已经发出去了，时序图 B 的说明）；
#   tracking 时间线里有 Lost 和 OrderRefunded，各一条
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'
$tracking = 'http://localhost:8085/api/tracking'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Order($orderId) {
    Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
}
function Stock($productId) {
    Sql 'store_db' "select sum(quantity) from warehouse_products where product_id = $productId"
}

# --- 准备一个新用户 ---
$email = "seq-b-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
$password = 'Smoke-Test-1'
Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
    -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
    -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.token)" }
$account = Sql 'store_db' "select bank_account_number from account where email = '$email'"
# 挑最便宜、还有库存的那台（iPhone 13 库存少，别的脚本跑几次就没了）
$name = Sql 'store_db' "select p.name from products p join warehouse_products w on w.product_id = p.id group by p.id having sum(w.quantity) > 0 order by p.price limit 1"
$product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq $name
$balanceStart = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
$stockStart = Stock $product.id
"0. 新用户 $email，余额 $balanceStart；$($product.name) 库存 $stockStart"

$lossy = Join-Path ([IO.Path]::GetTempPath()) 'shop-delivery-always-lose.yml'
"services:`n  delivery:`n    environment:`n      DELIVERY_SIMULATION_LOSSRATE: '1.0'" | Set-Content $lossy
try {
    # --- 1. delivery 换成"一定丢件" ---
    docker compose -f $compose -f $lossy up -d --wait delivery 2>&1 | Out-Null
    "1. delivery 已用丢件率 100% 重启"

    # --- 2. 下单，等到退款结束 ---
    $order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
    "2. 下单 $($order.id)"
    $seen = @()
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $o = Order $order.id
        $now = "$($o.order.status)/$($o.payment.status)"
        if ($seen[-1] -ne $now) { $seen += $now; "   订单/付款 -> $now" }
    } until ($o.payment.status -in 'REFUNDED', 'REFUND_UNKNOWN' -or $o.order.status -in 'DELIVERED', 'CANCELLED' -or (Get-Date) -gt $deadline)
}
finally {
    # --- 3. delivery 换回正常配置 ---
    docker compose -f $compose up -d --wait delivery 2>&1 | Out-Null
    Remove-Item $lossy -ErrorAction SilentlyContinue
    "3. delivery 已换回正常配置"
}

# --- 4. 看结果 ---
$balanceEnd = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
$refunds = Sql 'bank_db' "select count(*) from transactions where to_account = '$account' and type = 'REFUND' and status = 'SUCCESS'"
$stockEnd = Stock $product.id
$deliveryRow = Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'"
$timeline = @((Invoke-RestMethod "$tracking/orders/$($order.id)/timeline" -Headers $auth).eventType)
"4. 订单 $($o.order.status)，付款 $($o.payment.status)；配送单 $deliveryRow"
"   余额 $balanceStart -> $balanceEnd（应该回到原样），bank 退款 $refunds 笔（应该 1 笔）"
"   库存 $stockStart -> $stockEnd（应该少 1：丢件不还库存）"
"   tracking 时间线：$($timeline -join ' -> ')"

if ($o.order.status -eq 'REFUNDED' -and $o.payment.status -eq 'REFUNDED' -and $refunds -eq '1' -and
    [decimal]$balanceEnd -eq [decimal]$balanceStart -and [int]$stockEnd -eq [int]$stockStart - 1 -and
    $deliveryRow -eq 'LOST' -and @($timeline | Where-Object { $_ -eq 'Lost' }).Count -eq 1 -and
    @($timeline | Where-Object { $_ -eq 'OrderRefunded' }).Count -eq 1) {
    'PASS: 时序图 B 走通，丢件后钱退了一次、库存没还'
} else {
    'FAIL: 看上面哪一项不对'
    exit 1
}
