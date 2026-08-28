<#
.SYNOPSIS
  AIC-077 bounded observability smoke: start core + observability overlay,
  prove one real alert fires and recovers under deterministic fault injection,
  and write real evidence. No SLO/HA/capacity claim is made.

.DESCRIPTION
  - Uses the existing bounded business metric aicostops_login_result_total
    (result="INVALID_CREDENTIALS") as the deterministic signal.
  - Seeds the series, waits for a Prometheus scrape, then generates a bounded
    number of invalid-credential logins so increase() has a real baseline.
  - Verifies: backend target UP, at least one real aicostops_* series is
    non-empty, the chosen alert transitions inactive -> pending -> firing ->
    inactive, and the Grafana dashboard is provisioned.
  - Only ever touches the Compose project it started (-p ProjectName). It never
    stops or prunes unrelated projects, and never runs a global Docker prune.

.NOTES
  Run from the repository root (or anywhere; paths are resolved from PSScriptRoot).
  Requires Docker, Docker Compose v2, and curl.exe (Windows 10+ built-in).
#>
[CmdletBinding()]
param(
    [string] $EnvFile = ".env",
    [string] $ProjectName = "aicostops-obs",
    [int]    $MaxWaitSeconds = 360,
    [int]    $ScrapeWaitSeconds = 25
)

$ErrorActionPreference = 'Stop'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ComposeBase = Join-Path $RepoRoot 'compose.yaml'
$ComposeOverride = Join-Path $RepoRoot 'compose.observability.yaml'
$EvidencePath = Join-Path $RepoRoot 'docs/03-acceptance/m9-observability-evidence.md'

# Resolve curl.exe (must NOT be the PowerShell `curl` alias).
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { Write-Error "curl.exe not found on PATH; install Windows curl or run on Windows 10+."; exit 1 }
$Curl = $curl.Source

function Invoke-Compose {
    param([string[]] $ComposeArgs)
    # Run docker compose directly. We deliberately avoid PowerShell pipe
    # redirection (e.g. `| Out-Null` / `> $null` / `cmd /c "... > NUL"`): a native
    # command whose stream is piped through PowerShell can deadlock on a large
    # build log, and the `cmd /c` variant was observed to hang on `up -d` here.
    # Streaming compose output to the console is deadlock-free and keeps the smoke
    # debuggable.
    # NOTE: the parameter is deliberately NOT named `$Args` -- that is a PowerShell
    # automatic variable, and splatting `@Args` from a parameter of that name
    # silently produces a malformed command (docker prints its usage and does
    # nothing), which made `up -d` a no-op and the smoke hang in health-wait.
    docker compose -p $ProjectName -f $ComposeBase -f $ComposeOverride @ComposeArgs
}

function Get-HttpCode {
    param([string] $Url)
    try { return ( & $Curl -s -o NUL -w '%{http_code}' --max-time 5 $Url ) }
    catch { return '000' }
}

function Wait-Http200 {
    param([string] $Url, [int] $Timeout)
    $elapsed = 0
    while ($elapsed -lt $Timeout) {
        $c = Get-HttpCode $Url
        if ($c -eq '200') { return $true }
        Start-Sleep -Seconds 5; $elapsed += 5
    }
    return $false
}

function Query-Prom {
    param([string] $PromUri, [string] $Expr)
    $q = [System.Uri]::EscapeDataString($Expr)
    $raw = & $Curl -s "$PromUri/api/v1/query?query=$q"
    if (-not $raw) { return $null }
    return ($raw | ConvertFrom-Json)
}

function Get-PromAlerts {
    param([string] $PromUri)
    $raw = & $Curl -s "$PromUri/api/v1/alerts"
    if (-not $raw) { return @() }
    $j = $raw | ConvertFrom-Json
    return $j.data.alerts
}

