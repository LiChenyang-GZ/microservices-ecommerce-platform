# 场景 4 · Kafka 挂着的时候下单
# 在仓库根目录、系统已经起来时运行：
#   pwsh backend/docker/scenarios/04-kafka-down.ps1                 # compose
#   pwsh backend/docker/scenarios/04-kafka-down.ps1 -Target kind    # kind
# 每次注册一个新用户，可以反复跑。脚本最后一定会把 Kafka 启动回来。
#
# 剧情：
#   1. 关掉 Kafka
#   2. 新用户买 1 台最便宜、还有库存的 iPhone。扣款走 gRPC，不经过 Kafka，所以订单照样到 PAID；
#      OrderPaid 写进 store 的 event_outbox，发不出去就留在表里等
#   3. 这时 store、delivery 仍然健康 / Ready（Kafka 不在健康检查里，学习卡 08 末尾的决定），还没有配送单
#   4. 启动 Kafka → outbox 把 OrderPaid 发出去 → delivery 建单 → 订单往下走
param([ValidateSet('compose', 'kind')][string]$Target = 'compose')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_target.ps1" $Target

$shopper = New-Shopper 'kafka'
$product = Cheapest-InStock
"0. 新用户 $($shopper.email)，余额 $(Balance $shopper.account)；买 $($product.name)"

try {
    # --- 1. 关 Kafka ---
    Stop-Infra 'kafka'
    "1. Kafka 已关"

    # --- 2. 下单，等 PAID ---
    $order = Place-Order $shopper $product
    $deadline = (Get-Date).AddSeconds(60)
    do { Start-Sleep -Seconds 1; $o = Order $order.id } until ($o.order.status -eq 'PAID' -or (Get-Date) -gt $deadline)
    "2. 下单 $($order.id)，状态 $($o.order.status)，余额 $(Balance $shopper.account)"

    # --- 3. Kafka 挂着时看一会儿 ---
    Start-Sleep -Seconds 15
    $pending = Sql 'store_db' "select count(*) from event_outbox where order_id = $($order.id) and sent_at is null"
    $delivery = Sql 'delivery_db' "select count(*) from delivery where order_id = '$($order.id)'"
    $storeHealth = Health 'store'
    $deliveryHealth = Health 'delivery'
    "3. 15 秒后：订单 $((Order $order.id).order.status)；store $storeHealth，delivery $deliveryHealth"
    "   store 还没发出去的事件 $pending 条；配送单 $delivery 张（应该 0）"
    $paidWhileDown = (Order $order.id).order.status -eq 'PAID'
    $healthyWhileDown = $storeHealth -eq 'UP' -and $deliveryHealth -eq 'UP'
    $noDeliveryWhileDown = $delivery -eq '0'
}
finally {
    # --- 4. Kafka 回来 ---
    Start-Infra 'kafka'
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
