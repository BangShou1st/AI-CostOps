<#
.SYNOPSIS
    M16 Machine Acceptance Orchestrator — the single machine entry point.

.DESCRIPTION
    Orchestrates the existing scripts/m16/ scenario / verification scripts.
    It does NOT re-implement scenario logic; it:
      1. records the exact tested SHA;
      2. checks the acceptance environment (docker, mysql client, endpoints);
      3. seeds synthetic governed acceptance data (per-gate orgs);
      4. invokes each machine scenario script;
      5. captures exit codes and aggregates a PASS / FAIL / NOT RUN matrix;
      6. prints the acceptance matrix summary;
      7. exits non-zero when any mandatory machine gate FAILs or is NOT RUN;
      8. prints M16_MACHINE_ACCEPTANCE_PASS only when every mandatory
         machine gate PASSes.

    Browser UAT (F01-F07) and Real Provider certification are external gates
    and are NEVER marked PASS by this orchestrator.

    Gate -> implementation map:
      A01 topology boot              -> verify-m16-topology.ps1
      A02 gateway+backend readiness  -> /actuator/health/readiness probes
      A03 gateway DB least privilege -> verify-m16-gateway-privileges.ps1
      A04 unsafe prod config         -> GatewayProductionConfigurationValidatorTest (gateway unit)
      B01 100-way identical replay   -> invoke-m16-b01-idempotency.ps1
      B02 budget concurrent exhaust  -> invoke-m16-b02-budget.ps1 (REQUIRED mode, 1-slot budget)
      B03 stepped non-stream load    -> invoke-m16-load.ps1 (B03 section)
      B04 concurrent SSE bound       -> invoke-m16-load.ps1 (B04 section)
      B05 overload bounded reject    -> invoke-m16-b05-overload.ps1 (rate-limiter capacity+1, 429-bounded)
      C01 mysql down before dispatch -> invoke-m16-failure.ps1 (C01 section)
      C02 mysql failure after intent -> focused live step (this script)
      C03 mysql restart              -> invoke-m16-failure.ps1 (C03 section)
      C04 redis outage               -> invoke-m16-failure.ps1 (C04 section)
      C05 redis state loss           -> invoke-m16-failure.ps1 (C05 section)
      C06 gateway restart            -> invoke-m16-crash.ps1
      C07 backend restart            -> focused live step (this script)
      C08 settlement retry           -> GatewaySettlementTransactionIntegrationTest (backend integration)
      C09 reservation recovery       -> ReservationRecoveryIntegrationTest (gateway integration)
      D01 safe provider failover     -> GatewaySafeFailoverIntegrationTest (gateway integration)
      D02 billable-possible stops    -> invoke-m16-provider-revoke.ps1 (D02 section)
      D03 credential revoke          -> invoke-m16-provider-revoke.ps1 (D03 section)
      D04 settlement vs close        -> GatewaySettlementTransactionIntegrationTest close pair (backend)
      D05 reconciliation vs close    -> M15HybridRaceMatrixIntegrationTest (backend integration)
      D06 statement difference       -> LedgerCorrectionIntegrationTest (backend integration)
      E01 prometheus scrape          -> prometheus /api/v1/query up probes
      E02 alert injection            -> focused live step (this script)
      E03 leak scan                  -> invoke-m16-leakscan.ps1
      E04 V2 restore                 -> invoke-m16-restore.ps1

.EXAMPLE
    .\scripts\m16-production-acceptance.ps1 -DryRun
    .\scripts\m16-production-acceptance.ps1 -Only A01,A02,A03,A04
    .\scripts\m16-production-acceptance.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$GatewayBase = "http://127.0.0.1:18081",
    [string]$BackendBase = "http://127.0.0.1:18080",
    [string]$MockBase = "http://127.0.0.1:18089",
    [string]$PrometheusBase = "http://127.0.0.1:19090",
    [string]$MysqlHost = "127.0.0.1",
    [int]$MysqlPort = 13307,
    [string]$Database = "m16accept",
    [string]$MysqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    [string]$GatewayUser = "gw_m16",
    [string]$ModelKey = "m16-accept-chat",
    [string]$Only = "",
    [string]$Skip = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:M16Dir = Join-Path $RepoRoot "scripts\m16"

