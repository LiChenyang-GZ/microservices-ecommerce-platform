# 场景 4 · Kafka 挂着的时候下单
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/04-kafka-down.ps1
# 每次注册一个新用户，可以反复跑。脚本最后一定会把 Kafka 启动回来。
#
# 剧情：
#   1. 关掉 Kafka
#   2. 新用户买 1 台最便宜、还有库存的 iPhone。扣款走 gRPC，不经过 Kafka，所以订单照样到 PAID；
#      OrderPaid 写进 store 的 event_outbox，发不出去就留在表里等
#   3. 这时 store、delivery 仍然 healthy（Kafka 不在健康检查里，学习卡 08 末尾的决定），还没有配送单
#   4. 启动 Kafka → outbox 把 OrderPaid 发出去 → delivery 建单 → 订单往下走
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Order($orderId) {
    Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
}
function Health($service) {
    # 直接问服务自己的 /actuator/health，不等 compose 的检查周期
    $port = @{ store = 8082; delivery = 8081 }[$service]
    try { (Invoke-RestMethod "http://localhost:$port/actuator/health").status } catch { 'DOWN' }
}

# --- 准备一个新用户 ---
$email = "kafka-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
$password = 'Smoke-Test-1'
Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
    -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
    -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json)
$account = Sql 'store_db' "select bank_account_number from account where email = '$email'"
# 挑最便宜、还有库存的那台（iPhone 13 库存少，别的脚本跑几次就没了）
$name = Sql 'store_db' "select p.name from products p join warehouse_products w on w.product_id = p.id group by p.id having sum(w.quantity) > 0 order by p.price limit 1"
$product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq $name
$balanceStart = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
"0. 新用户 $email，余额 $balanceStart"

try {
    # --- 1. 关 Kafka ---
    docker compose -f $compose stop kafka 2>&1 | Out-Null
    "1. Kafka 已关"

    # --- 2. 下单，等 PAID ---
    $order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
    $deadline = (Get-Date).AddSeconds(60)
    do { Start-Sleep -Seconds 1; $o = Order $order.id } until ($o.order.status -eq 'PAID' -or (Get-Date) -gt $deadline)
    "2. 下单 $($order.id)，状态 $($o.order.status)，余额 $(Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'")"

    # --- 3. Kafka 挂着时看一会儿 ---
    Start-Sleep -Seconds 15
    $pending = Sql 'store_db' "select count(*) from event_outbox where order_id = $($order.id) and sent_at is null"
    $delivery = Sql 'delivery_db' "select count(*) from delivery where order_id = '$($order.id)'"
    "3. 15 秒后：订单 $((Order $order.id).order.status)；store $(Health store)，delivery $(Health delivery)"
    "   store 还没发出去的事件 $pending 条；配送单 $delivery 张（应该 0）"
    $paidWhileDown = (Order $order.id).order.status -eq 'PAID'
    $healthyWhileDown = (Health store) -eq 'UP' -and (Health delivery) -eq 'UP'
    $noDeliveryWhileDown = $delivery -eq '0'
}
finally {
    # --- 4. Kafka 回来 ---
    docker compose -f $compose up -d --wait kafka 2>&1 | Out-Null
    "4. Kafka 已启动"
}

$deadline = (Get-Date).AddMinutes(2)
do { Start-Sleep -Seconds 3; $o = Order $order.id } until ($o.order.status -in 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED' -or (Get-Date) -gt $deadline)
$deliveryRow = Sql 'delivery_db' "select id, delivery_status from delivery where order_id = '$($order.id)'"
"5. 订单 $($o.order.status)；配送单 $deliveryRow"

if ($paidWhileDown -and $healthyWhileDown -and $noDeliveryWhileDown -and $deliveryRow) {
    'PASS: Kafka 挂着时照常收钱、服务都健康；Kafka 回来后配送单补上了'
} else {
    "FAIL: PAID=$paidWhileDown 健康=$healthyWhileDown 当时没配送单=$noDeliveryWhileDown 之后有配送单=$([bool]$deliveryRow)"
    exit 1
}
