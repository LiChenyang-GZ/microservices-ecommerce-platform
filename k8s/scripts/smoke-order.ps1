# End-to-end smoke test for the Kubernetes stack: one order through the gateway.
# Same steps as backend/docker/smoke-order.ps1 (compose), with two differences:
#   - every request goes to http://localhost/api/store/... (the single entry);
#   - it also checks the money: the balance must drop by exactly the price (09 B#7).
#   ./k8s/scripts/smoke-order.ps1
# It registers a fresh user each time, so it can be run repeatedly.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools.ps1"
$store = 'http://localhost/api/store'

function Sql($db, $query) {
    & $kubectl -n shop exec postgres-0 -- psql -U postgres -d $db -tAc $query
}

# 1. Register + activate + log in (activate takes only the email, so no Gmail is needed)
$email = "smoke-$(Get-Date -Format 'yyyyMMddHHmmss')@example.com"
$password = 'Smoke-Test-1'
Invoke-RestMethod -Method Post "$store/user" -ContentType 'application/json' `
    -Body (@{ username = $email.Split('@')[0]; email = $email; password = $password } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Method Post "$store/user/activate" -ContentType 'application/json' `
    -Body (@{ email = $email } | ConvertTo-Json) | Out-Null
$login = Invoke-RestMethod -Method Post "$store/user/login" -ContentType 'application/json' `
    -Body (@{ email = $email; password = $password } | ConvertTo-Json)
$bankAccount = Sql 'store_db' "select bank_account_number from account where email = '$email'"
"1. user $($login.userId) logged in, bank account $bankAccount"

# 2. Place an order for one iPhone 13
$product = (Invoke-RestMethod "$store/products").products | Where-Object name -eq 'iPhone 13'
$balanceBefore = [decimal](Sql 'bank_db' "select balance from bank_accounts where account_number = '$bankAccount'")
$order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
    -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
"2. order $($order.id) placed, status $($order.status), balance before $balanceBefore"

# 3. Wait for payment (OutboxProcessor runs every 5 s), then for delivery updates to move the order on
$seen = @()
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 5
    $status = (Invoke-RestMethod "$store/store/orders/$($order.id)/with-payment").order.status
    if ($seen[-1] -ne $status) { $seen += $status; "   status -> $status" }
} until ($status -in 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'CANCELLED_SYSTEM' -or (Get-Date) -gt $deadline)

# 4. Evidence from each service's own database
$balanceAfter = [decimal](Sql 'bank_db' "select balance from bank_accounts where account_number = '$bankAccount'")
$deliveryRow = Sql 'delivery_db' "select id, delivery_status from delivery where order_id = '$($order.id)'"
"3. statuses seen: $($seen -join ' -> ')"
"4. bank_db: balance $balanceBefore -> $balanceAfter (price $($product.price))"
"5. delivery_db: $deliveryRow"
$paidOnce = ($balanceBefore - $balanceAfter) -eq [decimal]$product.price
if ($status -in 'IN_TRANSIT', 'DELIVERED' -and $paidOnce) { 'PASS: order paid exactly once and delivery updates reached store' }
elseif (-not $paidOnce) { "FAIL: balance changed by $($balanceBefore - $balanceAfter), price is $($product.price)"; exit 1 }
else { "FAIL: order stopped at $status"; exit 1 }