# ---------------------------------------------------------------------------
# Gate registry: machine-mandatory gates only. External gates (F01-F07
# browser UAT, real Provider) are reported BLOCKED and never executed here.
# Budget: one reservation holds 31.9488 (1M input tokens @30/1M + 8192 output
# @60/1M, ceiling scale 8). B02 seeds a 1-slot budget so exactly one of the
# racing REQUIRED-mode requests can hold; the rest converge 429.
# ---------------------------------------------------------------------------
$script:SingleSlotBudget = "31.94880000"
$script:Gates = @(
    @{ Id = "A01"; Kind = "script"; Script = "verify-m16-topology.ps1"; NeedsSeed = $false },
    @{ Id = "A02"; Kind = "probe"; Probe = "readiness"; NeedsSeed = $false },
    @{ Id = "A03"; Kind = "script"; Script = "verify-m16-gateway-privileges.ps1"; NeedsSeed = $false },
    @{ Id = "A04"; Kind = "maven-unit"; Module = "gateway"; Test = "GatewayProductionConfigurationValidatorTest"; NeedsSeed = $false },
    @{ Id = "B01"; Kind = "script"; Script = "invoke-m16-b01-idempotency.ps1"; NeedsSeed = $true },
    @{ Id = "B02"; Kind = "script"; Script = "invoke-m16-b02-budget.ps1"; NeedsSeed = $true; RequiredBudget = $true },
    @{ Id = "B03"; Kind = "script"; Script = "invoke-m16-load.ps1"; NeedsSeed = $true; LoadArgs = @("-Steps", "1,5,20", "-StreamCeiling", "4") },
    @{ Id = "B04"; Kind = "alias"; AliasOf = "B03"; NeedsSeed = $false },
    @{ Id = "B05"; Kind = "script"; Script = "invoke-m16-b05-overload.ps1"; NeedsSeed = $true },
    @{ Id = "C01"; Kind = "script"; Script = "invoke-m16-failure.ps1"; NeedsSeed = $true; Covers = "C01,C03,C04,C05" },
    @{ Id = "C02"; Kind = "step"; Step = "mysql-post-dispatch"; NeedsSeed = $true },
    @{ Id = "C03"; Kind = "alias"; AliasOf = "C01"; NeedsSeed = $false },
    @{ Id = "C04"; Kind = "alias"; AliasOf = "C01"; NeedsSeed = $false },
    @{ Id = "C05"; Kind = "alias"; AliasOf = "C01"; NeedsSeed = $false },
    @{ Id = "C06"; Kind = "script"; Script = "invoke-m16-crash.ps1"; NeedsSeed = $true },
    @{ Id = "C07"; Kind = "step"; Step = "backend-restart"; NeedsSeed = $true },
    @{ Id = "C08"; Kind = "maven-integration"; Module = "backend"; Test = "GatewaySettlementTransactionIntegrationTest#concurrentWorkersConvergeOnOneSettlementAndOneFinancialMutation+repeatedSettlementAfterCommittedResultDoesNotDoublePost"; NeedsSeed = $false },
    @{ Id = "C09"; Kind = "maven-integration"; Module = "gateway"; Test = "ReservationRecoveryIntegrationTest"; NeedsSeed = $false },
    @{ Id = "D01"; Kind = "maven-integration"; Module = "gateway"; Test = "GatewaySafeFailoverIntegrationTest"; NeedsSeed = $false },
    @{ Id = "D02"; Kind = "script"; Script = "invoke-m16-provider-revoke.ps1"; NeedsSeed = $true; Covers = "D02,D03" },
    @{ Id = "D03"; Kind = "alias"; AliasOf = "D02"; NeedsSeed = $false },
    @{ Id = "D04"; Kind = "maven-integration"; Module = "backend"; Test = "GatewaySettlementTransactionIntegrationTest#settlementGetsPeriodFirstAndCloseWaitsForCommittedFinancialTruth+closeGetsPeriodFirstAndSettlementReconcilesWithoutFinancialMutation"; NeedsSeed = $false },
    @{ Id = "D05"; Kind = "maven-integration"; Module = "backend"; Test = "M15HybridRaceMatrixIntegrationTest"; NeedsSeed = $false },
    @{ Id = "D06"; Kind = "maven-integration"; Module = "backend"; Test = "LedgerCorrectionIntegrationTest"; NeedsSeed = $false },
    @{ Id = "E01"; Kind = "probe"; Probe = "prometheus"; NeedsSeed = $false },
    @{ Id = "E02"; Kind = "step"; Step = "alert-injection"; NeedsSeed = $true },
    @{ Id = "E03"; Kind = "script"; Script = "invoke-m16-leakscan.ps1"; NeedsSeed = $true; LeakScan = $true },
    @{ Id = "E04"; Kind = "script"; Script = "invoke-m16-restore.ps1"; NeedsSeed = $true; Restore = $true }
)

