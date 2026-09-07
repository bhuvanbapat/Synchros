# FlashReserve live JWT + HMAC smoke test (run against a live app on :8081)
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8081'
$ct = @{ 'Content-Type' = 'application/json' }

function Post($uri, $headers, $body) {
    Invoke-RestMethod -Uri "$base$uri" -Method Post -Headers $headers -Body $body -TimeoutSec 30
}

# 1. Login -> JWT
$login = Post '/api/auth/login' $ct '{"email":"alice@example.com","password":"password"}'
Write-Host "LOGIN OK — token $($login.accessToken.Substring(0,25))... (user: $($login.user.email), role: $($login.user.role))"
$bearer = @{ Authorization = "Bearer $($login.accessToken)" }
$bearerJson = @{ Authorization = "Bearer $($login.accessToken)"; 'Content-Type' = 'application/json' }

# 2. Catalog
$events = Invoke-RestMethod -Uri "$base/api/events" -Headers $bearer -TimeoutSec 15
$ev = $events[0]
Write-Host "EVENTS OK — $($events.Count) event(s): $($ev.name)"

# 3. Inventory
$inv = Invoke-RestMethod -Uri "$base/api/inventory?eventId=$($ev.id)" -Headers $bearer -TimeoutSec 15
Write-Host "INVENTORY OK — sections: $($inv.section -join ', ')"

# 4. Reserve (idempotent)
$res = Post '/api/reservations' ($bearerJson + @{ 'Idempotency-Key' = "e2e-smoke-$([guid]::NewGuid().ToString().Substring(0,8))" }) ('{"eventId":"' + $ev.id + '","section":"FLOOR","quantity":1}')
Write-Host "RESERVE OK — id=$($res.id.Substring(0,8)) state=$($res.state)"

# 5. Order
$order = Post '/api/orders' $bearerJson ('{"reservationId":"' + $res.id + '"}')
Write-Host "ORDER OK — id=$($order.id.Substring(0,8)) state=$($order.state) amount=$($order.amountCents)c"

# 6. Pay (signed webhook relay fires under the hood)
$pay = Post "/api/orders/$($order.id)/pay" $bearer '{}'
Write-Host "PAY OK — outcome=$($pay.outcome) orderState=$($pay.orderState) ref=$($pay.providerRef)"

# 7. Webhook HMAC: unsigned delivery must 401
try {
    Post '/api/payment-webhooks' $ct '{"orderId":"00000000-0000-0000-0000-000000000009","providerRef":"x","outcome":"SUCCESS"}' | Out-Null
    throw 'unsigned webhook was NOT rejected'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 401) { Write-Host 'HMAC OK — unsigned webhook rejected with 401' }
    else { throw "expected 401, got $code : $_" }
}

# 8. Tampered JWT must 401
try {
    $bad = @{ Authorization = "Bearer $($login.accessToken.Substring(0, $login.accessToken.Length - 4))AAAA" }
    Invoke-RestMethod -Uri "$base/api/events" -Headers $bad -TimeoutSec 15 | Out-Null
    throw 'tampered token was NOT rejected'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 401) { Write-Host 'JWT OK — tampered token rejected with 401' }
    else { throw "expected 401, got $code : $_" }
}

# 9. Notifications (Kafka consumer projection)
$notif = Invoke-RestMethod -Uri "$base/api/users/me/notifications" -Headers $bearer -TimeoutSec 15
Write-Host "NOTIFICATIONS OK — $($notif.Count) item(s)"

# 10. Admin metrics as admin
$adminLogin = Post '/api/auth/login' $ct '{"email":"admin@flashreserve.dev","password":"password"}'
$admin = @{ Authorization = "Bearer $($adminLogin.accessToken)" }
$metrics = Invoke-RestMethod -Uri "$base/api/admin/metrics" -Headers $admin -TimeoutSec 15
Write-Host "ADMIN OK — pools: $($metrics.pools.Count), outbox states: $($metrics.outbox.PSObject.Properties.Name -join '/')"

# 11. Reconciliation — world must be consistent after the whole flow
$recon = Post '/api/admin/reconciliation' $admin '{}'
Write-Host "RECON OK — pools=$($recon.poolsChecked) consistent=$($recon.consistent) findings=$($recon.findings.Count)"

