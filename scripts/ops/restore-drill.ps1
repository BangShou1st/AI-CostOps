<#
.SYNOPSIS
End-to-end non-destructive restore drill:

  source stack (reading only)
  -> create synthetic source data via the public API
  -> backup MySQL + Evidence
  -> start an isolated restore-drill Compose project
  -> restore MySQL + Evidence into it
  -> start backend/frontend against the restored stores
  -> verify login, financial counts, ledger/period state, Evidence hash/count
  -> clean up ONLY the isolated project
  -> print M9_RESTORE_DRILL_PASS only after every assertion passes

The normal developer stack is never torn down, never volume-pruned and never
overwritten: the drill writes only under .local-backups/ and
.local-restore-drill/ (both git-ignored), and the isolated project owns its own
network, volumes and ports.

Timings are engineering evidence, not production RPO/RTO promises.
#>
[CmdletBinding()]
param(
    [string]$EnvFile = ".env",
    [string]$SourceProject = "ai-costops",
    [int]$DrillFrontendPort = 0,
    [string]$SourceBaseUrl = "",
    [string]$McImage = "minio/mc",
    [switch]$AllowEvidenceMirrorBypass,
    [switch]$KeepOnFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "`n[DRIFT] $Name" }
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}
function Get-EnvValue([string]$Name) {
    $escaped = [regex]::Escape($Name)
    $line = Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match "^$escaped=" } |
        Select-Object -First 1
    if (-not $line) { throw "Missing $Name in $EnvFile" }
    return (($line -split "=", 2)[1]).Trim()
}
function Invoke-Compose([string[]]$Arguments, [hashtable]$ExtraEnv = @{}) {
    $previous = @{}
    foreach ($key in $ExtraEnv.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key)
        [Environment]::SetEnvironmentVariable($key, [string]$ExtraEnv[$key])
    }
    try {
        $composeArgs = @()
        foreach ($file in $ComposeFiles) {
            $composeArgs += @("-f", $file)
        }
        & docker compose --env-file $EnvFile -p $DrillProject @composeArgs @Arguments 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
        }
    } finally {
        foreach ($key in $ExtraEnv.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key])
        }
    }
}
function Invoke-Json([string]$Method, [string]$Uri, [hashtable]$Headers, $Body = $null) {
    $params = @{ Method = $Method; Uri = $Uri; Headers = $Headers; ErrorAction = "Stop" }
    if ($Method -ne "Get") {
        $params.Headers["Idempotency-Key"] = [guid]::NewGuid().ToString("N")
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    try {
        return Invoke-RestMethod @params
    } catch {
        $status = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $status = " HTTP $([int]$_.Exception.Response.StatusCode)"
        }
        $details = [string]$_.ErrorDetails.Message
        if (-not [string]::IsNullOrWhiteSpace($details)) {
            throw "API $Method $Uri failed:$status body=$details"
        }
        throw "API $Method $Uri failed:$status"
    }
}
function Assert-CountsEqual([string]$Label, $Source, $Restored) {
    $s = [long]$Source
    $r = [long]$Restored
    Assert-True ($s -eq $r) "$Label count mismatch: source=$s restored=$r"
    Write-Output "[PASS] $Label count: $s == $r"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $repoRoot
Assert-True (Test-Path -LiteralPath $EnvFile) "Env file not found: $EnvFile"

# ---------------------------------------------------------------- timing clock
$tTotal = [System.Diagnostics.Stopwatch]::StartNew()
$tBackup = [System.Diagnostics.Stopwatch]::new()
$tRestore = [System.Diagnostics.Stopwatch]::new()
$tVerify = [System.Diagnostics.Stopwatch]::new()

$drillTs = Get-Date -Format "yyyyMMddHHmmss"
$runSuffix = (Get-Date -Format "HHmmss")
$DrillProject = "aicostops-restore-drill-$drillTs"
$drillStarted = $false
$drillRoot = Join-Path $repoRoot (Join-Path ".local-restore-drill" $drillTs)
New-Item -ItemType Directory -Path $drillRoot -Force | Out-Null

$overridePath = Join-Path $drillRoot "compose.override.yaml"
$drillNetwork = "aicostops-restore-drill-$drillTs-network"
# The isolated project MUST run on its own network/volumes/ports: the override
# file redeclares the aicostops network with a drill-unique name. Invoke-Compose
# always passes -f for every file in this list.
$ComposeFiles = @("compose.yaml")

try {
    # ------------------------------------------------------------- source env
    $sourceFrontendPort = Get-EnvValue "FRONTEND_PORT"
    if ([string]::IsNullOrWhiteSpace($SourceBaseUrl)) {
        $SourceBaseUrl = "http://localhost:$sourceFrontendPort/api/v1"
    }
    $SourceBaseUrl = $SourceBaseUrl.TrimEnd("/")
    $bootstrapEmail = Get-EnvValue "AICOSTOPS_DEV_BOOTSTRAP_EMAIL"
    $bootstrapPassword = Get-EnvValue "AICOSTOPS_DEV_BOOTSTRAP_PASSWORD"
    $mysqlUser = Get-EnvValue "MYSQL_USER"
    $mysqlPassword = Get-EnvValue "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue "MYSQL_DATABASE"

    if ($DrillFrontendPort -le 0) {
        for ($port = 18082; $port -lt 18200; $port += 1) {
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
            try {
                $listener.Start()
                $DrillFrontendPort = $port
                break
            } catch {
                continue
            } finally {
                $listener.Stop()
            }
        }
    }
    Assert-True ($DrillFrontendPort -gt 0) "Could not allocate a free port for the drill frontend"
    $DrillBaseUrl = "http://localhost:$DrillFrontendPort/api/v1"

    # ------------------------------------------------- source stack readiness
    Write-Stage "Source stack readiness"
    # docker compose v5 truncates long fields in `ps --format json`, making it
    # invalid JSON; inspect each container by id instead (same approach as the
    # smoke scripts).
    foreach ($service in @("mysql", "redis", "minio", "backend", "frontend")) {
        $containerId = (& docker compose --env-file $EnvFile -p $SourceProject ps -q $service 2>$null).Trim()
        Assert-True (-not [string]::IsNullOrWhiteSpace($containerId)) "Source service '$service' has no container"
        $health = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId 2>$null).Trim()
        Assert-True ($health -eq "healthy") "Source service '$service' is not healthy (health=$health)"
    }
    (Invoke-WebRequest -UseBasicParsing -Uri "$SourceBaseUrl/../actuator/health/liveness" -ErrorAction Stop) | Out-Null
    Write-Output "[PASS] source stack healthy ($SourceProject)"

    # --------------------------------------------------------- synthetic data
    Write-Stage "Create synthetic source data via the source API"
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $login = (Invoke-WebRequest -UseBasicParsing -WebSession $session -Method Post `
        -Uri "$SourceBaseUrl/auth/login" -ContentType "application/json" `
        -Body (@{ email = $bootstrapEmail; password = $bootstrapPassword } | ConvertTo-Json -Compress) `
        -ErrorAction Stop).Content | ConvertFrom-Json
    Assert-True (-not [string]::IsNullOrWhiteSpace($login.accessToken)) "Source login failed"
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    $me = Invoke-Json "Get" "$SourceBaseUrl/auth/me" $headers $null $session
    $orgId = [long]$me.organizationId

    $provider = Invoke-Json "Post" "$SourceBaseUrl/provider-accounts" $headers @{
        providerCode = "DEEPSEEK"
        displayName = "Restore Drill DeepSeek $runSuffix"
        externalAccountRef = "synthetic-drill-$runSuffix"
    } $session
    $providerAccountId = [long]$provider.id

    # DeepSeek ZIP fixture (same schema shape as the smoke suite).
    $fixtureMonth = (Get-Date).ToUniversalTime().ToString("yyyy-MM")
    $startIso = (Get-Date).ToUniversalTime().ToString("yyyy-MM-02T00:00:00Z")
    $endIso = (Get-Date).ToUniversalTime().ToString("yyyy-MM-02T01:00:00Z")
    $zipPath = Join-Path $drillRoot "synthetic-deepseek.zip"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zipTmp = Join-Path $drillRoot "zip-src"
    New-Item -ItemType Directory -Path $zipTmp -Force | Out-Null
    [System.IO.File]::WriteAllText(
        (Join-Path $zipTmp "amount-$fixtureMonth.csv"),
        "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount`n" +
        "synthetic-user,$startIso,$endIso,deepseek-chat,drill-key,sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125")
    [System.IO.File]::WriteAllText(
        (Join-Path $zipTmp "cost-$fixtureMonth.csv"),
        "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency`n" +
        "synthetic-user,$startIso,$endIso,deepseek-chat,main_wallet,1.25,CNY")
    [System.IO.Compression.ZipFile]::CreateFromDirectory($zipTmp, $zipPath)

    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $stream = [System.IO.File]::OpenRead($zipPath)
        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        try {
            $client.DefaultRequestHeaders.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $login.accessToken)
            $fileContent = [System.Net.Http.StreamContent]::new($stream)
            $fileContent.Headers.ContentType =
                [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/zip")
            $multipart.Add($fileContent, "file", "synthetic-deepseek.zip")
            $multipart.Add([System.Net.Http.StringContent]::new($providerAccountId.ToString()), "providerAccountId")
            $multipart.Add([System.Net.Http.StringContent]::new("FILE_EXPORT"), "sourceType")
            $uploadRes = $client.PostAsync("$SourceBaseUrl/provider-imports", $multipart).GetAwaiter().GetResult()
            Assert-True ($uploadRes.IsSuccessStatusCode) "Provider import upload failed: $([int]$uploadRes.StatusCode)"
            $uploadBody = $uploadRes.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        } finally {
            $multipart.Dispose()
            $stream.Dispose()
        }
    } finally {
        $client.Dispose()
    }
    $importId = [long]$uploadBody.importBatchId
    $importDeadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Seconds 2
        $import = Invoke-Json "Get" "$SourceBaseUrl/imports/$importId" $headers $null $session
        if ($import.status -eq "FAILED") { throw "Source import worker failed" }
    } while ($import.status -ne "READY_FOR_REVIEW" -and [DateTime]::UtcNow -lt $importDeadline)
    Assert-True ($import.status -eq "READY_FOR_REVIEW") "Source import did not reach READY_FOR_REVIEW"
    $confirmed = Invoke-Json "Post" "$SourceBaseUrl/imports/$importId/confirm" $headers $null $session
    Assert-True ($confirmed.status -eq "CONFIRMED") "Source import confirm failed"
    Write-Output "[PASS] synthetic provider import confirmed (id $importId)"

    # Approved + posted expense so the ledger is non-empty after restore.
    $expense = Invoke-Json "Post" "$SourceBaseUrl/expenses" $headers @{
        expenseDate = (Get-Date).ToUniversalTime().ToString("yyyy-MM-02")
        amount = "12.34000000"
        currency = "CNY"
    } $session
    $expenseId = [long]$expense.id
    $evidenceText = "synthetic expense evidence for restore drill $runSuffix"
    $receiptPath = Join-Path $drillRoot "synthetic-receipt.txt"
    [System.IO.File]::WriteAllText($receiptPath, $evidenceText)
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $stream = [System.IO.File]::OpenRead($receiptPath)
        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        try {
            $client.DefaultRequestHeaders.Authorization =
                [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $login.accessToken)
            $fileContent = [System.Net.Http.StreamContent]::new($stream)
            $fileContent.Headers.ContentType =
                [System.Net.Http.Headers.MediaTypeHeaderValue]::new("text/plain")
            $multipart.Add($fileContent, "file", "synthetic-receipt.txt")
            $multipart.Add([System.Net.Http.StringContent]::new("0"), "expectedVersion")
            $evRes = $client.PostAsync("$SourceBaseUrl/expenses/$expenseId/evidence", $multipart).GetAwaiter().GetResult()
            Assert-True ($evRes.IsSuccessStatusCode) "Evidence upload failed: $([int]$evRes.StatusCode)"
        } finally {
            $multipart.Dispose()
            $stream.Dispose()
        }
    } finally {
        $client.Dispose()
    }
    $submitted = Invoke-Json "Post" "$SourceBaseUrl/expenses/$expenseId/submit" $headers @{ expectedVersion = 1 } $session
    Assert-True ($submitted.status -eq "SUBMITTED") "Expense submit failed"
    $approved = Invoke-Json "Post" "$SourceBaseUrl/expenses/$expenseId/approve" $headers @{ expectedVersion = 2 } $session
    Assert-True ($approved.status -eq "APPROVED") "Expense approve failed"
    $project = Invoke-Json "Post" "$SourceBaseUrl/projects" $headers @{
        code = "drill-$runSuffix"
        name = "Restore Drill Project $runSuffix"
    } $session
    $projectId = [long]$project.id
    $allocation = Invoke-Json "Post" "$SourceBaseUrl/expenses/$expenseId/allocation-decisions/manual" $headers @{
        lines = @(
            @{
                allocatedAmount = "12.34000000"
                currency = "CNY"
                projectId = $projectId.ToString()
            }
        )
    } $session
    $allocationConfirmed = Invoke-Json "Post" "$SourceBaseUrl/allocation-decisions/$($allocation.id)/confirm" $headers $null $session
    Assert-True ($allocationConfirmed.status -eq "CONFIRMED") "Expense allocation confirm failed"
    $posted = Invoke-Json "Post" "$SourceBaseUrl/expenses/$expenseId/post" $headers @{ commitmentLinks = @() } $session
    Assert-True ($posted.status -eq "POSTED") "Expense post failed (status $($posted.status))"
    Write-Output "[PASS] synthetic expense approved, allocated, posted (id $expenseId)"

    # -------------------------------------------------------------- counters
    Write-Stage "Snapshot source counts"
    $chargesS = ((Invoke-Json "Get" "$SourceBaseUrl/costs/charges?page=0&size=100" $headers $null $session).items).Count
    $expensesS = ((Invoke-Json "Get" "$SourceBaseUrl/expenses?page=0&size=100" $headers $null $session).items).Count
    $postingsS = ((Invoke-Json "Get" "$SourceBaseUrl/ledger/postings?page=0&size=100" $headers $null $session).items).Count
    $entriesS = ((Invoke-Json "Get" "$SourceBaseUrl/ledger/entries?page=0&size=100" $headers $null $session).items).Count
    $periodsS = @(Invoke-Json "Get" "$SourceBaseUrl/billing-periods" $headers $null $session)
    $openPeriod = $periodsS | Where-Object { $_.status -eq "OPEN" } | Select-Object -First 1
    Assert-True ($null -ne $openPeriod) "No OPEN billing period on the source stack"
    $sourceEvidence = @(Invoke-Json "Get" "$SourceBaseUrl/evidence?page=0&size=100" $headers $null $session)
    $evidenceCountS = 0
    if ($sourceEvidence.PSObject.Properties.Name -contains "items") {
        $evidenceCountS = @($sourceEvidence.items).Count
    } else {
        $evidenceCountS = @($sourceEvidence).Count
    }
    Write-Output "[INFO] source counts: charges=$chargesS expenses=$expensesS postings=$postingsS entries=$entriesS evidence=$evidenceCountS period=$($openPeriod.id)/$($openPeriod.status)"

    # --------------------------------------------------------------- backups
    Write-Stage "Backup MySQL"
    $tBackup.Start()
    $mysqlBackupOut = Join-Path $repoRoot (Join-Path ".local-backups" (Join-Path "mysql" $drillTs))
    & (Join-Path $PSScriptRoot "backup-mysql.ps1") `
        -EnvFile $EnvFile -ProjectName $SourceProject -OutDir $mysqlBackupOut
    $dumpPath = Join-Path $mysqlBackupOut "dump.sql"
    Assert-True (Test-Path -LiteralPath $dumpPath) "MySQL dump missing"

    # The Evidence bucket mirror needs the disposable MinIO Client image. If it
    # is not present locally (e.g. registries unreachable), the drill must not
    # fake the full pass: it either fails here or, with the explicit operator
    # switch, downgrades to API-level Evidence verification and reports a
    # clearly-labelled marker instead of M9_RESTORE_DRILL_PASS.
    $mcAvailable = $true
    & docker image inspect $McImage *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[WARN] MinIO Client image '$McImage' is not in the local Docker cache; trying to pull it"
        & docker pull $McImage 2>&1 | Out-Null
        & docker image inspect $McImage *> $null
        $mcAvailable = ($LASTEXITCODE -eq 0)
    }
    if (-not $mcAvailable) {
        if (-not $AllowEvidenceMirrorBypass) {
            throw "EVIDENCE_MIRROR_BLOCKED: MinIO Client image '$McImage' is unavailable and registries are unreachable; rerun with -AllowEvidenceMirrorBypass to verify Evidence through the app API only."
        }
        Write-Output "[WARN] EVIDENCE_MIRROR_BYPASS: Evidence bucket mirror skipped (operator opted in); Evidence is verified via API count + download content hash."
    } else {
        Write-Stage "Backup Evidence"
        $evidenceBackupOut = Join-Path $repoRoot (Join-Path ".local-backups" (Join-Path "evidence" $drillTs))
        & (Join-Path $PSScriptRoot "backup-evidence.ps1") `
            -EnvFile $EnvFile -ProjectName $SourceProject -OutDir $evidenceBackupOut `
            -McImage $McImage
        $evidenceManifest = Get-Content -LiteralPath (Join-Path $evidenceBackupOut "backup-manifest.json") -Raw | ConvertFrom-Json
        $evidenceBackupCount = @($evidenceManifest.files).Count
    }
    $tBackup.Stop()

    # ----------------------------------------------------- isolated drill project
    Write-Stage "Start isolated restore-drill project '$DrillProject'"
    $DrillProject = "aicostops-restore-drill-$drillTs"
    # Derive the real frontend container port from the service definition so
    # the drill stays compatible with #121's final 8080 runtime without
    # hardcoding a branch-specific port. Prefer nginx config; fall back to
    # compose.yaml.
    $frontendContainerPort = 0
    $nginxConfPath = Join-Path $repoRoot "frontend/nginx/default.conf"
    if (Test-Path -LiteralPath $nginxConfPath) {
        $nginxText = Get-Content -LiteralPath $nginxConfPath -Raw
        $match = [regex]::Match($nginxText, 'listen\s+(\d+)')
        if ($match.Success) { $frontendContainerPort = [int]$match.Groups[1].Value }
    }
    if ($frontendContainerPort -le 0) {
        $composePath = Join-Path $repoRoot "compose.yaml"
        if (Test-Path -LiteralPath $composePath) {
            $composeText = Get-Content -LiteralPath $composePath -Raw
            $composeMatch = [regex]::Match($composeText, '\$\{FRONTEND_PORT\}:(\d+)')
            if ($composeMatch.Success) { $frontendContainerPort = [int]$composeMatch.Groups[1].Value }
        }
    }
    if ($frontendContainerPort -le 0) { $frontendContainerPort = 8080 }
    Write-Output "[INFO] derived frontend container port=$frontendContainerPort (from nginx/compose)"
    $overrideYaml = @"
services:
  backend:
    image: ai-costops-backend:local
  frontend:
    image: ai-costops-frontend:local
    # !override replaces (not appends) the compose.yaml port mapping: the drill
    # frontend must own exactly its isolated port and never steal the source
    # stack's FRONTEND_PORT. Container port is derived from the real service
    # definition (nginx default.conf / compose.yaml) for cross-PR compatibility.
    ports: !override
      - "${DrillFrontendPort}:$frontendContainerPort"
networks:
  aicostops:
    name: $drillNetwork
"@
    [System.IO.File]::WriteAllText($overridePath, $overrideYaml, [System.Text.UTF8Encoding]::new($false))
    $ComposeFiles = @("compose.yaml", $overridePath)

    $tRestore.Start()
    # Bring up only the stores first, restore into them, then boot app services.
    Invoke-Compose @("up", "-d", "mysql", "redis", "minio", "--wait", "--wait-timeout", "240")
    $drillStarted = $true
    Write-Output "[PASS] isolated stores up"

    Write-Stage "Restore MySQL into the isolated project"
    & (Join-Path $PSScriptRoot "restore-mysql.ps1") `
        -SourceDump $dumpPath -ProjectName $DrillProject -EnvFile $EnvFile

    if ($mcAvailable) {
        Write-Stage "Restore Evidence into the isolated project"
        & (Join-Path $PSScriptRoot "restore-evidence.ps1") `
            -SourceDir $evidenceBackupOut -ProjectName $DrillProject -EnvFile $EnvFile `
            -McImage $McImage
    } else {
        Write-Output "[WARN] restore-evidence skipped (EVIDENCE_MIRROR_BYPASS active)"
    }

    Write-Stage "Start backend/frontend against the restored stores"
    Invoke-Compose @("up", "-d", "backend", "frontend", "--wait", "--wait-timeout", "300")
    $tRestore.Stop()

    # ------------------------------------------------------------- verification
    Write-Stage "Verify the restored stack"
    $tVerify.Start()
    $probe = Invoke-WebRequest -UseBasicParsing -Uri "$DrillBaseUrl/../actuator/health/liveness" -ErrorAction Stop
    Assert-True ([int]$probe.StatusCode -eq 200) "Restored backend liveness failed"

    $drillSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $drillLogin = (Invoke-WebRequest -UseBasicParsing -WebSession $drillSession -Method Post `
        -Uri "$DrillBaseUrl/auth/login" -ContentType "application/json" `
        -Body (@{ email = $bootstrapEmail; password = $bootstrapPassword } | ConvertTo-Json -Compress) `
        -ErrorAction Stop).Content | ConvertFrom-Json
    Assert-True (-not [string]::IsNullOrWhiteSpace($drillLogin.accessToken)) "Login failed on the restored stack"
    $drillHeaders = @{ Authorization = "Bearer $($drillLogin.accessToken)" }
    Write-Output "[PASS] login works on the restored stack"

    $chargesR = ((Invoke-Json "Get" "$DrillBaseUrl/costs/charges?page=0&size=100" $drillHeaders $null $drillSession).items).Count
    $expensesR = ((Invoke-Json "Get" "$DrillBaseUrl/expenses?page=0&size=100" $drillHeaders $null $drillSession).items).Count
    $postingsR = ((Invoke-Json "Get" "$DrillBaseUrl/ledger/postings?page=0&size=100" $drillHeaders $null $drillSession).items).Count
    $entriesR = ((Invoke-Json "Get" "$DrillBaseUrl/ledger/entries?page=0&size=100" $drillHeaders $null $drillSession).items).Count
    $periodsR = @(Invoke-Json "Get" "$DrillBaseUrl/billing-periods" $drillHeaders $null $drillSession)
    $openPeriodR = $periodsR | Where-Object { $_.id -eq $openPeriod.id } | Select-Object -First 1

    Assert-CountsEqual "charges" $chargesS $chargesR
    Assert-CountsEqual "expenses" $expensesS $expensesR
    Assert-CountsEqual "ledger postings" $postingsS $postingsR
    Assert-CountsEqual "ledger entries" $entriesS $entriesR
    Assert-True ($null -ne $openPeriodR) "Restored stack lost the OPEN billing period $($openPeriod.id)"
    Assert-True ($openPeriodR.status -eq "OPEN") "Restored period $($openPeriod.id) status is $($openPeriodR.status), expected OPEN"

    # Evidence availability + byte-for-byte download (works with or without the
    # bucket mirror; the mirror also proves object-level count/hash).
    $evidenceDownload = Invoke-WebRequest -UseBasicParsing -Uri "$DrillBaseUrl/expenses/$expenseId/evidence/download" `
        -WebSession $drillSession -Headers $drillHeaders -ErrorAction Stop
    Assert-True ([int]$evidenceDownload.StatusCode -eq 200) "Evidence download failed on the restored stack"
    # PowerShell 7's Invoke-WebRequest returns Content as a string for text
    # payloads and as byte[] for binary ones; compare whichever form we got.
    $evidenceContent = $evidenceDownload.Content
    if ($evidenceContent -is [byte[]]) {
        $evidenceContent = [System.Text.Encoding]::UTF8.GetString($evidenceContent)
    }
    Assert-True ($evidenceContent -eq $evidenceText) "Restored Evidence content mismatch"
    if ($mcAvailable) {
        # Independently verify the restored bucket object count via the
        # isolated MinIO, not by comparing the backup count to itself.
        $restoredEvidenceCount = 0
        $restoredListingJson = & docker run --rm `
            --network $drillNetwork `
            -e "MC_HOST_drill=http://$accessKey`:$secretKey@minio:9000" `
            $McImage ls --recursive --json "drill/$bucket" 2>&1
        if ($LASTEXITCODE -eq 0 -and $restoredListingJson) {
            foreach ($line in $restoredListingJson) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                try {
                    $entry = $line | ConvertFrom-Json
                    if ($entry.type -eq "file") { $restoredEvidenceCount += 1 }
                } catch { continue }
            }
        }
        Write-Output "[INFO] restored Evidence bucket object count: $restoredEvidenceCount (backup manifest count: $evidenceBackupCount)"
        Assert-True ($restoredEvidenceCount -gt 0) "Restored Evidence bucket is empty (drill/$bucket)"
        Assert-CountsEqual "evidence objects" $evidenceBackupCount $restoredEvidenceCount
    } else {
        # Mirror bypass: compare source API-visible evidence count vs restored
        # API-visible count (two independent reads).
        $restoredEvidence = @(Invoke-Json "Get" "$DrillBaseUrl/evidence?page=0&size=100" $drillHeaders $null $drillSession)
        $restoredEvidenceCount = 0
        if ($restoredEvidence.PSObject.Properties.Name -contains "items") {
            # Paginated response
            $restoredEvidenceCount = @($restoredEvidence.items).Count
        } elseif ($restoredEvidence -and $restoredEvidence.Count -gt 0 -and $restoredEvidence[0].PSObject.Properties.Name -contains "items") {
            $restoredEvidenceCount = @($restoredEvidence[0].items).Count
        } else {
            $restoredEvidenceCount = @($restoredEvidence).Count
        }
        Write-Output "[INFO] evidence counts source(API)=$evidenceCountS restored(API)=$restoredEvidenceCount"
        Assert-CountsEqual "evidence objects (API)" $evidenceCountS $restoredEvidenceCount
    }

    $tVerify.Stop()

    # ------------------------------------------------------ cleanup (drill only)
    Write-Stage "Clean up the isolated drill project"
    Invoke-Compose @("down", "-v", "--remove-orphans")
    Remove-Item -LiteralPath $drillRoot -Recurse -Force
    Write-Output "[PASS] isolated drill project removed; normal developer volumes untouched"

    Write-Output ""
    Write-Output "[DRIFT] backup elapsed: $([math]::Round($tBackup.Elapsed.TotalSeconds, 1))s"
    Write-Output "[DRIFT] restore elapsed: $([math]::Round($tRestore.Elapsed.TotalSeconds, 1))s"
    Write-Output "[DRIFT] verify elapsed: $([math]::Round($tVerify.Elapsed.TotalSeconds, 1))s"
    Write-Output "[DRIFT] total elapsed: $([math]::Round($tTotal.Elapsed.TotalSeconds, 1))s"
    Write-Output ""
    if ($mcAvailable) {
        Write-Output "M9_RESTORE_DRILL_PASS"
    } else {
        Write-Output "M9_RESTORE_DRILL_PASS_WITH_EVIDENCE_MIRROR_BYPASS"
        Write-Output "[NOTE] Evidence was verified via the app API (count + download content hash); the MinIO bucket mirror was not executed because the MinIO Client image is unavailable locally."
    }
} catch {
    Write-Output ""
    Write-Output "[DRIFT] FAILED: $($_.Exception.Message)"
    if ($drillStarted -and -not $KeepOnFailure) {
        Write-Stage "Attempting cleanup of the isolated drill project"
        try {
            Invoke-Compose @("down", "-v", "--remove-orphans") 
            Write-Output "[INFO] drill project cleaned up after failure"
        } catch {
            Write-Output "[WARN] drill cleanup failed: $($_.Exception.Message); inspect project $DrillProject"
        }
    } else {
        Write-Output "[INFO] -KeepOnFailure was set or the drill never started; project $DrillProject left as-is"
    }
    exit 1
}