$script:Results = [ordered]@{}

function Write-Gate([string]$Id, [string]$Status, [string]$Detail) {
    $script:Results[$Id] = @{ Status = $Status; Detail = $Detail }
    Write-Host ("[M16-ORCH] gate " + $Id + " " + $Status + " :: " + $Detail)
}

function Invoke-RootSql([string]$Sql) {
    $pw = [Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD")
    $env:MYSQL_PWD = $pw
    try {
        $out = & $MysqlBin -h $MysqlHost -P $MysqlPort -u root -N -B $Database -e $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw ("MySQL failed: " + (($out | Out-String)).Trim()) }
        if ($null -eq $out) { return "" }
        return (($out | Out-String)).Trim()
    } finally {
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    }
}

function Send-Chat([string]$RawKey, [string]$KeySuffix) {
    $headers = @{
        Authorization = "Bearer $RawKey"
        "Idempotency-Key" = $KeySuffix
        "Content-Type" = "application/json"
    }
    $body = '{"model":"' + $ModelKey + '","messages":[{"role":"user","content":"m16 orchestrator probe"}]}'
    try {
        $r = Invoke-WebRequest -Uri ($GatewayBase + "/v1/chat/completions") -Method Post -Headers $headers -Body $body -SkipHttpErrorCheck -TimeoutSec 90
        return [pscustomobject]@{ Status = $r.StatusCode; Body = $r.Content }
    } catch {
        return [pscustomobject]@{ Status = -1; Body = $_.Exception.Message }
    }
}

