<#
.SYNOPSIS
    Exercise AdvisorInferenceWorker through a real Gateway DB account and Mock Provider.

.DESCRIPTION
    Runs against an already bootstrapped M21 Compose project. Backend creates the
    profile/jobs/snapshots with its normal datasource; Gateway runs as gw_m21 and
    performs the Worker SQL. If the project has no project budget yet, the script
    seeds one test-only row into that isolated database so BUDGET_RISK evidence and
    the normal governed Gateway request have a valid fixture.
#>
[CmdletBinding()]
param(
    [string]$ComposeProject = "aicostops-m21-final-r3",
    [string]$ComposeFile = "compose.yaml,compose.v3-operational.yaml",
    [string]$StateFile = ".m21-operational-state.json",
    [string]$BackendBase = "http://127.0.0.1:8080",
    [string]$MockBase = "http://127.0.0.1:8089",
    [int]$TerminalTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$helperPath = Join-Path $PSScriptRoot "import-v3-operational-env.ps1"
. $helperPath
Import-M21OperationalEnv | Out-Null

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$statePath = if ([System.IO.Path]::IsPathRooted($StateFile)) {
    $StateFile
} else {
    Join-Path $repoRoot $StateFile
}
if (-not (Test-Path $statePath)) {
    throw "[M21-ADVISOR] State file not found. Run scripts/m21/seed-v3-operational.ps1 first."
}
$seed = Get-Content $statePath -Raw | ConvertFrom-Json
$orgId = [long]$seed.org_id
$providerModelId = [long]$seed.provider_model_id

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
    # Fixture setup only. All Gateway runtime probes and Worker calls use gw_m21.
    $result = docker compose @composeArgs exec -T mysql sh -c "mysql -u root -p`"`$MYSQL_ROOT_PASSWORD`" aicostops -N -B -e `"$Sql`" 2>&1"
    $filtered = ($result | Where-Object { $_ -notmatch "Warning" }) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "[M21-ADVISOR] Test fixture SQL failed: $filtered"
    }
    return $filtered.Trim()
}

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "[M21-ADVISOR][FAIL] $Message"
    }
    Write-Output "[M21-ADVISOR] PASS: $Message"
}

function Set-MockMode([string]$Mode) {
    $body = @{ mode = $Mode } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$MockBase/admin/mode" `
        -ContentType "application/json" -Body $body | Out-Null
}

