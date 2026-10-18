<#
.SYNOPSIS
    V3 governed financial closed-loop smoke (normal / duplicate / provider-failure).

.DESCRIPTION
    Continues the M21 operational harness past the execution smoke: after a real
    Gateway request reaches the deterministic mock Provider, this script waits for
    the Backend GatewaySettlementWorker and asserts the full financial truth on the
    SAME MySQL that is the financial source of truth:

      normal   : 1 request -> 1 FINAL usage fact (with pricing version) -> 1 SETTLED
                 settlement -> 1 GATEWAY_SETTLEMENT ledger posting + 1 COST entry
                 -> Budget Actual increased by the posted amount -> reservation FINALIZED.
      duplicate: replaying the identical Idempotency-Key does NOT execute the Provider
                 again and does NOT create any second financial mutation.
      failure  : a mock Provider error is NOT booked as zero cost (no Ledger posting,
                 no SETTLED settlement) and the ledger effect count is unchanged.

    The Idempotency-Key digest is computed exactly as the Gateway does:
    HMAC-SHA256(key = AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1, msg = "idem\0" + raw key).

    Reuses the same .env.m21.local / .m21-operational-state.json as the M21 harness.
    No secret is printed. Exits 0 and prints M21_FINANCIAL_SMOKE_PASS only when every
    assertion passes.
