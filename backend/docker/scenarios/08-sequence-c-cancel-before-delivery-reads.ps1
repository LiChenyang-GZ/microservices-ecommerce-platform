# 场景 8 · 时序图 C：付完款马上取消，delivery 还没读到 OrderPaid
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/08-sequence-c-cancel-before-delivery-reads.ps1
# 每次注册一个新用户，可以反复跑。脚本最后一定会把 Kafka 启动回来。
#
# 怎么保证"delivery 还没读到"：先关掉 Kafka。扣款走 gRPC 不受影响，订单照样 PAID，
# 但 OrderPaid 留在 store 的 event_outbox 里发不出去。这时取消；再开 Kafka，OrderPaid 就"迟到"了。
#
# 必须成立（时序图 C）：
#   取消时 delivery 插入取消标记（CANCELLED），store 订单 CANCELLED、退款 1 笔、库存还回去；
#   迟到的 OrderPaid 不建单：delivery_db 里这单始终只有 1 行，状态 CANCELLED；
#   delivery 再发一条 Cancelled（4b 审查 #2），store 跳过：订单仍 CANCELLED，不退第二次
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
$email = "seq-c-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
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

try {
    # --- 1. 关 Kafka，下单，等 PAID ---
    docker compose -f $compose stop kafka 2>&1 | Out-Null
    $order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
    $deadline = (Get-Date).AddSeconds(60)
    do { Start-Sleep -Seconds 1; $o = Order $order.id } until ($o.order.status -eq 'PAID' -or (Get-Date) -gt $deadline)
    if ($o.order.status -ne 'PAID') { throw "前提不成立：订单停在 $($o.order.status)，没到 PAID" }
    $rows = Sql 'delivery_db' "select count(*) from delivery where order_id = '$($order.id)'"
    "1. Kafka 已关；下单 $($order.id) 已 PAID，余额 $(Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'")；配送单 $rows 张（应该 0）"

    # --- 2. 取消 ---
    $r = Invoke-RestMethod -Method Post "$store/store/orders/$($order.id)/cancel"
    "2. 点取消：$($r.message)"
    "   delivery_db：$(Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'")（取消标记，应该 CANCELLED）"
}
finally {
    # --- 3. Kafka 回来：OrderPaid 迟到 ---
    docker compose -f $compose up -d --wait kafka 2>&1 | Out-Null
    "3. Kafka 已启动，迟到的 OrderPaid 送到 delivery"
}

# --- 4. 等退款结束、迟到的事件处理完 ---
$deadline = (Get-Date).AddSeconds(90)
do { Start-Sleep -Seconds 3; $o = Order $order.id } until ($o.payment.status -in 'REFUNDED', 'REFUND_UNKNOWN' -or (Get-Date) -gt $deadline)
Start-Sleep -Seconds 10 # 让迟到的 OrderPaid、第二条 Cancelled 都走完
$o = Order $order.id
$balanceEnd = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
$refunds = Sql 'bank_db' "select count(*) from transactions where to_account = '$account' and type = 'REFUND' and status = 'SUCCESS'"
$stockEnd = Stock $product.id
$deliveryRows = @(Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'")
$timeline = @((Invoke-RestMethod "$tracking/orders/$($order.id)/timeline" -Headers $auth).eventType)
"4. 订单 $($o.order.status)，付款 $($o.payment.status)"
"   余额 $balanceStart -> $balanceEnd（应该回到原样），bank 退款 $refunds 笔（应该 1 笔）"
"   库存 $stockStart -> $stockEnd（应该回到原样）"
"   delivery_db：$($deliveryRows.Count) 行，$($deliveryRows -join ',')（应该 1 行 CANCELLED：迟到的 OrderPaid 没建单）"
"   tracking 时间线：$($timeline -join ' -> ')"

$cancelledEvents = @($timeline | Where-Object { $_ -eq 'Cancelled' }).Count
if ($o.order.status -eq 'CANCELLED' -and $o.payment.status -eq 'REFUNDED' -and $refunds -eq '1' -and
    [decimal]$balanceEnd -eq [decimal]$balanceStart -and $stockEnd -eq $stockStart -and
    $deliveryRows.Count -eq 1 -and $deliveryRows[0] -eq 'CANCELLED' -and
    'DeliveryCreated' -notin $timeline -and $cancelledEvents -eq 2) {
    'PASS: 时序图 C 走通，取消标记挡住了迟到的 OrderPaid，钱退一次、库存还回去'
} else {
    "FAIL: 看上面哪一项不对（delivery 的 Cancelled 应该 2 条，现在 $cancelledEvents 条）"
    exit 1
}
