# 场景 5 · 付完款、建配送单的时候 RabbitMQ 挂了（第 2 波 C 审查的发现 7，集成发现 I1）
# 在仓库根目录、系统已经起来时运行：
#   pwsh backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1                 # compose
#   pwsh backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1 -Target kind    # kind
# 每次注册一个新用户，可以反复跑。脚本最后一定会把 RabbitMQ 启动回来。
#
# 以前：store 建配送单走 RabbitMQ，失败就取消订单并退款——用户付了钱，因为中间件抖一下就被退单。
# 现在：OrderPaid 走 Kafka，delivery 自己建单。RabbitMQ 只剩两件事：发邮件，和 delivery 内部"开始模拟配送"的任务。
# 建单时这个任务发不出去，以前配送单会永远停在 CREATED（I1）；现在 delivery 每 10 秒扫一次，
# 60 秒没动的单把任务重新发出去（StuckDeliveryJobSweeper），所以 RabbitMQ 回来后最多约 70 秒就会动。
#
# 剧情：
#   1. 关掉 RabbitMQ
#   2. 新用户买 1 台最便宜、还有库存的 iPhone → PAID → delivery 从 Kafka 读到 OrderPaid，建配送单
#   3. 看：订单没被取消、没退款
#   4. 启动 RabbitMQ，看配送单会不会往下走（PICKED_UP）
param([ValidateSet('compose', 'kind')][string]$Target = 'compose')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_target.ps1" $Target

$shopper = New-Shopper 'rabbit'
$product = Cheapest-InStock
"0. 新用户 $($shopper.email)，余额 $(Balance $shopper.account)；买 $($product.name)"

try {
    # --- 1. 关 RabbitMQ ---
    Stop-Infra 'rabbitmq'
    "1. RabbitMQ 已关"

    # --- 2. 下单，等配送单建出来 ---
    $order = Place-Order $shopper $product
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        $row = Sql 'delivery_db' "select id, delivery_status from delivery where order_id = '$($order.id)'"
    } until ($row -or (Get-Date) -gt $deadline)
    Start-Sleep -Seconds 15
    $o = Order $order.id
    "2. 下单 $($order.id)：订单 $($o.order.status)，付款 $($o.payment.status)"
    "   配送单 $(Sql 'delivery_db' "select id, delivery_status from delivery where order_id = '$($order.id)'")"
    $notCancelled = $o.order.status -notlike 'CANCELLED*' -and $o.payment.status -notlike 'REFUND*'
}
finally {
    # --- 3. RabbitMQ 回来 ---
    Start-Infra 'rabbitmq'
    "3. RabbitMQ 已启动，等配送单往下走（最多 2 分钟）"
}

$deadline = (Get-Date).AddMinutes(2)
do {
    Start-Sleep -Seconds 5
    $status = Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'"
} until ($status -and $status -ne 'CREATED' -or (Get-Date) -gt $deadline)
$o = Order $order.id
"4. 配送单 $status；订单 $($o.order.status)，付款 $($o.payment.status)"

if ($notCancelled -and $status -and $status -ne 'CREATED') {
    'PASS: RabbitMQ 挂着时订单没被取消，回来后配送照常往下走'
} elseif ($notCancelled) {
    'FAIL: 订单没被取消（发现 7 修好了），但配送单停在 CREATED，RabbitMQ 回来也没动'
    exit 1
} else {
    'FAIL: 订单被取消或退款了（发现 7 还在）'
    exit 1
}
