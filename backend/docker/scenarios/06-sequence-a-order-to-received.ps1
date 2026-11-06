# 场景 6 · 时序图 A：下单 → 扣款（gRPC）→ OrderPaid → 建配送单 → 一路送到 Received
# 在仓库根目录、系统已经起来时运行：
#   pwsh backend/docker/scenarios/06-sequence-a-order-to-received.ps1                 # compose
#   pwsh backend/docker/scenarios/06-sequence-a-order-to-received.ps1 -Target kind    # kind
# 每次注册一个新用户，可以反复跑。配送每一步约 10 秒，整单约 1 分钟。
#
# 必须成立：
#   订单 DELIVERED；余额只扣一次；delivery_db 里这单只有 1 行，状态 RECEIVED；
#   tracking 时间线依次是 OrderPlaced、OrderPaid、DeliveryCreated、PickedUp、Delivering、Received，各一条
param([ValidateSet('compose', 'kind')][string]$Target = 'compose')
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\_target.ps1" $Target

$shopper = New-Shopper 'seq-a'
$product = Cheapest-InStock
$balanceStart = Balance $shopper.account
"0. 新用户 $($shopper.email)，余额 $balanceStart；买 $($product.name)（$($product.price)）"

# --- 1. 下单，跟着状态走到 DELIVERED ---
$order = Place-Order $shopper $product
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
$balanceEnd = Balance $shopper.account
$transfers = Sql 'bank_db' "select count(*) from transactions where from_account = '$($shopper.account)' and status = 'SUCCESS'"
$deliveryRows = @(Sql 'delivery_db' "select delivery_status from delivery where order_id = '$($order.id)'")
$timeline = Timeline $order.id $shopper.token
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
