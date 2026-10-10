<#
 NT14 gateway security smoke test. Run it only against your own deployment.

 Usage:
   .\security_check.ps1
   .\security_check.ps1 -OldSecret "<your PREVIOUS JWT secret>" -AdminToken "<a real ADMIN JWT>"
   .\security_check.ps1 -SkipBurst        # skip the rate-limit burst (it can auto-ban this machine ~5 min)

 Notes:
   - Needs curl.exe (built into Windows 10/11). On macOS/Linux use PowerShell 7 (pwsh).
   - Tests are spaced out (-DelayMs) so they don't trip the gateway's own auto-ban (12 errors / 30 s).
   - No secret or token is ever printed. Results are also saved to security_report.csv.
#>
param(
  [string]$Base = "https://api-rate-limiter-gateway-n0ab.onrender.com",
  [string]$OldSecret = "",
  [string]$AdminToken = "",
  [string]$BurstPath = "/api/polylance/escrows",
  [int]$DelayMs = 2600,
  [switch]$SkipBurst
)

$NullDev = if ($env:OS -eq "Windows_NT") { "NUL" } else { "/dev/null" }
$Results = New-Object System.Collections.Generic.List[object]

function Report($Name, $Status, $Detail) {
  $Results.Add([pscustomobject]@{ Test = $Name; Status = $Status; Detail = $Detail })
  $color = switch ($Status) { "PASS" { "Green" } "FAIL" { "Red" } "WARN" { "Yellow" } default { "Gray" } }
  Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
}

function Http([string]$Method, [string]$Path, [string[]]$Headers = @(), [string]$Body = "", [switch]$NoDelay) {
  if (-not $NoDelay) { Start-Sleep -Milliseconds $DelayMs }
  $a = @("-s", "-o", $NullDev, "-w", "%{http_code}", "-X", $Method, "--max-time", "40")
  foreach ($h in $Headers) { $a += @("-H", $h) }
  if ($Body) { $a += @("-H", "Content-Type: application/json", "--data-binary", $Body) }
  $a += "$Base$Path"
  $out = & curl.exe @a
  if (-not $out) { "000" } else { ($out -join "").Trim() }
}

function HttpBody([string]$Path, [string[]]$Headers = @()) {
  Start-Sleep -Milliseconds $DelayMs
  $a = @("-s", "--max-time", "40")
  foreach ($h in $Headers) { $a += @("-H", $h) }
  $a += "$Base$Path"
  (& curl.exe @a) -join "`n"
}

function ExpectDenied($Name, $Method, $Path, [string[]]$Headers = @(), [string]$Body = "",
                      [string[]]$Allowed = @("401", "403"), [switch]$Strict) {
  $c = Http $Method $Path $Headers $Body
  if ($Allowed -contains $c) { Report $Name "PASS" "denied ($c)" }
  elseif ($c -eq "429") { Report $Name "WARN" "429: rate-limited or auto-banned, re-run in 5 minutes" }
  elseif ($c -like "2*" -or $Strict) { Report $Name "FAIL" "returned $c, expected denial" }
  else { Report $Name "WARN" "returned $c (expected $($Allowed -join '/'))" }
}

function B64Url([byte[]]$b) { [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_') }

function Make-Jwt([string]$Alg, [string]$Secret, $Claims) {
  $h = B64Url ([Text.Encoding]::UTF8.GetBytes((@{ alg = $Alg; typ = "JWT" } | ConvertTo-Json -Compress)))
  $p = B64Url ([Text.Encoding]::UTF8.GetBytes(($Claims | ConvertTo-Json -Compress)))
  $unsigned = "$h.$p"
  if ($Alg -eq "none") { return "$unsigned." }
  $hmac = New-Object Security.Cryptography.HMACSHA256
  $hmac.Key = [Text.Encoding]::UTF8.GetBytes($Secret)
  "$unsigned." + (B64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned))))
}

Write-Host "`nTarget: $Base`n"

# 1. Reachability, TLS, information exposure ----------------------------------------------------
$c = Http GET "/health" -NoDelay
if ($c -eq "200") { Report "Gateway reachable" "PASS" "200" }
else { Report "Gateway reachable" "FAIL" "got $c (server asleep? open /health in a browser and retry)" }