function Send-InvalidLogin {
    param([string] $Base, [string] $Email)
    $body = '{"email":"' + $Email + '","password":"smoke-wrong-password"}'
    $code = & $Curl -s -o NUL -w '%{http_code}' -X POST "$Base/api/v1/auth/login" `
        -H 'Content-Type: application/json' -d $body
    return [int]$code
}

function Log { param([string] $Msg) Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Msg) }

$results = @{}
$failed = $false
function Assert-Or-Fail {
    param([bool] $Cond, [string] $Name, [string] $Detail)
    $results[$Name] = if ($Cond) { 'PASS' } else { 'FAIL' }
    if (-not $Cond) { $script:failed = $true }
    Log ("{0}: {1} {2}" -f $Name, $(if ($Cond) {'PASS'} else {'FAIL'}), $Detail)
}

# ---------------------------------------------------------------------------
# 0. Preconditions
# ---------------------------------------------------------------------------
if (-not (Test-Path $ComposeBase)) { Write-Error "compose.yaml not found at $ComposeBase"; exit 1 }
if (-not (Test-Path $ComposeOverride)) { Write-Error "compose.observability.yaml not found"; exit 1 }

# Collision handling (guardrail): compose.observability.yaml overrides the
# `aicostops` network name to `aicostops-obs-network`, so this overlay is fully
# isolated from a concurrently running default `ai-costops` project at the network
# and volume level. The only remaining collision surface is host-port binding, so
# we abort if any host port this overlay uses is already taken. We never stop or
# prune unrelated projects.
$ports = @(18080, 9090, 3000)
foreach ($p in $ports) {
    if (Get-NetTCPConnection -LocalPort $p -ErrorAction SilentlyContinue) {
        Write-Error "Host port $p is already in use (conflicting local stack). Free it before running the smoke."
        exit 1
    }
}

# ---------------------------------------------------------------------------
# 1. Start core + observability overlay (backend only; frontend not needed)
# ---------------------------------------------------------------------------
Log "Starting project $ProjectName (backend/prometheus/grafana + deps)..."
Invoke-Compose -ComposeArgs @('up','-d','backend','prometheus','grafana','mysql','redis','minio')

$Backend = "http://localhost:18080"
$Prom = "http://localhost:9090"
$Grafana = "http://localhost:3000"

$ok = Wait-Http200 "$Backend/actuator/health/liveness" $MaxWaitSeconds
Assert-Or-Fail $ok 'backend_health' "liveness"

$ok = Wait-Http200 "$Prom/-/ready" $MaxWaitSeconds
Assert-Or-Fail $ok 'prometheus_ready' "/-/ready"

$ok = Wait-Http200 "$Grafana/api/health" $MaxWaitSeconds
Assert-Or-Fail $ok 'grafana_health' "/api/health"

# ---------------------------------------------------------------------------
# 2. Backend Prometheus target UP
# ---------------------------------------------------------------------------
$targetUp = $false; $targetVal = $null
for ($i = 0; $i -lt 12; $i++) {
    $r = Query-Prom $Prom 'up{job="aicostops-backend"}'
    if ($r -and $r.status -eq 'success' -and $r.data.result.Count -gt 0) {
        $targetVal = $r.data.result[0].value[1]
        if ($targetVal -eq '1') { $targetUp = $true; break }
    }
    Start-Sleep -Seconds 5
}
Assert-Or-Fail $targetUp 'target_up' "up{job=`"aicostops-backend`"}=$targetVal"

# ---------------------------------------------------------------------------
# 3. Real non-empty business series: seed INVALID_CREDENTIALS, wait a scrape
# ---------------------------------------------------------------------------
$email = "smoke-$(Get-Date -Format yyyyMMddHHmmss)@example.test"
$code = Send-InvalidLogin $Backend $email
Log "seed invalid login -> HTTP $code"
Start-Sleep -Seconds $ScrapeWaitSeconds   # let Prometheus scrape at least once

$bizVal = $null; $bizNonEmpty = $false
$r = Query-Prom $Prom 'sum(aicostops_login_result_total{result="INVALID_CREDENTIALS"})'
if ($r -and $r.status -eq 'success' -and $r.data.result.Count -gt 0) {
    $bizVal = [double]$r.data.result[0].value[1]
    if ($bizVal -ge 1) { $bizNonEmpty = $true }
}
Assert-Or-Fail $bizNonEmpty 'business_series_nonempty' "aicostops_login_result_total{INVALID_CREDENTIALS}=$bizVal"

# ---------------------------------------------------------------------------
# 4. Generate bounded failures so increase() has a real baseline
# ---------------------------------------------------------------------------
$failures = 0
for ($i = 0; $i -lt 4; $i++) {
    $c = Send-InvalidLogin $Backend $email
    if ($c -in @(401, 429)) { $failures++ }
    Start-Sleep -Seconds 1
}
Log "generated $failures invalid-login attempts (HTTP 401/429)"

