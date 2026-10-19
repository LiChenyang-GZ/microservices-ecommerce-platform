# End-to-end smoke test for the compose stack: one order across all four services.
# Run from the repository root after `docker compose -f backend/compose.yml up -d --wait`:
#   pwsh backend/docker/smoke-order.ps1
# It registers a fresh user each time, so it can be run repeatedly.
$ErrorActionPreference = 'Stop'
$compose = 'backend/compose.yml'
$store = 'http://localhost:8082/api'

function Sql($db, $query) {
    docker compose -f $compose exec -T postgres psql -U postgres -d $db -tAc $query
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
$balanceBefore = Sql 'bank_db' "select balance from bank_accounts where account_number = '$bankAccount'"
$order = (Invoke-RestMethod -Method Post "$store/store/orders/create-with-payment" -ContentType 'application/json' `
    -Body (@{ userId = $login.userId; orderItems = @(@{ productId = $product.id; quantity = 1 }) } | ConvertTo-Json -Depth 3)).order
"2. order $($order.id) placed, status $($order.status), balance before $balanceBefore"

# 3. Wait for payment (OutboxProcessor runs every 5 s), then for delivery webhooks to move the order on
$seen = @()
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 5
    $status = (Invoke-RestMethod "$store/store/orders/$($order.id)/with-payment").order.status
    if ($seen[-1] -ne $status) { $seen += $status; "   status -> $status" }
} until ($status -in 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'CANCELLED_SYSTEM' -or (Get-Date) -gt $deadline)

# 4. Evidence from each service's own database
$balanceAfter = Sql 'bank_db' "select balance from bank_accounts where account_number = '$bankAccount'"
$deliveryRow = Sql 'delivery_db' "select id, delivery_status from delivery where order_id = '$($order.id)'"
"3. statuses seen: $($seen -join ' -> ')"
"4. bank_db: balance $balanceBefore -> $balanceAfter (price $($product.price))"
"5. delivery_db: $deliveryRow"
if ($status -in 'IN_TRANSIT', 'DELIVERED') { 'PASS: order paid and delivery updates reached store' }
else { "FAIL: order stopped at $status"; exit 1 }