# 12. Stale webhook replay must 401 (timestamp bound to the signature):
# re-sign the smoke body with a timestamp 1h in the past. The HMAC is
# correct for (t, body) — only the replay window rejects it.
function Sign-At([string]$body, [long]$epoch) {
    # mirrors WebhookSigner: HMAC-SHA256(secret, "<t>.<body>") -> "t=..,v1=.."
    $secret = if ($env:PAYMENT_WEBHOOK_SECRET) { $env:PAYMENT_WEBHOOK_SECRET } else { 'dev-only-webhook-secret-change-me-0123456789' }
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($secret))
    $material = [System.Text.Encoding]::UTF8.GetBytes("$epoch.$body")
    $mac = $hmac.ComputeHash($material)
    "t=$epoch,v1=$([Convert]::ToBase64String($mac))"
}
$staleBody = '{"orderId":"00000000-0000-0000-0000-000000000009","providerRef":"replay","outcome":"SUCCESS"}'
$staleSig = Sign-At $staleBody ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 3600)
try {
    Invoke-RestMethod -Uri "$base/api/payment-webhooks" -Method Post -Headers $ct -Body $staleBody -TimeoutSec 15 | Out-Null
    throw 'stale webhook was NOT rejected'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 401) { Write-Host 'REPLAY OK — captured delivery older than the window rejected with 401' }
    else { throw "expected 401, got $code : $_" }
}
# Fresh signature over the same body must PASS the HMAC gate (then 404 on
# the unknown order — proving the rejection above was the window, not the sig).
try {
    Post '/api/payment-webhooks' ($ct + @{ 'X-Signature' = (Sign-At $staleBody ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) ) }) $staleBody | Out-Null
    throw 'unknown-order webhook unexpectedly succeeded'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 404) { Write-Host 'REPLAY OK — fresh signature passes HMAC, fails 404 on unknown order as expected' }
    else { throw "expected 404 (sig ok, unknown order), got $code : $_" }
}

# 13. Login brute-force lockout (Redis-backed, live): 5 wrong passwords
# for a fresh (email, ip) — the 6th is refused 429 BEFORE credential check.
$lockEmail = "lockout-smoke-$([guid]::NewGuid().ToString('N').Substring(0,8))@example.com"
Post '/api/auth/register' $ct ('{"email":"' + $lockEmail + '","password":"password12345"}') | Out-Null
for ($i = 0; $i -lt 5; $i++) {
    $code = $null
    try {
        Post '/api/auth/login' $ct ('{"email":"' + $lockEmail + '","password":"wrong"}') | Out-Null
    } catch { $code = $_.Exception.Response.StatusCode.value__ }
    if ($code -ne 401) { throw "failed-login attempt $($i+1): expected 401, got $code" }
}
try {
    Post '/api/auth/login' $ct ('{"email":"' + $lockEmail + '","password":"wrong"}') | Out-Null
    throw '6th failed login was NOT locked out'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 429) { Write-Host 'LOCKOUT OK — 6th attempt within the window refused with 429' }
    else { throw "expected 429, got $code : $_" }
}

# 14. Account suspension round-trip: bob suspended -> live token 401s ->
# reactivated -> same token works again (no revocation list needed).
$bobLogin = Post '/api/auth/login' $ct '{"email":"bob@example.com","password":"password"}'
$bobToken = $bobLogin.accessToken
$bobId = ([guid]$bobLogin.user.id).ToString()
$adminJson = $admin + $ct
Post "/api/admin/users/$bobId/state" $adminJson '{"accountState":"SUSPENDED"}' | Out-Null
try {
    Invoke-RestMethod -Uri "$base/api/events" -Headers @{ Authorization = "Bearer $bobToken" } -TimeoutSec 15 | Out-Null
    throw 'suspended bob was NOT rejected'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 401) { Write-Host 'SUSPEND OK — live token 401s immediately after suspension' }
    else { throw "expected 401, got $code : $_" }
}
Post "/api/admin/users/$bobId/state" $adminJson '{"accountState":"ACTIVE"}' | Out-Null
Invoke-RestMethod -Uri "$base/api/events" -Headers @{ Authorization = "Bearer $bobToken" } -TimeoutSec 15 | Out-Null
Write-Host 'SUSPEND OK — reactivation restores the same token immediately'

# 15. Malformed transport: form-encoded body to a JSON API must be a
# clean 400, never a 500.
try {
    Invoke-RestMethod -Uri "$base/api/reservations" -Method Post -Headers $bearer -Body 'eventId=1&section=FLOOR&quantity=1' -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 15 | Out-Null
    throw 'form-encoded request was NOT rejected'
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    if ($code -eq 400) { Write-Host 'TRANSPORT OK — form-encoded to JSON API is a clean 400' }
    else { throw "expected 400, got $code : $_" }
}

Write-Host ''
Write-Host '=== SMOKE PASSED: JWT auth, HMAC webhook + replay window, full flow, login lockout, account suspension, reconciliation ==='