$plain = $Base -replace "^https://", "http://"
$r = ((& curl.exe -s -o $NullDev -w "%{http_code} %{redirect_url}" --max-time 30 "$plain/health") -join "").Trim()
if ($r -match "^(301|302|307|308) https://") { Report "HTTP redirects to HTTPS" "PASS" $r }
elseif ($r -like "000*") { Report "Plain HTTP refused" "PASS" "connection refused" }
else { Report "Plain HTTP answered" "WARN" $r }

$h = HttpBody "/health"
if ($h -match "activeClients|endpointCounts") {
  Report "Public /health detail" "WARN" "internal metrics visible to anyone; return only status in production"
}

# 2. Unauthenticated READ access (decide if intended) -------------------------------------------
foreach ($p in "/api/stats", "/api/logs?limit=1", "/api/rules", "/api/bans", "/api/polylance/escrows") {
  $c = Http GET $p
  if ($c -like "2*") { Report "Open read $p" "WARN" "200 without login (is this intended?)" }
  else { Report "Read protected $p" "PASS" "got $c" }
}

# 3. Unauthenticated WRITE access (must be denied) ----------------------------------------------
ExpectDenied "No-auth POST /api/rules"      POST   "/api/rules" @() '{"endpoint":"/x","limitPerMin":1,"burstLimit":1}'
ExpectDenied "No-auth DELETE /api/rules"    DELETE "/api/rules/test"
ExpectDenied "No-auth DELETE /api/bans"     DELETE "/api/bans/nt14-test-client"
ExpectDenied "No-auth POST /api/simulate"   POST   "/api/simulate" @() '{}'

# 4. Pairing endpoint must not hand out credentials publicly ------------------------------------
$c = Http GET "/api/auth/pair"
if ($c -in @("401", "403")) { Report "/api/auth/pair requires auth" "PASS" "denied ($c)" }
elseif ($c -like "2*") {
  $b = HttpBody "/api/auth/pair"
  if ($b -match '"(token|ticket|secret|jwt|password|apiKey)"') {
    Report "/api/auth/pair public" "FAIL" "returns credential-like fields with no auth (values hidden)"
  } else { Report "/api/auth/pair public" "WARN" "200 without auth but no credential fields seen" }
} else { Report "/api/auth/pair" "WARN" "returned $c" }

# 5. Legacy / insecure credential channels ------------------------------------------------------
$c = Http GET "/api/bans?api_key=dev-local-key"
if ($c -like "2*") { Report "api_key in query string" "FAIL" "gateway accepts a key in the URL (F3 regression)" }
else { Report "api_key in query string" "PASS" "not accepted ($c)" }

# 6. Forged / expired JWTs (strict: anything except 401/403 is a failure) -----------------------
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$admin = @{ sub = "attacker"; email = "akpolylance@gmail.com"; role = "ADMIN"; iat = $now; exp = $now + 3600 }
$expired = @{ sub = "attacker"; email = "akpolylance@gmail.com"; role = "ADMIN"; iat = $now - 7200; exp = $now - 3600 }
$forged = [ordered]@{
  "alg=none ADMIN"           = Make-Jwt "none" "" $admin
  "random-secret HS256 ADMIN" = Make-Jwt "HS256" ([guid]::NewGuid().ToString()) $admin
  "expired token"            = Make-Jwt "HS256" ([guid]::NewGuid().ToString()) $expired
}
if ($OldSecret) { $forged["OLD secret ADMIN (must be rejected after rotation)"] = Make-Jwt "HS256" $OldSecret $admin }
foreach ($k in $forged.Keys) {
  ExpectDenied "Forged JWT: $k" DELETE "/api/bans/nt14-test-client" @("Authorization: Bearer $($forged[$k])") -Strict
}

# 7. Fake Google ID token and oversized body -----------------------------------------------------
ExpectDenied "Fake Google ID token" POST "/api/auth/google" @() '{"idToken":"not.a.real.token"}' @("400", "401", "403")

