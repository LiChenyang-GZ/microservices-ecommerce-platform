# 场景 6 · 时序图 A：下单 → 扣款（gRPC）→ OrderPaid → 建配送单 → 一路送到 Received
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/06-sequence-a-order-to-received.ps1
# 每次注册一个新用户，可以反复跑。配送每一步约 10 秒，整单约 1 分钟。
#
# 必须成立：
#   订单 DELIVERED；余额只扣一次；delivery_db 里这单只有 1 行，状态 RECEIVED；
#   tracking 时间线依次是 OrderPlaced、OrderPaid、DeliveryCreated、PickedUp、Delivering、Received，各一条
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

# --- 准备一个新用户 ---
$email = "seq-a-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
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
"0. 新用户 $email，余额 $balanceStart；买 $($product.name)（$($product.price)）"

# --- 1. 下单，跟着状态走到 DELIVERED ---
$order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
    -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
"1. 下单 $($order.id)"
$seen = @()
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    $status = (Order $order.id).order.status
    if ($seen[-1] -ne $status) { $seen += $status; "   订单 -> $status" }
} until ($status -in 'DELIVERED', 'LOST', 'REFUNDED', 'CANCELLED', 'CANCELLED_SYSTEM' -or (Get-Date) -gt $deadline)

# --- 2. 各服务自己的库 + tracking ---
Start-Sleep -Seconds 3 # Received 事件到 tracking 可能比到 store 晚一点
$balanceEnd = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
$transfers = Sql 'bank_db' "select count(*) from transactions where from_account = '$account' and status = 'SUCCESS'"
$deliveryRows = @(Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'")
$timeline = @((Invoke-RestMethod "$tracking/orders/$($order.id)/timeline" -Headers $auth).eventType)
"2. bank：余额 $balanceStart -> $balanceEnd，成功扣款 $transfers 笔（应该 1 笔）"
"   delivery_db：$($deliveryRows.Count) 行，$($deliveryRows -join ',')"
"   tracking 时间线：$($timeline -join ' -> ')"

$expected = 'OrderPlaced', 'OrderPaid', 'DeliveryCreated', 'PickedUp', 'Delivering', 'Received'
if ($status -eq 'DELIVERED' -and $transfers -eq '1' -and
    [decimal]$balanceStart - [decimal]$balanceEnd -eq [decimal]$product.price -and
    $deliveryRows.Count -eq 1 -and $deliveryRows[0] -eq 'RECEIVED' -and
    ($timeline -join ',') -eq ($expected -join ',')) {
    'PASS: 时序图 A 走通，钱扣一次、一张配送单、时间线完整'
} else {
    "FAIL: 看上面哪一项不对（时间线应该是 $($expected -join ' -> ')）"
    exit 1
}
