# Which paths the single entry lets through, checked against both entries:
# the kind gateway (http://localhost) and compose's frontend nginx (http://localhost:3000).
#   ./k8s/scripts/check-entry.ps1                              # both entries
#   ./k8s/scripts/check-entry.ps1 -Entries http://localhost    # only one
#
# The entry is an allowlist: it forwards only the method + path pairs the
# frontend calls, and answers 404 itself for everything else.
#   open    the request reaches the service (any answer except the entry's own 404 or a 502/503/504)
#   closed  the entry's own 404 (body "not found"), the service never sees it
#   page    the frontend's index.html with 200
# Requests carry no token, an empty JSON body and ids that do not exist,
# so the open ones are refused by the service and change nothing.
param([string[]]$Entries = @('http://localhost', 'http://localhost:3000'))
$ErrorActionPreference = 'Stop'

# One request per line: expect, method, path, then why (the rest of the line).
$cases = @'
# ---- open: every call in frontend/src (services/api.js, services/adminService.js) ----
# store, /api/store/x -> store /api/x
open   POST   /api/store/user                                           register
open   POST   /api/store/user/login                                     login
open   POST   /api/store/user/activate                                  email verification page
open   POST   /api/store/user/forgot-password                           forgot password
open   POST   /api/store/user/reset-password                            reset password
open   GET    /api/store/products                                       catalogue; admin dashboard
open   GET    /api/store/products/available                             catalogue
open   GET    /api/store/products/999999999                             product page, checkout
open   POST   /api/store/store/orders/create-with-payment               checkout
open   GET    /api/store/store/orders/user/999999999/with-payment       my orders
open   GET    /api/store/store/orders/999999999/with-payment            order page
open   POST   /api/store/store/orders/999999999/cancel                  order page, delivery page
open   POST   /api/store/store/orders/999999999/retry-payment           order page: Pay Now
open   POST   /api/store/payments/999999999/refund                      order page: refund button
open   GET    /api/store/orders                                         admin dashboard
open   GET    /api/store/warehouses                                     admin dashboard
open   GET    /api/store/warehouses/audit-logs/out-transactions         admin dashboard
# email, unchanged path
open   POST   /api/email/send-verification                              register
open   POST   /api/email/verify-code                                    email verification page
# delivery, /api/delivery/x -> delivery /api/x
open   GET    /api/delivery/deliveries/me                               delivery list
open   GET    /api/delivery/deliveries/999999999                        delivery page
open   POST   /api/delivery/deliveries/999999999/cancel                 delivery page
open   GET    /api/delivery/admin/dlq/delivery                          admin dashboard
open   POST   /api/delivery/admin/dlq/delivery/999999999/resolve        admin dashboard
open   POST   /api/delivery/admin/dlq/delivery/999999999/requeue        admin dashboard