#>
[CmdletBinding()]
param(
    [string]$GatewayBase = "http://127.0.0.1:8081",
    [string]$MockBase = "http://127.0.0.1:8089",
    [string]$ComposeProject = "aicostops-m21-dev",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$StateFile = ".m21-operational-state.json",
    [string]$ModelKey = "default-chat",
    [int]$SettleTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$helperPath = Join-Path $PSScriptRoot "import-v3-operational-env.ps1"
if (Test-Path $helperPath) {
    . $helperPath
    Import-M21OperationalEnv
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$statePath = Join-Path $repoRoot $StateFile
if (-not (Test-Path $statePath)) {
    Write-Error "[M21-FIN] State file not found. Run scripts/m21/seed-v3-operational.ps1 first."
    exit 1
}
$state = Get-Content $statePath -Raw | ConvertFrom-Json
$orgId = [long]$state.org_id

$composeArgs = @()
foreach ($file in $ComposeFile.Split(',')) {
    $composeArgs += "-f"
    $composeArgs += $file.Trim()
}
$composeArgs += "-p"
$composeArgs += $ComposeProject
$composeArgs += "--env-file"
$composeArgs += ".env.m21.local"

function Invoke-Mysql([string]$Sql) {
    # Root password comes from the mysql container's own environment
    # (MYSQL_ROOT_PASSWORD); host env does not propagate through `compose exec`.
    $result = docker compose @composeArgs exec -T mysql sh -c "mysql -u root -p`"`$MYSQL_ROOT_PASSWORD`" aicostops -N -B -e `"$Sql`" 2>&1"
    $filtered = ($result | Where-Object { $_ -notmatch "Warning" }) -join "`n"
    return $filtered.Trim()
}

function Get-IdemDigestHex([string]$RawKey) {
    $hmacKey = [Convert]::FromBase64String($env:AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1)
    $hm = New-Object System.Security.Cryptography.HMACSHA256(, $hmacKey)
    $prefix = [Text.Encoding]::UTF8.GetBytes("idem`0")
    $hm.TransformBlock($prefix, 0, $prefix.Length, $null, 0) | Out-Null
    $hm.TransformFinalBlock([Text.Encoding]::UTF8.GetBytes($RawKey), 0, $RawKey.Length) | Out-Null
    return (-join ($hm.Hash | ForEach-Object { $_.ToString("x2") }))
}

function Send-Chat([string]$RawKey, [string]$IdemKey) {
    $body = @{
        model    = $ModelKey
        messages = @(@{ role = "user"; content = "m21 financial smoke" })
        stream   = $false
    } | ConvertTo-Json -Depth 3
    $headers = @{
        "Authorization"   = "Bearer $RawKey"
        "Idempotency-Key" = $IdemKey
        "Content-Type"    = "application/json"
    }
    try {
        $r = Invoke-WebRequest -Uri "$GatewayBase/v1/chat/completions" -Method POST `
            -Headers $headers -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
            -SkipHttpErrorCheck -TimeoutSec 90
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}

function Get-BudgetActuals([string]$Query) {
    # Returns a hashtable budget_id -> actual_amount (decimal string).
    $map = @{}
    $raw = Invoke-Mysql $Query
    if ([string]::IsNullOrWhiteSpace($raw)) { return $map }
    foreach ($line in ($raw -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $f = $line -split "`t"
        $map[$f[0].Trim()] = $f[1].Trim()
    }
    return $map
}

$failures = [System.Collections.ArrayList]::new()
function Assert-That([bool]$Cond, [string]$Msg) {
    if (-not $Cond) { $failures.Add($Msg) | Out-Null; Write-Output ("[M21-FIN][FAIL] " + $Msg) }
    else { Write-Output ("[M21-FIN][ok]   " + $Msg) }
}

# ---- raw key (never printed) ----
$envFile = Join-Path $repoRoot ".env.m21.local"
$envContent = Get-Content $envFile -Raw
if ($envContent -notmatch "AICOSTOPS_GATEWAY_DEV_RAW_KEY=(.+)") {
    Write-Error "[M21-FIN] AICOSTOPS_GATEWAY_DEV_RAW_KEY not found in $envFile"
    exit 1
}
$rawKey = $Matches[1].Trim()

Write-Output "[M21-FIN] org=$orgId pricing_version=$($state.pricing_version_id) profile=$($state.connection_profile_id)"

# ---- health + mock reset ----
$health = Invoke-WebRequest -Uri "$GatewayBase/actuator/health/liveness" -SkipHttpErrorCheck -TimeoutSec 30
Assert-That ($health.StatusCode -eq 200) "gateway liveness 200 (got $($health.StatusCode))"
Invoke-RestMethod -Method POST -Uri "$MockBase/admin/reset" | Out-Null

# budgets snapshot before any request
$budgetBefore = Get-BudgetActuals "SELECT id, actual_amount FROM budget WHERE org_id=$orgId;"
Write-Output ("[M21-FIN] budgets before: " + (($budgetBefore.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ", "))

# =========================================================================
# NORMAL
# =========================================================================
Write-Output "[M21-FIN] === NORMAL scenario ==="
$normalIdem = "m21-fin-normal-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$nStats0 = (Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
$nResp = Send-Chat $rawKey $normalIdem
Write-Output "[M21-FIN] normal request HTTP $($nResp.Status)"
Assert-That ($nResp.Status -eq 200) "normal request returns 200 (got $($nResp.Status): $($nResp.Body))"
$nJson = $null
try { $nJson = $nResp.Body | ConvertFrom-Json } catch { }
$nContent = ""
try { $nContent = $nJson.choices[0].message.content } catch { $nContent = "" }
Assert-That ($nContent -eq "Hello from M16 mock") "normal body is deterministic mock content (got $nContent)"
$nDelta = (Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions - $nStats0
Assert-That ($nDelta -eq 1) "normal provider operation delta = 1 (got $nDelta)"

$digest = Get-IdemDigestHex $normalIdem
$reqRows = Invoke-Mysql "SELECT id, public_request_id, current_route_attempt_id, billing_period_id FROM gateway_request WHERE org_id=$orgId AND idempotency_key_digest=UNHEX('$digest');"
Assert-That (-not [string]::IsNullOrWhiteSpace($reqRows)) "gateway_request found by idempotency digest"
if ([string]::IsNullOrWhiteSpace($reqRows)) {
    Write-Output "[M21-FIN][FAIL] cannot continue without request row"
    exit 1
}
$rf = ($reqRows -split "`n")[0] -split "`t"
$requestId = $rf[0].Trim(); $publicRequestId = $rf[1].Trim(); $routeAttemptId = $rf[2].Trim(); $billingPeriodId = $rf[3].Trim()
Write-Output "[M21-FIN] request_id=$requestId public=$publicRequestId route_attempt=$routeAttemptId period=$billingPeriodId"

# route attempt lineage (exact seeded profile)
$ra = (Invoke-Mysql "SELECT provider_connection_profile_id, routing_policy_id, provider_account_id, provider_model_id, pricing_version_id, status FROM gateway_route_attempt WHERE id=$routeAttemptId;") -split "`t"
Assert-That ($ra[0].Trim() -eq "$($state.connection_profile_id)") "route_attempt.provider_connection_profile_id equals seeded profile"
$attemptPricing = $ra[4].Trim()
Write-Output "[M21-FIN] route_attempt status=$($ra[5].Trim()) pricing_version=$attemptPricing"

# FINAL usage fact
$uf = $null
for ($i = 0; $i -lt 20; $i++) {
    $ufRaw = Invoke-Mysql "SELECT id, status, pricing_version_id FROM gateway_usage_fact WHERE org_id=$orgId AND request_id=$requestId AND status='FINAL' ORDER BY sequence DESC LIMIT 1;"
    if (-not [string]::IsNullOrWhiteSpace($ufRaw)) { $uf = ($ufRaw -split "`t"); break }
    Start-Sleep -Seconds 2
}
Assert-That ($null -ne $uf) "FINAL usage fact exists"
$usageFactId = ""
if ($null -ne $uf) {
    $usageFactId = $uf[0].Trim()
    Write-Output "[M21-FIN] usage_fact_id=$usageFactId status=$($uf[1].Trim()) pricing_version=$($uf[2].Trim())"
    Assert-That ($uf[2].Trim() -eq "$($state.pricing_version_id)") "usage_fact.pricing_version_id equals seeded active pricing version"
    $dims = Invoke-Mysql "SELECT dimension_code, quantity, provenance FROM gateway_usage_dimension WHERE usage_fact_id=$usageFactId ORDER BY dimension_code;"
    Write-Output "[M21-FIN] usage dimensions: $dims"
    Assert-That ($dims -match "INPUT_TOKEN`t5\.0+\s*`tPROVIDER_FINAL|INPUT_TOKEN\s+5\.0+\s+PROVIDER_FINAL") "INPUT_TOKEN=5 PROVIDER_FINAL present"
    Assert-That ($dims -match "OUTPUT_TOKEN`t3\.0+\s*`tPROVIDER_FINAL|OUTPUT_TOKEN\s+3\.0+\s+PROVIDER_FINAL") "OUTPUT_TOKEN=3 PROVIDER_FINAL present"
}

# Settled settlement
$settle = $null
for ($i = 0; $i -lt [math]::Ceiling($SettleTimeoutSeconds / 5); $i++) {
    $sRaw = Invoke-Mysql "SELECT id, status, calculated_amount_raw, posted_amount, rounding_delta, ledger_posting_id, reservation_id, billing_period_id, currency FROM gateway_settlement WHERE org_id=$orgId AND request_id=$requestId ORDER BY id DESC LIMIT 1;"
    if (-not [string]::IsNullOrWhiteSpace($sRaw)) {
        $sf = $sRaw -split "`t"
        if ($sf[1].Trim() -eq "SETTLED") { $settle = $sf; break }
    }
    Start-Sleep -Seconds 5
}
Assert-That ($null -ne $settle) "settlement reached SETTLED within ${SettleTimeoutSeconds}s"
if ($null -eq $settle) {
    Write-Output ("[M21-FIN][FAIL] settlement not SETTLED; last=" + (Invoke-Mysql "SELECT id,status,last_error_code FROM gateway_settlement WHERE org_id=$orgId AND request_id=$requestId;"))
    exit 1
}
$settlementId = $settle[0].Trim()
$calcRaw = $settle[2].Trim(); $postedAmount = $settle[3].Trim(); $roundingDelta = $settle[4].Trim()
$ledgerPostingId = $settle[5].Trim(); $reservationId = $settle[6].Trim(); $settlePeriod = $settle[7].Trim(); $currency = $settle[8].Trim()
Write-Output "[M21-FIN] settlement_id=$settlementId calculated=$calcRaw posted=$postedAmount delta=$roundingDelta ledger_posting=$ledgerPostingId reservation=$reservationId currency=$currency"
Assert-That ($postedAmount -ne "" -and [decimal]$postedAmount -gt 0) "posted_amount is a real positive cost (got $postedAmount)"
Assert-That ($settlePeriod -eq $billingPeriodId) "settlement.billing_period_id equals request.billing_period_id"

$settleCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM gateway_settlement WHERE org_id=$orgId AND request_id=$requestId;")
Assert-That ($settleCount -eq 1) "exactly one settlement for the request (got $settleCount)"

# Ledger posting + entry
$postCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_posting WHERE org_id=$orgId AND source_type='GATEWAY_SETTLEMENT' AND source_id=$settlementId;")
Assert-That ($postCount -eq 1) "exactly one GATEWAY_SETTLEMENT ledger posting (got $postCount)"
$postRow = (Invoke-Mysql "SELECT id, posting_key, status, posting_actor_type, posted_by_member_id FROM ledger_posting WHERE id=$ledgerPostingId;") -split "`t"
Assert-That ($postRow[0].Trim() -eq $ledgerPostingId) "settlement.ledger_posting_id points at the posting"
Assert-That ($postRow[3].Trim() -eq "SYSTEM" -and $postRow[4].Trim() -eq "NULL") "posting actor is SYSTEM with NULL member"
$entryCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_entry WHERE org_id=$orgId AND source_gateway_settlement_id=$settlementId;")
Assert-That ($entryCount -eq 1) "exactly one COST ledger entry for the settlement (got $entryCount)"
$entryRow = (Invoke-Mysql "SELECT id, entry_type, amount, currency FROM ledger_entry WHERE org_id=$orgId AND source_gateway_settlement_id=$settlementId LIMIT 1;") -split "`t"
$ledgerEntryId = $entryRow[0].Trim()
Write-Output "[M21-FIN] ledger_posting_id=$ledgerPostingId ledger_entry_id=$ledgerEntryId entry_type=$($entryRow[1].Trim()) entry_amount=$($entryRow[2].Trim())"
Assert-That ($entryRow[1].Trim() -eq "COST") "ledger entry type is COST"
Assert-That ([decimal]$entryRow[2].Trim() -eq [decimal]$postedAmount) "ledger entry amount equals posted_amount"
Assert-That ($entryRow[3].Trim() -eq $currency) "ledger entry currency equals settlement currency"

# Budget actual delta
$budgetAfter = Get-BudgetActuals "SELECT id, actual_amount FROM budget WHERE org_id=$orgId;"
$boundBudgetId = ""
$hasReservation = ($reservationId -ne "" -and $reservationId -ne "NULL")
if ($hasReservation) {
    $boundBudgetId = (Invoke-Mysql "SELECT budget_id FROM budget_reservation WHERE id=$reservationId;").Trim()
}
$deltaSum = [decimal]0
foreach ($k in $budgetAfter.Keys) {
    $before = if ($budgetBefore.ContainsKey($k)) { [decimal]$budgetBefore[$k] } else { [decimal]0 }
    $deltaSum += ([decimal]$budgetAfter[$k] - $before)
}
Write-Output "[M21-FIN] bound budget=$boundBudgetId budget actual delta total=$deltaSum"
Assert-That ($deltaSum -eq [decimal]$postedAmount) "Budget Actual increased by exactly posted_amount ($deltaSum vs $postedAmount)"
if ($hasReservation -and $budgetAfter.ContainsKey($boundBudgetId) -and $budgetBefore.ContainsKey($boundBudgetId)) {
    $boundDelta = [decimal]$budgetAfter[$boundBudgetId] - [decimal]$budgetBefore[$boundBudgetId]
    Assert-That ($boundDelta -eq [decimal]$postedAmount) "the bound reservation budget received the posted amount ($boundDelta)"
}
$resStatus = if ($hasReservation) { (Invoke-Mysql "SELECT status FROM budget_reservation WHERE id=$reservationId;").Trim() } else { "NO_RESERVATION" }
Assert-That (-not $hasReservation -or $resStatus -eq "FINALIZED") "reservation finalized (got $resStatus)"

$postCountNormal = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_posting WHERE org_id=$orgId;")

# =========================================================================
# DUPLICATE
# =========================================================================
Write-Output "[M21-FIN] === DUPLICATE scenario ==="
$dStats0 = (Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
$dResp = Send-Chat $rawKey $normalIdem
Write-Output "[M21-FIN] duplicate replay HTTP $($dResp.Status)"
$dProviderDelta = (Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions - $dStats0
Assert-That ($dProviderDelta -eq 0) "duplicate replay does not execute the Provider again (delta $dProviderDelta)"
Start-Sleep -Seconds 12
$dReqCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM gateway_request WHERE org_id=$orgId AND idempotency_key_digest=UNHEX('$digest');")
$dSettleCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM gateway_settlement WHERE org_id=$orgId AND request_id=$requestId;")
$dEntryCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_entry WHERE org_id=$orgId AND source_gateway_settlement_id=$settlementId;")
$dPostCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_posting WHERE org_id=$orgId;")
$dBudgetAfter = Get-BudgetActuals "SELECT id, actual_amount FROM budget WHERE org_id=$orgId;"
$dBoundActual = if ($dBudgetAfter.ContainsKey($boundBudgetId)) { [decimal]$dBudgetAfter[$boundBudgetId] } else { [decimal]0 }
$nBoundActual = if ($budgetAfter.ContainsKey($boundBudgetId)) { [decimal]$budgetAfter[$boundBudgetId] } else { [decimal]0 }
Assert-That ($dReqCount -eq 1) "still one gateway_request (got $dReqCount)"
Assert-That ($dSettleCount -eq 1) "still one settlement (got $dSettleCount)"
Assert-That ($dEntryCount -eq 1) "still one ledger entry (got $dEntryCount)"
Assert-That ($dPostCount -eq $postCountNormal) "no new ledger posting after replay ($dPostCount vs $postCountNormal)"
Assert-That ($dBoundActual -eq $nBoundActual) "no second Budget Actual mutation after replay ($dBoundActual vs $nBoundActual)"

# =========================================================================
# FAILURE (provider error must NOT be booked as zero cost)
# =========================================================================
Write-Output "[M21-FIN] === PROVIDER-FAILURE scenario ==="
Invoke-RestMethod -Method POST -Uri "$MockBase/admin/mode" -Body '{"mode":"http500"}' -ContentType "application/json" | Out-Null
$failIdem = "m21-fin-fail-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$fResp = Send-Chat $rawKey $failIdem
Write-Output "[M21-FIN] provider-failure request HTTP $($fResp.Status)"
Assert-That ($fResp.Status -ne 200) "provider failure does not return 200 (got $($fResp.Status))"
Start-Sleep -Seconds 12
$failDigest = Get-IdemDigestHex $failIdem
$fPostCount = [long](Invoke-Mysql "SELECT COUNT(*) FROM ledger_posting WHERE org_id=$orgId;")
Assert-That ($fPostCount -eq $postCountNormal) "provider failure adds no ledger posting ($fPostCount vs $postCountNormal)"
$fSettledZero = [long](Invoke-Mysql "SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=$orgId AND r.idempotency_key_digest=UNHEX('$failDigest') AND s.status='SETTLED';")
Assert-That ($fSettledZero -eq 0) "provider failure creates no SETTLED settlement (got $fSettledZero)"
$fBudgetAfter = Get-BudgetActuals "SELECT id, actual_amount FROM budget WHERE org_id=$orgId;"
$fBoundActual = if ($fBudgetAfter.ContainsKey($boundBudgetId)) { [decimal]$fBudgetAfter[$boundBudgetId] } else { [decimal]0 }
Assert-That ($fBoundActual -eq $nBoundActual) "provider failure does not mutate Budget Actual ($fBoundActual vs $nBoundActual)"
Invoke-RestMethod -Method POST -Uri "$MockBase/admin/mode" -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null

Write-Output ""
Write-Output "================ M21 FINANCIAL CLOSED-LOOP ================"
Write-Output ("chain: request=$requestId public=$publicRequestId route_attempt=$routeAttemptId usage_fact=$usageFactId")
Write-Output ("       settlement=$settlementId ledger_posting=$ledgerPostingId ledger_entry=$ledgerEntryId budget=$boundBudgetId")
Write-Output ("       posted_amount=$postedAmount currency=$currency reservation=$reservationId status=$resStatus")
Write-Output ("assertions failed: " + $failures.Count)
Write-Output "==========================================================="

if ($failures.Count -gt 0) {
    Write-Output ("[M21-FIN][RED] M21_FINANCIAL_SMOKE_FAIL: " + ($failures -join "; "))
    exit 1
}
Write-Output "[M21-FIN] M21_FINANCIAL_SMOKE_PASS"
exit 0
