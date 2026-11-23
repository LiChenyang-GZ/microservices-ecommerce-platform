# Activating a new account in the scripts (A11). Since A11 store's activate needs the 6-digit code from the
# registration email, so the scripts do what the registration page does: ask store to send a code (store asks
# email, rate limits, Obsidian 54), read the code from Mailpit (the fake SMTP server compose and kind send mail
# to), and hand it to store's activate.
# Dot-source it, then call Confirm-ShopperEmail after registering:
#   . "$PSScriptRoot\mailpit.ps1"                 # from backend/docker
#   Confirm-ShopperEmail $email                   # compose: store on :8082, Mailpit on :8025
#   Confirm-ShopperEmail $email -Kind $kubectl    # kind: store through the gateway, Mailpit by port-forward
# store limits send-verification to 20 per hour from one address (rate limits, Obsidian 54). One run of all the
# scenarios and both smoke scripts registers about 10 shoppers, so about two full runs an hour. To start the counts
# again on this machine (compose; they live only in Redis, nothing else is lost):
#   docker compose -f backend/compose.yml exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHDB'
# Needs the default MAIL_HOST (Mailpit). With a real mail server in backend/.env the code never reaches Mailpit.

# The newest mail Mailpit holds for $Email, as text, or $null. $Base is Mailpit's web/API address.
function Get-MailpitText([string]$Base, [string]$Email) {
    $query = [uri]::EscapeDataString("to:`"$Email`"")
    $found = Invoke-RestMethod "$Base/api/v1/search?query=$query&limit=1"
    if (-not $found.messages) { return $null }
    (Invoke-RestMethod "$Base/api/v1/message/$($found.messages[0].ID)").Text
}

# Send a registration code to $Email, wait (up to 30 s) for it in Mailpit, then activate the account with it.
function Confirm-ShopperEmail([string]$Email, [string]$Kind) {
    if ($Kind) {
        $storeApi = 'http://localhost/api/store'
        # Mailpit is not behind the gateway (k8s/base/mailpit.yaml): open a port-forward for this call
        $forward = Start-Process $Kind -ArgumentList '-n', 'shop', 'port-forward', 'svc/mailpit', '18025:8025' `
            -PassThru -WindowStyle Hidden
        $mailpit = 'http://localhost:18025'
    } else {
        $storeApi = 'http://localhost:8082/api'
        $forward = $null
        $mailpit = 'http://localhost:8025'
    }
    try {
        Invoke-RestMethod -Method Post "$storeApi/user/send-verification" -ContentType 'application/json' `
            -Body (@{ email = $Email } | ConvertTo-Json) | Out-Null
        $deadline = (Get-Date).AddSeconds(30)
        $code = $null
        while (-not $code) {
            try {
                $text = Get-MailpitText $mailpit $Email
                if ($text -match 'Code is: (\d{6})') { $code = $Matches[1] }
            } catch {
                if ((Get-Date) -gt $deadline) { throw } # kind: the port-forward may not be up yet
            }
            if (-not $code) {
                if ((Get-Date) -gt $deadline) { throw "No verification email for $Email in Mailpit ($mailpit) after 30 s" }
                Start-Sleep -Milliseconds 500
            }
        }
        Invoke-RestMethod -Method Post "$storeApi/user/activate" -ContentType 'application/json' `
            -Body (@{ email = $Email; code = $code } | ConvertTo-Json) | Out-Null
    } finally {
        if ($forward) { Stop-Process -Id $forward.Id -ErrorAction SilentlyContinue }
    }
}