Start-Sleep -Milliseconds $DelayMs
$tmp = New-TemporaryFile
Set-Content -Path $tmp -Value ('{"idToken":"' + ('A' * 2000000) + '"}') -NoNewline
$c = ((& curl.exe -s -o $NullDev -w "%{http_code}" -X POST -H "Content-Type: application/json" --data-binary "@$tmp" --max-time 40 "$Base/api/auth/google") -join "").Trim()
Remove-Item $tmp -ErrorAction SilentlyContinue
if ($c -in @("400", "401", "413")) { Report "2 MB request body" "PASS" "rejected ($c)" }
else { Report "2 MB request body" "WARN" "returned $c (expected 400/401/413; check body size limits)" }

# 8. WebSocket without credentials ---------------------------------------------------------------
Start-Sleep -Milliseconds $DelayMs
$ws = (& curl.exe -s -i -N --max-time 6 -H "Connection: Upgrade" -H "Upgrade: websocket" `
        -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "$Base/ws/events" 2>$null |
       Select-Object -First 1)
if ($ws -match "101") { Report "WebSocket without credentials" "WARN" "upgrade accepted with no token (ok only if the feed is meant to be public)" }
else { Report "WebSocket without credentials" "PASS" "$ws" }

# 9. Response security headers -------------------------------------------------------------------
$hdr = (& curl.exe -s -D - -o $NullDev --max-time 30 "$Base/health") -join "`n"
foreach ($n in "X-Content-Type-Options", "Strict-Transport-Security") {
  if ($hdr -match "(?im)^$n") { Report "Header $n" "PASS" "present" }
  else { Report "Header $n" "WARN" "missing" }
}

# 10. Real admin token must work (positive control) ---------------------------------------------
if ($AdminToken) {
  $c = Http GET "/api/bans" @("Authorization: Bearer $AdminToken")
  if ($c -like "2*") { Report "Admin token accepted" "PASS" "$c" } else { Report "Admin token accepted" "FAIL" "got $c" }
}

# 11. Rate limiting works and tells clients to back off (LAST: may ban this machine) -------------
if (-not $SkipBurst) {
  Write-Host "`nBurst test on $BurstPath (may auto-ban this machine for ~5 minutes)..." -ForegroundColor Cyan
  $codes = 1..40 | ForEach-Object { Http GET $BurstPath -NoDelay }
  $n429 = @($codes | Where-Object { $_ -eq "429" }).Count
  if ($n429 -gt 0) { Report "Rate limiter engages" "PASS" "$n429/40 fast requests got 429" }
  else { Report "Rate limiter engages" "WARN" "no 429 in 40 fast requests (limit may be higher, or $BurstPath needs auth)" }

  $hdr = (& curl.exe -s -D - -o $NullDev --max-time 30 "$Base$BurstPath") -join "`n"
  if ($hdr -match "HTTP/\S+ 429") {
    if ($hdr -match "(?im)^Retry-After") { Report "429 includes Retry-After" "PASS" "present" }
    else { Report "429 includes Retry-After" "FAIL" "header missing" }

    $c = Http GET $BurstPath @("X-Forwarded-For: 203.0.113.$(Get-Random -Maximum 250)") -NoDelay
    if ($c -like "2*") { Report "X-Forwarded-For spoofing" "WARN" "limit bypassed with a fake XFF; verify TRUSTED_PROXIES handling" }
    else { Report "X-Forwarded-For spoofing" "PASS" "still limited ($c)" }
  }
  Write-Host "If you were auto-banned, lift it from the app (Security > Bans) or wait ~5 minutes." -ForegroundColor Cyan
}

# Summary ----------------------------------------------------------------------------------------
$p = @($Results | Where-Object Status -eq "PASS").Count
$w = @($Results | Where-Object Status -eq "WARN").Count
$f = @($Results | Where-Object Status -eq "FAIL").Count
Write-Host "`nSummary: PASS=$p  WARN=$w  FAIL=$f" -ForegroundColor $(if ($f) { "Red" } elseif ($w) { "Yellow" } else { "Green" })
$Results | Export-Csv -NoTypeInformation -Path "security_report.csv"
Write-Host "Saved security_report.csv"
if ($f -gt 0) { exit 1 }