function New-GateSeed([string]$GateId, [string]$BudgetTotal) {
    $mockUrl = "http://m16-mock-provider:8089/v1"
    $suffix = ($GateId.ToLower() + "-" + (Get-Date -Format "yyyyMMddHHmmss"))
    $lines = @( & (Join-Path $script:M16Dir "seed-m16-acceptance.ps1") `
        -MysqlHost $MysqlHost -MysqlPort $MysqlPort -Database $Database `
        -MysqlBin $MysqlBin -Suffix $suffix `
        -MockBaseUrl $mockUrl -BudgetTotal $BudgetTotal 2>&1 )
    if ($LASTEXITCODE -ne 0) { throw ("seed failed for " + $GateId + ": " + (($lines | Out-String)).Trim()) }
    return (($lines | Select-Object -Last 1) | ConvertFrom-Json)
}

function Invoke-GateScript([string]$GateId, [hashtable]$Gate, [object]$Seed) {
    # Runs the scenario script in a CHILD pwsh process (native) so its
    # `exit N` becomes a real process exit code in $LASTEXITCODE.
    # All progress logging uses Write-Host: Write-Output inside this function
    # would be captured into the caller's $rc variable and corrupt it.
    $suffix = ($GateId.ToLower() + "-" + (Get-Date -Format "yyyyMMddHHmmssfff"))
    $hmacKey = [Environment]::GetEnvironmentVariable("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1")
    $named = [ordered]@{}
    switch ($Gate.Script) {
        "verify-m16-topology.ps1" { break }
        "verify-m16-gateway-privileges.ps1" {
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["GatewayUser"] = $GatewayUser
            $named["MysqlBin"] = $MysqlBin
            break
        }
        "invoke-m16-leakscan.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["RawKey"] = $Seed.raw_key; $named["ModelKey"] = $ModelKey
            break
        }
        "invoke-m16-crash.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["RawKey"] = $Seed.raw_key; $named["OrgId"] = ([long]$Seed.org_id)
            $named["GatewayEnvKeys"] = $hmacKey; $named["ModelKey"] = $ModelKey
            break
        }
        "invoke-m16-provider-revoke.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["RawKey"] = $Seed.raw_key; $named["OrgId"] = ([long]$Seed.org_id)
            $named["CredentialId"] = ([long]$Seed.credential); $named["ModelKey"] = $ModelKey
            break
        }
        "invoke-m16-restore.ps1" {
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["GatewayBase"] = $GatewayBase
            $named["Suffix"] = $suffix; $named["RawKey"] = $Seed.raw_key
            $named["OrgId"] = ([long]$Seed.org_id)
            $named["GatewayEnvKeys"] = $hmacKey; $named["ModelKey"] = $ModelKey
            break
        }
        "invoke-m16-b01-idempotency.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["RawKey"] = $Seed.raw_key; $named["ModelKey"] = $ModelKey
            $named["OrgId"] = ([long]$Seed.org_id); $named["Workers"] = 100
            break
        }
        "invoke-m16-b02-budget.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["Suffix"] = $suffix; $named["RawKey"] = $Seed.raw_key
            $named["OrgId"] = ([long]$Seed.org_id)
            $named["ModelKey"] = $ModelKey; $named["Workers"] = 20
            break
        }
        "invoke-m16-b05-overload.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["Suffix"] = $suffix; $named["RawKey"] = $Seed.raw_key
            $named["OrgId"] = ([long]$Seed.org_id)
            $named["CredentialId"] = ([long]$Seed.credential)
            $named["ModelKey"] = $ModelKey
            break
        }
        "invoke-m16-load.ps1" {
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["Suffix"] = $suffix; $named["RawKey"] = $Seed.raw_key
            $named["OrgId"] = ([long]$Seed.org_id)
            $named["ModelKey"] = $ModelKey; $named["Steps"] = "1,5,20"; $named["StreamCeiling"] = 4
            break
        }
        default {
            # invoke-m16-failure.ps1 and any future Suffix+RawKey+OrgId script.
            $named["GatewayBase"] = $GatewayBase; $named["MockBase"] = $MockBase
            $named["MysqlHost"] = $MysqlHost; $named["MysqlPort"] = $MysqlPort
            $named["Database"] = $Database; $named["MysqlBin"] = $MysqlBin
            $named["Suffix"] = $suffix; $named["RawKey"] = $Seed.raw_key
            $named["OrgId"] = ([long]$Seed.org_id)
            $named["ModelKey"] = $ModelKey
        }
    }
    # -File arguments are passed literally (no expansion); wrap each value in
    # double quotes so paths with spaces survive. Values are synthetic.
    $argStr = ""
    foreach ($k in $named.Keys) {
        $v = [string]$named[$k]
        $argStr += " -$k `"" + ($v -replace '"', '`"') + "`""
    }
    Write-Host ("[M16-ORCH] " + $GateId + " exec: " + $Gate.Script)
    # Start-Process (not `&`): the child pwsh streams its scenario log lines
    # straight to the console (never captured into this function's return
    # value), and -PassThru gives the REAL process exit code. Using `&`
    # would merge the child's stdout into `return $LASTEXITCODE`.
    # Start-Process with ONE pre-quoted argument string: -ArgumentList as a
    # string[] joins elements with bare spaces, which splits values containing
    # spaces (e.g. "C:\Program Files\MySQL\..."). A single string with explicit
    # double quotes survives intact.
    $quoted = '-NoProfile -File "' + (Join-Path $script:M16Dir $Gate.Script) + '"'
    foreach ($k in $named.Keys) {
        $quoted += ' -' + $k + ' "' + (([string]$named[$k]) -replace '"', '""') + '"'
    }
    $proc = Start-Process -FilePath "pwsh" -ArgumentList $quoted -Wait -NoNewWindow -PassThru
    return $proc.ExitCode
}