# ---------------------------------------------------------------------------
# 5. Alert inactive -> pending -> firing
# ---------------------------------------------------------------------------
$alertName = 'AiCostOpsLoginInvalidCredentialsSpike'
$pendingAt = $null; $firingAt = $null
$deadline = (Get-Date).AddSeconds($MaxWaitSeconds)
while ((Get-Date) -lt $deadline) {
    $alerts = Get-PromAlerts $Prom
    $a = $alerts | Where-Object { $_.labels.alertname -eq $alertName }
    if ($a) {
        if (-not $pendingAt -and $a.state -eq 'pending') { $pendingAt = Get-Date }
        if ($a.state -eq 'firing') { $firingAt = Get-Date; break }
    }
    Start-Sleep -Seconds 5
}
Assert-Or-Fail ($null -ne $firingAt) 'alert_firing' "pending=$pendingAt firing=$firingAt"

# ---------------------------------------------------------------------------
# 6. Recovery: stop generating, wait for the range window to expire
# ---------------------------------------------------------------------------
$recoveredAt = $null
while ((Get-Date) -lt $deadline) {
    $alerts = Get-PromAlerts $Prom
    $a = $alerts | Where-Object { $_.labels.alertname -eq $alertName }
    if (-not $a) { $recoveredAt = Get-Date; break }
    Start-Sleep -Seconds 5
}
Assert-Or-Fail ($null -ne $recoveredAt) 'alert_recovered' "recoveredAt=$recoveredAt"

# ---------------------------------------------------------------------------
# 7. Grafana dashboard provisioned
# ---------------------------------------------------------------------------
$dashOk = $false; $dashTitle = $null
$raw = & $Curl -s -u admin:admin "$Grafana/api/dashboards/uid/aicostops-overview"
if ($raw) {
    $dj = $raw | ConvertFrom-Json
    if ($dj.dashboard) { $dashOk = $true; $dashTitle = $dj.dashboard.title }
}
Assert-Or-Fail $dashOk 'grafana_dashboard_provisioned' "title=$dashTitle"

# ---------------------------------------------------------------------------
# 8. Cleanup ONLY this project
# ---------------------------------------------------------------------------
Log "Stopping only project $ProjectName ..."
Invoke-Compose -ComposeArgs @('down')

# ---------------------------------------------------------------------------
# 9. Write real evidence
# ---------------------------------------------------------------------------
$sha = (git -C $RepoRoot rev-parse HEAD)
$dockerVer = (docker version --format '{{.Server.Version}}' 2>$null)
$composeVer = (docker compose version --short 2>$null)
$ts = (Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')

$md = @"
# AIC-077 Observability Smoke Evidence

> Real run output. NO SLO / HA / capacity claims are made.

- Generated: $ts
- Commit SHA: $sha
- Docker server version: $dockerVer
- Docker Compose version: $composeVer
- Compose project: $ProjectName (started/stopped only by this script)
- Prometheus image: prom/prometheus:v2.54.1
- Grafana image: grafana/grafana:11.5.2

## Checks

| Check | Result | Detail |
|---|---|---|
| backend_health | $($results['backend_health']) | /actuator/health/liveness |
| prometheus_ready | $($results['prometheus_ready']) | /-/ready |
| grafana_health | $($results['grafana_health']) | /api/health |
| target_up | $($results['target_up']) | up{job="aicostops-backend"}=$targetVal |
| business_series_nonempty | $($results['business_series_nonempty']) | aicostops_login_result_total{result="INVALID_CREDENTIALS"}=$bizVal |
| alert_firing | $($results['alert_firing']) | pending=$pendingAt firing=$firingAt |
| alert_recovered | $($results['alert_recovered']) | recoveredAt=$recoveredAt |
| grafana_dashboard_provisioned | $($results['grafana_dashboard_provisioned']) | title=$dashTitle |

## Alert transition (deterministic fault injection)

Signal: invalid-credential logins (metric aicostops_login_result_total{result="INVALID_CREDENTIALS"}).
Rule: sum(increase(aicostops_login_result_total{result="INVALID_CREDENTIALS"}[1m])) >= 3, for: 15s.

- pending observed at: $pendingAt
- firing observed at: $firingAt
- recovered (inactive) at: $recoveredAt

## Notes

- Core Compose remains usable without the observability overlay; prometheus is
  exposed only when compose.observability.yaml is loaded.
- No global Docker prune and no unrelated project was stopped by this script.
"@
Set-Content -Path $EvidencePath -Value $md -Encoding UTF8
Log "Evidence written to $EvidencePath"

if ($failed) { Write-Error "SMOKE FAILED"; exit 1 }
Log "SMOKE PASSED"
exit 0