# ---- closed: review #1, delivery's token-free calls that only store makes ----
closed POST   /api/delivery/deliveries/create                           #1 fake delivery for any order
closed POST   /api/delivery/deliveries/create/batch                     #1
closed POST   /api/delivery/deliveries/cancel-by-order/999999999        #1 cancel -> store refunds
closed POST   /api/delivery/deliveries/cancel-by-order/cancel           #1 id that is not a number
closed GET    /api/delivery/deliveries/all/x@example.com                not used by the frontend
# ---- closed: review #2, store's write calls the frontend does not make ----
closed POST   /api/store/store/orders/999999999/payment-success         #2 PAID without bank
closed POST   /api/store/store/orders/999999999/payment-failure         #2
closed PUT    /api/store/orders/999999999/status                        #2 any status
closed PUT    /api/store/orders/999999999/cancel                        #2
closed POST   /api/store/orders                                         #2 order without payment
closed POST   /api/store/payments                                       #2
closed PUT    /api/store/warehouses/products/quantity                   #2 stock
closed PUT    /api/store/warehouses/unhold                              #2 stock
closed DELETE /api/store/warehouses/999999999                           #2 stock
closed POST   /api/store/products                                       #2
closed PUT    /api/store/products/999999999                             #2
closed POST   /api/store/user/validate-token                            service-to-service
closed GET    /api/store/orders/user/999999999                          not used by the frontend
closed POST   /api/store/delivery-webhook                               callback from delivery, no auth
# ---- closed: a frontend path with the wrong method ----
closed POST   /api/store/products/available                             only GET is used
closed DELETE /api/delivery/deliveries/999999999                        only GET is used
# ---- closed: email sends that other services make ----
closed POST   /api/email/send-order-cancelled                           store -> email
closed POST   /api/email/send-password-reset                            store -> email
# ---- closed: bank (review #3: the top-up page is gone, so the frontend calls no bank path) ----
closed POST   /api/bank/transfer                                        money from SYSTEM to anyone
closed POST   /api/bank/refund                                          store -> bank
closed GET    /api/bank/account/me                                      only the removed top-up page used it
closed GET    /api/bank/account/CUSTOMER_ACCOUNT_001/balance            only the removed top-up page used it
# ---- closed: tracking (no page calls it yet) ----
closed GET    /api/tracking/orders/999999999/timeline                   no frontend caller
# ---- closed: not part of the API at all ----
closed GET    /actuator/health                                          probes stay inside
closed GET    /internal/deliveries/cancel-by-order/1                    internal prefix
closed GET    /api/nothing-here                                         unknown /api path

# ---- page: everything else is the React app ----
page   GET    /                                                         home
page   GET    /orders                                                   React Router path, reload
'@ -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') } |
    ForEach-Object { $e, $m, $p, $w = $_ -split '\s+', 4; [pscustomobject]@{ Expect = $e; Method = $m; Path = $p; Why = $w } }

function Probe($entry, $method, $path) {
    $request = @{ Method = $method; Uri = "$entry$path"; SkipHttpErrorCheck = $true; TimeoutSec = 15 }
    if ($method -in 'POST', 'PUT') { $request.Body = '{}'; $request.ContentType = 'application/json' }
    try {
        $r = Invoke-WebRequest @request
        return @{ Status = [int]$r.StatusCode; Body = "$($r.Content)" }
    } catch {
        return @{ Status = 0; Body = $_.Exception.Message }
    }
}

# The entry's own 404: Envoy's directResponse "not found", or nginx's own error page.
function IsEntry404($r) { $r.Status -eq 404 -and ($r.Body.Trim() -eq 'not found' -or $r.Body -match '<center>nginx') }

function Verdict($expect, $r) {
    switch ($expect) {
        'closed' { return (IsEntry404 $r) }
        'open'   { return $r.Status -ne 0 -and -not (IsEntry404 $r) -and $r.Status -notin 502, 503, 504 }
        'page'   { return $r.Status -eq 200 -and $r.Body -match '<div id="root">' }
    }
}

$failed = 0
$rows = foreach ($c in $cases) {
    $expect, $method, $path, $why = $c.Expect, $c.Method, $c.Path, $c.Why
    $row = [ordered]@{ Expect = $expect; Request = "$method $path" }
    $ok = $true
    foreach ($entry in $Entries) {
        $r = Probe $entry $method $path
        $pass = Verdict $expect $r
        if (-not $pass) { $ok = $false }
        $row[$entry] = "$($r.Status)$(if (-not $pass) { ' X' })"
    }
    if (-not $ok) { $failed++ }
    $row.Result = if ($ok) { 'ok' } else { 'FAIL' }
    $row.Why = $why
    [pscustomobject]$row
}
$rows | Format-Table -AutoSize | Out-String -Width 250

if ($failed) { Write-Host "$failed of $($cases.Count) FAIL  (X = that entry gave the wrong answer)" -ForegroundColor Red; exit 1 }
Write-Host "all $($cases.Count) ok on $($Entries -join ', ')" -ForegroundColor Green