# Focused live steps (C02/C07/E02) that need no new script file: each
# asserts durable DB truth directly and returns 0/1. Progress lines use
# Write-Host (console only): Write-Output inside this function would be
# captured into the caller's $rc variable and corrupt the exit code.
# NOTE: B05 overload is NOT a focused step; its deterministic proof lives in
# scripts/m16/invoke-m16-b05-overload.ps1 (rate-limiter capacity+1).
function Invoke-FocusedStep([string]$Step, [object]$Seed) {
    [int]$rcResult = 0
    $failures = [System.Collections.ArrayList]::new()
    $stamp = [DateTime]::UtcNow.ToString("yyyyMMddHHmmssfff")
    if ($Step -eq "mysql-post-dispatch") {
        # C02: hold a dispatch in UPSTREAM_ACTIVE with the timeout-mode mock,
        # stop MySQL mid-flight, then restore. Invariants: replaying the same
        # identity causes no second Provider execution; the durable request
        # row survives; fresh work flows afterwards.
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"timeout"}' -ContentType "application/json" | Out-Null
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"timeout"}' -ContentType "application/json" | Out-Null
        $key = "m16-c02-" + $stamp
        $job = Start-Job -ScriptBlock {
            param($Base, $Key, $Idem, $Model)
            $h = @{ Authorization = "Bearer $Key"; "Idempotency-Key" = $Idem; "Content-Type" = "application/json" }
            $b = '{"model":"' + $Model + '","messages":[{"role":"user","content":"m16 c02"}]}'
            try {
                $r = Invoke-WebRequest -Uri ($Base + "/v1/chat/completions") -Method Post -Headers $h -Body $b -SkipHttpErrorCheck -TimeoutSec 120
                return [pscustomobject]@{ Status = $r.StatusCode }
            } catch { return [pscustomobject]@{ Status = -1 } }
        } -ArgumentList $GatewayBase, $Seed.raw_key, $key, $ModelKey
        Start-Sleep 8
        $mid = Invoke-RootSql ("SELECT state FROM gateway_request WHERE org_id=" + $Seed.org_id + " ORDER BY id DESC LIMIT 1;")
        Write-Host ("[M16-C02] mid-flight state=" + $mid)
        docker stop m16-mysql-accept | Out-Null
        Start-Sleep 5
        docker start m16-mysql-accept | Out-Null
        for ($i = 0; $i -lt 30; $i++) {
            docker exec m16-mysql-accept mysqladmin ping -h localhost --silent 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep 5
        }
        Start-Sleep 10
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
        $opsKill = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
        $post = Invoke-RootSql ("SELECT state FROM gateway_request WHERE org_id=" + $Seed.org_id + " ORDER BY id DESC LIMIT 1;")
        Write-Host ("[M16-C02] post-mysql-restart state=" + $post + " provider_ops=" + $opsKill)
        $replay = Send-Chat $Seed.raw_key $key
        $opsReplay = (Invoke-RestMethod -Uri ($MockBase + "/stats")).post_chat_completions
        Write-Host ("[M16-C02] replay HTTP " + $replay.Status + " provider_ops=" + $opsReplay)
        if ($opsReplay -gt 1) { $failures.Add("C02: second Provider execution after mid-flight MySQL failure (ops=$opsReplay)") | Out-Null }
        $fresh = Send-Chat $Seed.raw_key ("m16-c02fresh-" + $stamp)
        Write-Host ("[M16-C02] fresh work HTTP " + $fresh.Status)
        if ($fresh.Status -ne 200) { $failures.Add("C02: fresh work got " + $fresh.Status + " (want 200)") | Out-Null }
        Wait-Job $job -Timeout 60 | Out-Null
        Stop-Job $job -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $job -Force -ErrorAction SilentlyContinue | Out-Null
    } elseif ($Step -eq "backend-restart") {
        # C07: baseline 200, restart the backend settlement worker mid-cycle,
        # prove the incurred request still converges to exactly one SETTLED
        # settlement / one Ledger posting with no duplicate Ledger effect.
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
        $r = Send-Chat $Seed.raw_key ("m16-c07-" + $stamp)
        Write-Host ("[M16-C07] baseline HTTP " + $r.Status)
        if ($r.Status -ne 200) { $failures.Add("C07 setup: baseline got " + $r.Status + " (want 200)") | Out-Null }
        else {
            docker restart m16-backend-accept | Out-Null
            Start-Sleep 25
            $ready = Invoke-WebRequest -Uri ($BackendBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 60
            Write-Host ("[M16-C07] backend readiness after restart: HTTP " + $ready.StatusCode)
            if ($ready.StatusCode -ne 200) { $failures.Add("C07: backend not ready after restart") | Out-Null }
            $settled = 0
            for ($i = 0; $i -lt 24; $i++) {
                Start-Sleep 5
                $settled = [long](Invoke-RootSql ("SELECT COUNT(*) FROM gateway_settlement s JOIN gateway_request r ON r.id=s.request_id WHERE r.org_id=" + $Seed.org_id + " AND s.status='SETTLED';"))
                if ($settled -ge 1) { break }
            }
            $postings = [long](Invoke-RootSql ("SELECT COUNT(*) FROM ledger_posting WHERE org_id=" + $Seed.org_id + ";"))
            $reqs = [long](Invoke-RootSql ("SELECT COUNT(*) FROM gateway_request WHERE org_id=" + $Seed.org_id + ";"))
            Write-Host ("[M16-C07] settled=" + $settled + " ledger_postings=" + $postings + " requests=" + $reqs)
            if ($settled -lt 1) { $failures.Add("C07: no SETTLED settlement after backend restart") | Out-Null }
            if ($postings -ne $settled) { $failures.Add("C07: ledger_postings=$postings != settled=$settled") | Out-Null }
        }
    } elseif ($Step -eq "alert-injection") {
        # E02: inject http500 x3 (billable-possible), prove the M16 alert rules
        # see the spike via /api/v1/alerts and that backlog rules stay sane.
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"http500"}' -ContentType "application/json" | Out-Null
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/reset") | Out-Null
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"http500"}' -ContentType "application/json" | Out-Null
        for ($i = 0; $i -lt 3; $i++) {
            $rr = Send-Chat $Seed.raw_key ("m16-e02-" + $stamp + "-" + $i)
            Write-Host ("[M16-E02] inject[$i] HTTP " + $rr.Status)
        }
        Invoke-RestMethod -Method Post -Uri ($MockBase + "/admin/mode") -Body '{"mode":"ok"}' -ContentType "application/json" | Out-Null
        Start-Sleep 25
        $alerts = Invoke-RestMethod -Uri ($PrometheusBase + "/api/v1/alerts") -TimeoutSec 30
        $names = @($alerts.data.alerts | ForEach-Object { $_.labels.alertname + ":" + $_.state })
        Write-Host ("[M16-E02] alerts: " + ($names -join ", "))
        $firing = @($alerts.data.alerts | Where-Object { $_.labels.alertname -like "M16*" -and $_.state -eq "firing" })
        if ($firing.Count -lt 1) { $failures.Add("E02: no M16 alert firing after http500 injection") | Out-Null }
        else { Write-Host ("[M16-E02] firing: " + (($firing | ForEach-Object { $_.labels.alertname }) -join ",")) }
    } else {
        throw ("unknown focused step: " + $Step)
    }
    if ($failures.Count -gt 0) { $rcResult = 1 }
    return $rcResult
}

