# 场景 2 · 付完款、还没取件时取消订单
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/02-cancel-after-paid.ps1                  # 配送单还没建就取消
#   pwsh backend/docker/scenarios/02-cancel-after-paid.ps1 -AfterDelivery   # 配送单建好、还没取件时取消
# 每次注册一个新用户，可以反复跑。
#
# 剧情：
#   1. 新用户余额 10000，买 1 台 iPhone 13（6999）
#   2. 等到订单 PAID（钱已经扣了）。建配送单是 OutboxProcessor 下一轮（约 5 秒后）才做的事。
#      默认：PAID 后马上取消 → OutboxProcessor 看到已取消，不建配送单。
#      -AfterDelivery：等配送单建好再取消。delivery 建单后约 10 秒才"取件"，取件后就不能取消了。
#   3. 看结果：订单 CANCELLED，钱退回来且只退一次，库存还回去；配送单要么没建，要么被取消
param([switch]$AfterDelivery)
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Order($orderId) {
    Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
}
function Balance($account) {
    Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
}
function Stock($productId) {
    Sql 'store_db' "select sum(quantity) from warehouse_products where product_id = $productId"
}

# --- 准备一个新用户 ---
$email = "cancel-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
$password = 'Smoke-Test-1'
Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
    -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
    -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json)
$account = Sql 'store_db' "select bank_account_number from account where email = '$email'"
$product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq 'iPhone 13'
$balanceStart = Balance $account
$stockStart = Stock $product.id
"0. 新用户 $email，余额 $balanceStart；iPhone 13 库存 $stockStart"

# --- 1. 下单 ---
$order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
    -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
"1. 下单 $($order.id)，$($order.totalAmount)；库存 $(Stock $product.id)"

# --- 2. 等到 PAID ---
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 1; $o = Order $order.id } until ($o.order.status -eq 'PAID' -or (Get-Date) -gt $deadline)
if ($o.order.status -ne 'PAID') { throw "前提不成立：订单停在 $($o.order.status)，没到 PAID" }
"2. 订单 PAID，余额 $(Balance $account)"
if ($AfterDelivery) {
    $deadline = (Get-Date).AddSeconds(30)
    do { Start-Sleep -Milliseconds 500; $o = Order $order.id } until ($o.order.deliveryId -or (Get-Date) -gt $deadline)
    if (-not $o.order.deliveryId) { throw "前提不成立：30 秒内没建配送单" }
    "   配送单已建（delivery id $($o.order.deliveryId)），状态 $(Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'")"
}

# --- 3. 取消 ---
try {
    $r = Invoke-RestMethod -Method Post "$store/store/orders/$($order.id)/cancel"
    "3. 点取消：$($r.message)"
} catch {
    "3. 点取消被拒绝：$($_.ErrorDetails.Message)"
    'FAIL: 取消没成功（如果说已经取件，是没赶上 10 秒窗口，再跑一次）'
    exit 1
}

# --- 4. 等退款（OutboxProcessor 每 5 秒一轮）---
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 3
    $o = Order $order.id
} until ($o.payment.status -in 'REFUNDED', 'REFUND_UNKNOWN' -or (Get-Date) -gt $deadline)

$balanceEnd = Balance $account
$stockEnd = Stock $product.id
$refunds = Sql 'bank_db' "select count(*) from transactions where to_account = '$account' and type = 'REFUND' and status = 'SUCCESS'"
$delivery = Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'"
"4. 订单 $($o.order.status)，付款 $($o.payment.status)"
"   余额 $balanceStart -> $balanceEnd（应该回到原样）"
"   bank 退款 $refunds 笔（应该 1 笔）"
if ($delivery) { "   配送单 $delivery" } else { '   配送单：没建（取消在建单之前，OutboxProcessor 看到已取消就跳过了）' }
"   库存 $stockStart -> $stockEnd（应该回到原样）"

if ($o.order.status -eq 'CANCELLED' -and $refunds -eq '1' -and
    [decimal]$balanceEnd -eq [decimal]$balanceStart -and $stockEnd -eq $stockStart) {
    'PASS: 取消后钱退了一次、库存还回去了'
} else {
    'FAIL: 看上面哪个数字不对'
}
