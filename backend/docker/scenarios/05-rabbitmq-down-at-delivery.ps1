# 场景 5 · 付完款、建配送单的时候 RabbitMQ 挂了（第 2 波 C 审查的发现 7）
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1
# 每次注册一个新用户，可以反复跑。脚本最后一定会把 RabbitMQ 启动回来。
#
# 以前：store 建配送单走 RabbitMQ，失败就取消订单并退款——用户付了钱，因为中间件抖一下就被退单。
# 现在：OrderPaid 走 Kafka，delivery 自己建单。RabbitMQ 只剩两件事：发邮件，和 delivery 内部"开始模拟配送"的任务。
#
# 剧情：
#   1. 关掉 RabbitMQ
#   2. 新用户买 1 台最便宜、还有库存的 iPhone → PAID → delivery 从 Kafka 读到 OrderPaid，建配送单
#   3. 看：订单没被取消、没退款
#   4. 启动 RabbitMQ，看配送单会不会往下走（PICKED_UP）
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Order($orderId) {
    Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
}

# --- 准备一个新用户 ---
$email = "rabbit-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
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
"0. 新用户 $email，余额 $(Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'")"

try {
    # --- 1. 关 RabbitMQ ---
    docker compose -f $compose stop rabbitmq 2>&1 | Out-Null
    "1. RabbitMQ 已关"

    # --- 2. 下单，等配送单建出来 ---
    $order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
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
    docker compose -f $compose up -d --wait rabbitmq 2>&1 | Out-Null
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
