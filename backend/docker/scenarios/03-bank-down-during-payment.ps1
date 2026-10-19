# 场景 3 · 付款时 bank 挂了，过一会儿又恢复
# 在仓库根目录、compose 已经起来时运行：
#   pwsh backend/docker/scenarios/03-bank-down-during-payment.ps1
# 每次注册一个新用户，可以反复跑。脚本会停掉 bank 再启动它，最后 bank 一定是开着的。
# 要等约 11 分钟：结果未知（PAY_UNKNOWN）之后，store 每 10 分钟才用同一个键再试一次。
#
# 剧情：
#   1. 新用户余额 10000，先停掉 bank，再买 1 台 iPhone 13（6999）
#   2. store 调 bank 连不上 → 不知道钱扣没扣 → 同一个键连试 5 次 → PAY_UNKNOWN + 告警日志
#   3. 用户着急点 Pay Now → 不应该换新键再扣（钱可能已经扣了）
#   4. bank 恢复 → 10 分钟内 store 用同一个键再试 → 成功，钱只扣一次
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
}
function Order($orderId) {
    Invoke-RestMethod "$store/store/orders/$orderId/with-payment"
}
function Show($o) {
    "order $($o.order.status), payment $($o.payment.status) (attempt $($o.payment.attempt))"
}

try {
    # --- 准备一个新用户（bank 还开着：注册时要开银行账户）---
    $email = "bankdown-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
    $password = 'Smoke-Test-1'
    Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
        -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
    Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
        -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
    $login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
        -Body (@{ email = $email; password = $password } | ConvertTo-Json)
    $auth = @{ Authorization = "Bearer $($login.token)" }
    $account = Sql 'store_db' "select bank_account_number from account where email = '$email'"
    $balanceStart = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
    $product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq 'iPhone 13'
    "0. 新用户 $email，余额 $balanceStart"

    # --- 1. 停 bank，下单 ---
    docker compose -f $compose stop bank 2>$null | Out-Null
    "1. bank 已停止"
    $order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
        -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
    "   下单 $($order.id)，$($order.totalAmount)"

    # --- 2. 等 PAY_UNKNOWN ---
    $deadline = (Get-Date).AddSeconds(90)
    do { Start-Sleep -Seconds 3; $o = Order $order.id } until ($o.payment.status -eq 'PAY_UNKNOWN' -or (Get-Date) -gt $deadline)
    "2. $(Show $o)"
    if ($o.payment.status -ne 'PAY_UNKNOWN') { throw "前提不成立：付款没有变成 PAY_UNKNOWN" }
    $alert = docker compose -f $compose logs store --since 3m 2>$null | Select-String "ALERT payment_unknown" | Select-Object -Last 1
    "   告警日志：$($alert.Line.Trim())"

    # --- 3. 用户着急点 Pay Now ---
    try {
        $r = Invoke-RestMethod -Method Post "$store/store/orders/$($order.id)/retry-payment" -Headers $auth
        "3. 点 Pay Now：$($r.message)"
    } catch {
        "3. 点 Pay Now 被拒绝：$($_.ErrorDetails.Message)"
    }
    $o = Order $order.id
    "   $(Show $o)（attempt 应该还是 1：结果未知时不能换新键）"
    $attemptAfterPayNow = $o.payment.attempt
}
finally {
    # --- 4. 不管上面成没成功，都把 bank 开回来 ---
    docker compose -f $compose start bank 2>$null | Out-Null
    docker compose -f $compose up -d --wait bank 2>$null | Out-Null
    "4. bank 已恢复（healthy）"
}

# --- 5. 等 store 用同一个键再试（最多约 11 分钟）---
"5. 等 store 每 10 分钟一次的重试，每 30 秒看一眼 ..."
$deadline = (Get-Date).AddMinutes(11)
do {
    Start-Sleep -Seconds 30
    $o = Order $order.id
    "   $(Get-Date -Format 'HH:mm:ss') $(Show $o)"
} until ($o.payment.status -in 'SUCCESS', 'FAILED' -or (Get-Date) -gt $deadline)

$balanceEnd = Sql 'bank_db' "select balance from bank_accounts where account_number = '$account'"
$keys = Sql 'bank_db' "select string_agg(transaction_ref || ' ' || status, ', ') from transactions where from_account = '$account'"
"6. $(Show $o)"
"   余额 $balanceStart -> $balanceEnd（应该只少 $($order.totalAmount)）"
"   bank 收到的扣款：$keys（应该只有 1 个键）"

if ($o.payment.status -eq 'SUCCESS' -and $attemptAfterPayNow -eq 1 -and
    ([decimal]$balanceStart - [decimal]$balanceEnd) -eq [decimal]$order.totalAmount -and $keys -notmatch ',') {
    'PASS: bank 挂了又恢复，同一个键补上了付款，钱只扣一次'
} else {
    'FAIL: 看上面哪个数字不对'
}