function New-AdvisorJob([hashtable]$Headers, [long]$BudgetId) {
    $body = @{ subjectType = "BUDGET_RISK"; subjectId = $BudgetId } | ConvertTo-Json -Compress
    return Invoke-RestMethod -Method Post -Uri "$BackendBase/api/v1/ai-advisor/explanations" `
        -Headers $Headers -ContentType "application/json" -Body $body
}

function Wait-AdvisorJob([hashtable]$Headers, [long]$JobId) {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TerminalTimeoutSeconds)
    do {
        $job = Invoke-RestMethod -Uri "$BackendBase/api/v1/ai-advisor/explanations/$JobId" -Headers $Headers
        if ($job.status -in @("COMPLETED", "FAILED")) {
            return $job
        }
        Start-Sleep -Milliseconds 1000
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "[M21-ADVISOR] Job $JobId did not reach a terminal state within $TerminalTimeoutSeconds seconds."
}

# Profile and evidence setup are done by Backend as in normal API use.
$projectId = [long](Invoke-Mysql "SELECT id FROM project WHERE org_id=$orgId AND code='aicostops-gateway-dev' AND status='ACTIVE' LIMIT 1;")
$periodId = [long](Invoke-Mysql "SELECT id FROM billing_period WHERE org_id=$orgId AND status='OPEN' AND period_start <= UTC_TIMESTAMP(6) AND period_end > UTC_TIMESTAMP(6) ORDER BY id DESC LIMIT 1;")
$budgetIdText = Invoke-Mysql "SELECT id FROM budget WHERE org_id=$orgId AND billing_period_id=$periodId AND scope_type='PROJECT' AND scope_id=$projectId AND currency='USD' AND status='ACTIVE' ORDER BY id DESC LIMIT 1;"
if ([string]::IsNullOrWhiteSpace($budgetIdText)) {
    Invoke-Mysql "INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,actual_amount,committed_amount,status,version,created_at,updated_at) VALUES($orgId,$periodId,'PROJECT',$projectId,'USD',1000.00000000,0,0,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));" | Out-Null
    $budgetIdText = Invoke-Mysql "SELECT id FROM budget WHERE org_id=$orgId AND billing_period_id=$periodId AND scope_type='PROJECT' AND scope_id=$projectId AND currency='USD' AND status='ACTIVE' ORDER BY id DESC LIMIT 1;"
}
$budgetId = [long]$budgetIdText
Assert-That ($projectId -gt 0 -and $periodId -gt 0 -and $budgetId -gt 0) "isolated project, open billing period, and budget fixture exist"

$loginBody = @{
    email = $env:AICOSTOPS_DEV_BOOTSTRAP_EMAIL
    password = $env:AICOSTOPS_DEV_BOOTSTRAP_PASSWORD
} | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Method Post -Uri "$BackendBase/api/v1/auth/login" `
    -ContentType "application/json" -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$profileBody = @{
    providerModelId = $providerModelId
    projectId = $projectId
    financialScopeType = "PROJECT"
    financialScopeId = $projectId
    budgetEnforcementMode = "REQUIRED"
} | ConvertTo-Json -Compress
$profile = Invoke-RestMethod -Method Put -Uri "$BackendBase/api/v1/ai-advisor/profile" `
    -Headers $headers -ContentType "application/json" -Body $profileBody
Assert-That ($profile.status -eq "ACTIVE" -and $profile.providerModelId -eq $providerModelId) `
    "Backend creates an ACTIVE profile bound to the seeded provider model"

Invoke-RestMethod -Method Post -Uri "$MockBase/admin/reset" | Out-Null
Set-MockMode "advisor-success"
$beforeSuccess = [int](Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
$successRequest = New-AdvisorJob $headers $budgetId
$success = Wait-AdvisorJob $headers ([long]$successRequest.id)
$afterSuccess = [int](Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
Assert-That ($success.status -eq "COMPLETED") "successful Mock Provider path completes the Advisor job"
Assert-That ($success.attemptCount -eq 1 -and $success.attempts.Count -eq 1 -and $success.attempts[0].status -eq "COMPLETED") `
    "success writes exactly one COMPLETED attempt"
Assert-That ($success.explanation.summary -eq "Mock advisor completed.") "success writes the validated explanation"
Assert-That ($null -ne $success.gatewayRequestId -and $afterSuccess - $beforeSuccess -eq 1) `
    "success links one governed Gateway request and performs one Mock call"

Set-MockMode "http500"
$beforeFailure = [int](Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
$failureRequest = New-AdvisorJob $headers $budgetId
$failure = Wait-AdvisorJob $headers ([long]$failureRequest.id)
$afterFailure = [int](Invoke-RestMethod -Uri "$MockBase/stats").post_chat_completions
Assert-That ($failure.status -eq "FAILED" -and -not [string]::IsNullOrWhiteSpace($failure.failureCode)) `
    "upstream HTTP 500 records the failed terminal state and safe failure code"
Assert-That ($failure.attemptCount -eq 1 -and $failure.attempts.Count -eq 1 -and $failure.attempts[0].status -eq "FAILED") `
    "failure writes exactly one FAILED attempt"
Assert-That ($null -eq $failure.explanation -and $afterFailure - $beforeFailure -eq 1) `
    "failure writes no explanation and performs one Mock call"

# Let at least one complete 30-second scheduler interval pass; terminal jobs
# must remain terminal and must not cause repeated Provider calls.
Start-Sleep -Seconds 32
$successAfterInterval = Invoke-RestMethod -Uri "$BackendBase/api/v1/ai-advisor/explanations/$($success.id)" -Headers $headers
$failureAfterInterval = Invoke-RestMethod -Uri "$BackendBase/api/v1/ai-advisor/explanations/$($failure.id)" -Headers $headers
$finalStats = Invoke-RestMethod -Uri "$MockBase/stats"
Assert-That ($successAfterInterval.status -eq "COMPLETED" -and $failureAfterInterval.status -eq "FAILED") `
    "both jobs stay terminal across a full Worker interval"
Assert-That ([int]$finalStats.post_chat_completions -eq 2) "no automatic retry adds another Mock Provider call"

Invoke-RestMethod -Method Post -Uri "$MockBase/admin/reset" | Out-Null
Write-Output "[M21-ADVISOR] SUCCESS job=$($success.id) attempt=COMPLETED; FAILURE job=$($failure.id) attempt=FAILED code=$($failure.failureCode)"
Write-Output "[M21-ADVISOR] M21_ADVISOR_WORKER_SMOKE_PASS"
