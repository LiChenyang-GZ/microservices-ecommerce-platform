# 场景 1 · 两个标签页同时点 Pay Now
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/01-paynow-twice.ps1
# 每次注册一个新用户，可以反复跑。
#
# 剧情：
#   1. 新用户余额 10000，一次买 2 台 iPhone 13（13998）→ bank 拒绝（余额不足）→ 付款 FAILED
#   2. 用户去 Add Money 充 5000
#   3. 两个标签页几乎同时点 Pay Now
#   4. 看结果：钱应该只扣一次
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'
$bank = 'http://localhost:8084/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Status($orderId) {
    $r = Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
    "order $($r.order.status), payment $($r.payment.status) (attempt $($r.payment.attempt))"
}
function Balance($account) {
    Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
}

# --- 准备一个新用户 ---
$email = "paynow-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
$password = 'Smoke-Test-1'
Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
    -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
    -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json)
$auth = @{ Authorization = "Bearer $($login.token)" }
$account = Sql 'store_db' "select bank_account_number from account where email = '$email'"
"0. 新用户 $email，银行账户 $account，余额 $(Balance $account)"

# --- 1. 买 2 台，钱不够 ---
$product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq 'iPhone 13'
$order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
    -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 2 }) } | ConvertTo-Json -Depth 3)).order
"1. 下单 $($order.id)：2 台 iPhone 13，共 $($order.totalAmount)。等 bank 回答（OutboxProcessor 每 5 秒一轮）..."
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 3; $s = Status $order.id } until ($s -match 'payment FAILED' -or (Get-Date) -gt $deadline)
"   -> $s"
if ($s -notmatch 'payment FAILED') { throw "前提不成立：付款没有变成 FAILED" }

# --- 2. 充值 5000（和 Add Money 页面发的请求一样）---
Invoke-RestMethod -Method Post "$bank/bank/transfer" -ContentType 'application/json' `
    -Body (@{ fromAccount = 'SYSTEM'; toAccount = $account; amount = 5000; transactionRef = "DEPOSIT-$(Get-Random)" } | ConvertTo-Json) | Out-Null
$before = Balance $account
"2. 充值 5000，余额 $before"

# --- 3. 两个"标签页"同时点 Pay Now ---
"3. 两个标签页同时点 Pay Now ..."
$tabs = 1..2 | ForEach-Object -Parallel {
    try {
        $r = Invoke-RestMethod -Method Post "$using:store/store/orders/$($using:order.id)/retry-payment" -Headers $using:auth
        "   标签页 ${_}: $($r.message)"
    } catch {
        "   标签页 ${_}: 被拒绝 ($($_.ErrorDetails.Message))"
    }
} -ThrottleLimit 2
$tabs

# --- 4. 等付款结果 ---
$deadline = (Get-Date).AddSeconds(60)
do { Start-Sleep -Seconds 3; $s = Status $order.id } until ($s -match 'payment (SUCCESS|FAILED)' -or (Get-Date) -gt $deadline)
$after = Balance $account
"4. $s"
"   余额 $before -> $after（应该只少 $($order.totalAmount)）"
$charges = Sql 'bank_db' "select count(*) from transactions where from_account = '$account' and status = 'SUCCESS'"
$keys = Sql 'bank_db' "select string_agg(transaction_ref, ', ') from transactions where from_account = '$account'"
"   bank 里这个账户成功扣款 $charges 笔；收到过的键：$keys"
$tasks = Sql 'store_db' "select count(*) from payment_outbox where order_id = $($order.id) and event_type = 'PAY'"
"   store 为这单写过 $tasks 条 PAY 任务（第一次 1 条 + Pay Now 1 条 = 2）"

if ($charges -eq '1' -and ([decimal]$before - [decimal]$after) -eq [decimal]$order.totalAmount) {
    'PASS: 连点两次，钱只扣了一次'
} else {
    'FAIL: 扣款次数或金额不对，看上面的数字'
}