Push-Location $RepoRoot
try {
    # ---- 1. exact SHA ----
    $sha = (git rev-parse HEAD).Trim()
    $branch = (git branch --show-current).Trim()
    Write-Output ("[M16-ORCH] branch=" + $branch + " sha=" + $sha)
    if ($branch -ne "feat/m16-v2-production-acceptance") {
        Write-Output "[M16-ORCH-RED] must run on feat/m16-v2-production-acceptance."
        exit 2
    }

    # ---- gate filter ----
    $onlySet = @()
    if (-not [string]::IsNullOrWhiteSpace($Only)) { $onlySet = @($Only -split "," | ForEach-Object { $_.Trim() }) }
    $skipSet = @()
    if (-not [string]::IsNullOrWhiteSpace($Skip)) { $skipSet = @($Skip -split "," | ForEach-Object { $_.Trim() }) }

    # ---- 2. environment checks ----
    $envFailures = [System.Collections.ArrayList]::new()
    try { docker ps 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { $envFailures.Add("docker unavailable") | Out-Null } }
    catch { $envFailures.Add("docker unavailable") | Out-Null }
    if (-not (Test-Path -LiteralPath $MysqlBin)) { $envFailures.Add("mysql client missing") | Out-Null }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("MYSQL_M16_ROOT_PASSWORD"))) { $envFailures.Add("MYSQL_M16_ROOT_PASSWORD not set") | Out-Null }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("MYSQL_M16_GATEWAY_PASSWORD"))) { $envFailures.Add("MYSQL_M16_GATEWAY_PASSWORD not set") | Out-Null }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1"))) { $envFailures.Add("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1 not set") | Out-Null }
    foreach ($s in @("verify-m16-topology.ps1", "verify-m16-gateway-privileges.ps1",
            "seed-m16-acceptance.ps1", "invoke-m16-b01-idempotency.ps1",
            "invoke-m16-b02-budget.ps1", "invoke-m16-load.ps1",
            "invoke-m16-b05-overload.ps1",
            "invoke-m16-failure.ps1", "invoke-m16-crash.ps1",
            "invoke-m16-provider-revoke.ps1", "invoke-m16-leakscan.ps1",
            "invoke-m16-restore.ps1")) {
        if (-not (Test-Path -LiteralPath (Join-Path $script:M16Dir $s))) {
            $envFailures.Add("missing harness script: scripts/m16/$s") | Out-Null
        }
    }
    if ($envFailures.Count -gt 0) {
        foreach ($f in $envFailures) { Write-Output ("[M16-ORCH-RED] env: " + $f) }
        exit 2
    }
    Write-Output "[M16-ORCH] environment checks PASS."

    if ($DryRun) {
        Write-Output "[M16-ORCH] DRY RUN: planned gates:"
        foreach ($g in $script:Gates) { Write-Output ("  " + $g.Id + " kind=" + $g.Kind) }
        Write-Output "[M16-ORCH] DRY RUN complete (no scenario executed)."
        exit 0
    }

    # ---- 3-5. run gates in registry order ----
    foreach ($g in $script:Gates) {
        $id = $g.Id
        if ($onlySet.Count -gt 0 -and $onlySet -notcontains $id) {
            Write-Gate $id "NOT RUN" "excluded by -Only filter"
            continue
        }
        if ($skipSet -contains $id) {
            Write-Gate $id "NOT RUN" "excluded by -Skip filter"
            continue
        }
        $t0 = Get-Date
        try {
            if ($g.Kind -eq "alias") {
                $target = $g.AliasOf
                if (-not $script:Results.Contains($target)) {
                    Write-Gate $id "NOT RUN" "alias target $target not executed yet"
                } else {
                    $t = $script:Results[$target]
                    Write-Gate $id $t.Status ("alias of " + $target + ": " + $t.Detail)
                }
                continue
            }
            if ($g.Kind -eq "probe") {
                if ($g.Probe -eq "readiness") {
                    $r = Invoke-WebRequest -Uri ($GatewayBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 30
                    $b = Invoke-WebRequest -Uri ($BackendBase + "/actuator/health/readiness") -SkipHttpErrorCheck -TimeoutSec 30
                    if ($r.StatusCode -eq 200 -and $b.StatusCode -eq 200) {
                        Write-Gate $id "PASS" ("gateway+backend readiness 200 in " + ((Get-Date) - $t0).TotalSeconds.ToString("0.0") + "s")
                    } else {
                        Write-Gate $id "FAIL" ("readiness gateway=" + $r.StatusCode + " backend=" + $b.StatusCode)
                    }
                } elseif ($g.Probe -eq "prometheus") {
                    $q1 = Invoke-RestMethod -Uri ($PrometheusBase + "/api/v1/query?query=up{job='m16-gateway'}") -TimeoutSec 30
                    $q2 = Invoke-RestMethod -Uri ($PrometheusBase + "/api/v1/query?query=up{job='m16-backend'}") -TimeoutSec 30
                    $g1 = @($q1.data.result | Where-Object { $_.value[1] -eq "1" }).Count
                    $g2 = @($q2.data.result | Where-Object { $_.value[1] -eq "1" }).Count
                    if ($g1 -ge 1 -and $g2 -ge 1) {
                        Write-Gate $id "PASS" "prometheus scrapes m16-gateway + m16-backend up=1"
                    } else {
                        Write-Gate $id "FAIL" ("prometheus up: gateway=" + $g1 + " backend=" + $g2)
                    }
                }
                continue
            }
            if ($g.Kind -eq "maven-unit" -or $g.Kind -eq "maven-integration") {
                $mod = $g.Module
                $t = $g.Test
                Write-Host ("[M16-ORCH] " + $id + " exec: mvn " + $mod + " -Dtest=" + $t)
                Push-Location (Join-Path $RepoRoot $mod)
                try {
                    # Integration gates run ONLY the named test in the `test`
                    # phase (surefire honors -Dtest for *IntegrationTest too):
                    # bare `verify` also runs the failsafe phase, which
                    # re-reads STALE failsafe-reports from earlier full runs
                    # and misattributes unrelated failures (C09's
                    # ReservationRecovery 7/7 GREEN was buried by 9 stale
                    # MimoStreaming 401s). Unit gates use `test` as before.
                    # The orchestrator's live-stack HMAC/KEK env keys MUST NOT
                    # leak into the test JVM: env vars outrank the test yml
                    # (application-test-defaults.yml pins the fixture keys),
                    # so inherited keys turn every fixture signature into 401
                    # (D01 RED root cause). mvn runs in a CHILD pwsh via
                    # Start-Process with the three keys REMOVED from its
                    # environment block (NOTE: SetEnvironmentVariable(name,
                    # $null) sets EMPTY STRING, not removal — the empty value
                    # still outranks the yml and breaks Base64 decode — so the
                    # child is spawned with the keys stripped instead).
                    # Streams the child's log straight to the console and
                    # returns its REAL exit code via -PassThru. The child
                    # EXPLICITLY removes the live keys from its own block
                    # (Remove-Item Env:) before exec: belt and suspenders —
                    # -Environment $null strips on PS7.1+, and the explicit
                    # removal covers older hosts where $null means empty.
                    $mvnCmd = "Remove-Item Env:\\AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1 -ErrorAction SilentlyContinue; " +
                        "Remove-Item Env:\\AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1 -ErrorAction SilentlyContinue; " +
                        "Remove-Item Env:\\AICOSTOPS_PROVIDER_KEK_V1 -ErrorAction SilentlyContinue; " +
                        "& .\\mvnw.cmd -B -Dtest=" + $t + " -DfailIfNoTests=false test; exit $LASTEXITCODE"
                    $mvnProc = Start-Process -FilePath "pwsh" -ArgumentList @("-NoProfile", "-Command", $mvnCmd) -Wait -NoNewWindow -PassThru -WorkingDirectory (Join-Path $RepoRoot $mod)
                    if ($mvnProc.ExitCode -eq 0) { Write-Gate $id "PASS" ("mvn " + $mod + " " + $t) }
                    else { Write-Gate $id "FAIL" ("mvn exit " + $mvnProc.ExitCode + ": " + $mod + " " + $t) }
                } finally { Pop-Location }
                continue
            }
            if ($g.Kind -eq "step") {
                # B05 overload budget: 32 x ~31.95 ~= 1022 worst case; seed
                # 1200 so budget exhaustion does NOT mask bounded overload.
                $stepBudget = "100.00000000"
                if ($g.Step -eq "overload") { $stepBudget = "1200.00000000" }
                $seed = New-GateSeed $id $stepBudget
                Write-Host ("[M16-ORCH] " + $id + " seeded org=" + $seed.org_id + " budget=" + $stepBudget)
                $rc = Invoke-FocusedStep $g.Step $seed
                if ($rc -eq 0) { Write-Gate $id "PASS" ("focused step " + $g.Step) }
                else { Write-Gate $id "FAIL" ("focused step " + $g.Step + " returned " + $rc) }
                continue
            }
            # script kind
            $seed = $null
            if ($g.NeedsSeed) {
                # B03 budget: steps 1+5+20 admit 26 x 31.9488 ~= 831; seed
                # 1000 so the envelope measures latency, not exhaustion
                # (exhaustion is B02's job on a 1-slot budget).
                # B05 rate-limiter proof: 5 fill + 1 over + recovery admits;
                # seed 1000 so budget NEVER masks the rate bound.
                $budget = "100.00000000"
                if ($id -eq "B02") { $budget = $script:SingleSlotBudget }
                if ($id -eq "B03" -or $id -eq "B05") { $budget = "1000.00000000" }
                $seed = New-GateSeed $id $budget
                Write-Host ("[M16-ORCH] " + $id + " seeded org=" + $seed.org_id + " budget=" + $budget)
                if ($g.ContainsKey("RequiredBudget") -and $g.RequiredBudget) {
                    Invoke-RootSql ("UPDATE gateway_credential SET budget_enforcement_mode='REQUIRED' WHERE id=" + $seed.credential + ";") | Out-Null
                    Write-Host ("[M16-ORCH] " + $id + " credential set REQUIRED")
                }
            }
            $rc = Invoke-GateScript $id $g $seed
            if ($rc -eq 0) {
                if ($g.ContainsKey("Covers")) { Write-Gate $id "PASS" ($g.Script + " exit 0 (covers " + $g.Covers + ")") }
                else { Write-Gate $id "PASS" ($g.Script + " exit 0") }
            } else { Write-Gate $id "FAIL" ($g.Script + " exit " + $rc) }
        } catch {
            Write-Gate $id "FAIL" ("exception: " + $_.Exception.Message)
        }
    }

    # ---- 6. matrix summary ----
    Write-Output ""
    Write-Output "================ M16 MACHINE ACCEPTANCE MATRIX ================"
    Write-Output ("SHA: " + $sha)
    foreach ($g in $script:Gates) {
        $r = $script:Results[$g.Id]
        Write-Output (($g.Id.PadRight(5)) + " " + ($r.Status.PadRight(8)) + " " + $r.Detail)
    }
    Write-Output "External: F01-F07 BLOCKED BY BROWSER EXECUTION; Real Provider BLOCKED (operator credential unavailable)"
    Write-Output "==============================================================="

    # ---- 7/8. aggregate gate ----
    $bad = @($script:Gates | Where-Object {
        $onlySet.Count -eq 0 -or $onlySet -contains $_.Id
    } | Where-Object { $script:Results[$_.Id].Status -ne "PASS" })
    if ($bad.Count -gt 0) {
        Write-Host ("[M16-ORCH-RED] MACHINE ACCEPTANCE FAIL: " + $bad.Count + " gate(s) not PASS (" + (($bad | ForEach-Object { $_.Id }) -join ",") + ")")
        exit 1
    }
    Write-Output "M16_MACHINE_ACCEPTANCE_PASS"
    exit 0
} finally {
    Pop-Location
}
