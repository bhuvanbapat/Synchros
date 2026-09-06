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

Write-Host ''
Write-Host '=== SMOKE PASSED: JWT auth, HMAC webhook enforcement, full flow, reconciliation ==='
