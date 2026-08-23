[CmdletBinding()]
param(
    [string]$BaseUrl = "",
    [string]$EnvFile = ".env.example",
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) {
    Write-Output "[SMOKE] $Name"
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Get-SmokeBusinessDates {
    $utcNow = [DateTime]::UtcNow
    $periodStart = [DateTime]::SpecifyKind(
        [DateTime]::new($utcNow.Year, $utcNow.Month, 1),
        [DateTimeKind]::Utc)
    $periodEnd = $periodStart.AddMonths(1)
    $transactionStart = $periodStart.AddDays(1)
    $transactionEnd = $transactionStart.AddHours(1)

    Assert-True ($transactionStart -ge $periodStart -and $transactionEnd -lt $periodEnd) `
        "Smoke transaction date is outside the current UTC billing period"

    return [pscustomobject]@{
        ProviderStartIso = $transactionStart.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
        ProviderEndIso   = $transactionEnd.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
        ExpenseDate      = $transactionStart.ToString("yyyy-MM-dd")
        FixtureMonth     = $periodStart.ToString("yyyy-MM")
    }
}

function Get-EnvValue([string]$Name) {
    $escaped = [regex]::Escape($Name)
    $line = Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match "^$escaped=" } |
        Select-Object -First 1
    if (-not $line) {
        throw "Missing $Name in $EnvFile"
    }
    return (($line -split "=", 2)[1]).Trim()
}

function Invoke-Compose([string[]]$Arguments) {
    $output = & docker compose --env-file $EnvFile @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Get-ServiceHealth([string]$Service) {
    $containerId = (& docker compose --env-file $EnvFile ps -q $Service 2>$null).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        return "missing"
    }
    $health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId 2>$null).Trim()
    if ([string]::IsNullOrWhiteSpace($health)) {
        return "unknown"
    }
    return $health
}

function Wait-ServicesHealthy([string[]]$Services) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $states = [ordered]@{}
        foreach ($service in $Services) {
            $states[$service] = Get-ServiceHealth $service
        }
        if (@($states.Values | Where-Object { $_ -ne "healthy" }).Count -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)

    $summary = ($states.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ", "
    throw "Timed out waiting for Compose services: $summary"
}

function Invoke-Json([string]$Method, [string]$Uri, [hashtable]$Headers, $Body = $null, [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession = $null) {
    $params = @{
        Method      = $Method
        Uri         = $Uri
        Headers     = $Headers
        ErrorAction = "Stop"
    }
    if ($null -ne $WebSession) {
        $params.WebSession = $WebSession
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    return Invoke-RestMethod @params
}

function Invoke-MultipartJson([string]$Uri, [string]$AccessToken, [string]$ZipPath, [long]$ProviderAccountId) {
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $stream = [System.IO.File]::OpenRead($ZipPath)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    try {
        $client.DefaultRequestHeaders.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $AccessToken)
        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType =
            [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/zip")
        $multipart.Add($fileContent, "file", [System.IO.Path]::GetFileName($ZipPath))
        $multipart.Add([System.Net.Http.StringContent]::new($ProviderAccountId.ToString()), "providerAccountId")
        $multipart.Add([System.Net.Http.StringContent]::new("FILE_EXPORT"), "sourceType")

        $response = $client.PostAsync($Uri, $multipart).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Multipart request failed with HTTP $([int]$response.StatusCode)"
        }
        return $body | ConvertFrom-Json
    } finally {
        $multipart.Dispose()
        $stream.Dispose()
        $client.Dispose()
    }
}

function New-DeepSeekSmokeZip($BusinessDates) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $root = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-costops-smoke-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $amount = @(
        "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount",
        "synthetic-user,$($BusinessDates.ProviderStartIso),$($BusinessDates.ProviderEndIso),deepseek-chat,smoke-key,sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125"
    ) -join "`n"
    $cost = @(
        "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency",
        "synthetic-user,$($BusinessDates.ProviderStartIso),$($BusinessDates.ProviderEndIso),deepseek-chat,main_wallet,1.25,CNY"
    ) -join "`n"
    [System.IO.File]::WriteAllText((Join-Path $root "amount-$($BusinessDates.FixtureMonth).csv"), $amount)
    [System.IO.File]::WriteAllText((Join-Path $root "cost-$($BusinessDates.FixtureMonth).csv"), $cost)
    $zipPath = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-costops-smoke-" + [guid]::NewGuid().ToString("N") + ".zip")
    [System.IO.Compression.ZipFile]::CreateFromDirectory($root, $zipPath)
    Remove-Item -LiteralPath $root -Recurse -Force
    return $zipPath
}

$composeServices = @("mysql", "redis", "minio", "backend", "frontend")
$frontendPort = Get-EnvValue "FRONTEND_PORT"
if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    if ([string]::IsNullOrWhiteSpace($frontendPort)) {
        $frontendPort = "8080"
    }
    $BaseUrl = "http://localhost:$frontendPort/api/v1"
}
$BaseUrl = $BaseUrl.TrimEnd("/")
$frontendRoot = ([uri]$BaseUrl).GetLeftPart([System.UriPartial]::Authority)
$bootstrapEmail = Get-EnvValue "AICOSTOPS_DEV_BOOTSTRAP_EMAIL"
$bootstrapPassword = Get-EnvValue "AICOSTOPS_DEV_BOOTSTRAP_PASSWORD"
Assert-True (-not [string]::IsNullOrWhiteSpace($bootstrapEmail)) "Development bootstrap email is empty"
Assert-True (-not [string]::IsNullOrWhiteSpace($bootstrapPassword)) "Development bootstrap password is empty"

$zipPath = $null
$smokeRunId = [guid]::NewGuid().ToString("N")
$smokeRunSuffix = $smokeRunId.Substring(0, 12)
$smokeProviderDisplayName = "Compose Smoke DeepSeek $smokeRunSuffix"
$smokeProviderExternalRef = "synthetic-compose-smoke-$smokeRunSuffix"
try {
    Write-Stage "Compose service health"
    Wait-ServicesHealthy $composeServices
    Write-Output "[PASS] mysql, redis, minio, backend and frontend are healthy"
    Write-Output "[INFO] smoke run id: $smokeRunId"
    $smokeBusinessDates = Get-SmokeBusinessDates
    Write-Output "[INFO] smoke business date: $($smokeBusinessDates.ExpenseDate) UTC"

    Write-Stage "Dependency readiness"
    $mysqlUser = Get-EnvValue "MYSQL_USER"
    $mysqlPassword = Get-EnvValue "MYSQL_PASSWORD"
    $mysqlDatabase = Get-EnvValue "MYSQL_DATABASE"
    $mysqlResult = (
        & docker compose --env-file $EnvFile exec -T `
            -e "MYSQL_PWD=$mysqlPassword" `
            mysql `
            mysql "-u$mysqlUser" "$mysqlDatabase" -Nse "SELECT 1;"
    ).Trim()
    Assert-True ($LASTEXITCODE -eq 0) "MySQL readiness query failed"
    Assert-True ($mysqlResult -eq "1") "MySQL readiness query did not return 1"
    $redisPassword = Get-EnvValue "REDIS_PASSWORD"
    $redisResult = (& docker compose --env-file $EnvFile exec -T redis redis-cli -a $redisPassword --no-auth-warning ping 2>$null).Trim()
    Assert-True ($redisResult -eq "PONG") "Redis readiness query failed"
    (& docker compose --env-file $EnvFile exec -T minio curl --fail --silent http://localhost:9000/minio/health/ready 2>$null) | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) "MinIO readiness query failed"
    (& docker compose --env-file $EnvFile exec -T backend curl --fail --silent http://localhost:8080/actuator/health/liveness 2>$null) | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) "Backend health endpoint failed"
    $frontendResponse = Invoke-WebRequest -UseBasicParsing -Uri $frontendRoot -ErrorAction Stop
    Assert-True ([int]$frontendResponse.StatusCode -eq 200) "Frontend HTTP check did not return 200"
    Write-Output "[PASS] MySQL, Redis, MinIO, backend health endpoint and frontend HTTP 200"

    Write-Stage "Compose log classification"
    $logs = (Invoke-Compose @("logs", "--no-color")) -join "`n"
    $blockerMatches = [regex]::Matches($logs, "(?im)(migration failure|flyway.*(fail|error)|application failed|failed to start|connection refused|redis unavailable|minio unavailable|stack trace)")
    Assert-True ($blockerMatches.Count -eq 0) "Compose logs contain $($blockerMatches.Count) startup blocker pattern(s)"
    Write-Output "[PASS] no startup blocker patterns found in Compose logs"

    Write-Stage "Authentication and organization scope"
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $loginResponse = Invoke-WebRequest -UseBasicParsing -WebSession $session -Method Post `
        -Uri "$BaseUrl/auth/login" -ContentType "application/json" `
        -Body (@{ email = $bootstrapEmail; password = $bootstrapPassword } | ConvertTo-Json -Compress) `
        -ErrorAction Stop
    $login = $loginResponse.Content | ConvertFrom-Json
    Assert-True (-not [string]::IsNullOrWhiteSpace($login.accessToken)) "Login did not return an access token"
    Assert-True ([int]$login.expiresIn -eq 900) "Login access-token lifetime was not 900 seconds"
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    $me = Invoke-Json "Get" "$BaseUrl/auth/me" $headers $null $session
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$me.organizationId)) "/auth/me did not return organization context"
    foreach ($permission in @("PROVIDER_ACCOUNT_MANAGE", "EVIDENCE_UPLOAD_PROVIDER", "IMPORT_CONFIRM", "COST_READ", "EXPENSE_CREATE_OWN", "EXPENSE_SUBMIT_OWN", "AUDIT_READ")) {
        Assert-True ($me.permissions -contains $permission) "/auth/me missing expected permission $permission"
    }
    Write-Output "[PASS] login, /auth/me, organization scope and required permissions"

    Write-Stage "Workbench read"
    $workbench = Invoke-Json "Get" "$BaseUrl/workbench" $headers $null $session
    Assert-True ($null -ne $workbench.PSObject.Properties["costByProvider"]) "Workbench schema missing costByProvider"
    Assert-True ($null -ne $workbench.PSObject.Properties["pendingApprovals"]) "Workbench schema missing pendingApprovals"
    Write-Output "[PASS] workbench returned the organization-scoped response schema"

    Write-Stage "Provider account and DeepSeek import"
    $provider = Invoke-Json "Post" "$BaseUrl/provider-accounts" $headers @{
        providerCode = "DEEPSEEK"
        displayName = $smokeProviderDisplayName
        externalAccountRef = $smokeProviderExternalRef
    } $session
    $providerAccountId = [long]$provider.id
    Assert-True ($providerAccountId -gt 0) "Provider account creation did not return an id"
    $zipPath = New-DeepSeekSmokeZip $smokeBusinessDates
    $upload = Invoke-MultipartJson "$BaseUrl/provider-imports" $login.accessToken $zipPath $providerAccountId
    $importId = [long]$upload.importBatchId
    Assert-True ($upload.batchStatus -eq "PENDING") "Provider upload did not enter PENDING"

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        $import = Invoke-Json "Get" "$BaseUrl/imports/$importId" $headers $null $session
        if ($import.status -eq "FAILED") {
            throw "Provider import worker failed"
        }
    } while ($import.status -ne "READY_FOR_REVIEW" -and [DateTime]::UtcNow -lt $deadline)
    Assert-True ($import.status -eq "READY_FOR_REVIEW") "Provider import did not reach READY_FOR_REVIEW"
    $confirmed = Invoke-Json "Post" "$BaseUrl/imports/$importId/confirm" ($headers + @{ "Idempotency-Key" = "compose-smoke-confirm-$([guid]::NewGuid().ToString('N'))" }) $null $session
    Assert-True ($confirmed.status -eq "CONFIRMED") "Provider import confirm did not return CONFIRMED"
    $charges = Invoke-Json "Get" "$BaseUrl/costs/charges?page=0&size=50" $headers $null $session
    $deepSeekCharge = @($charges.items | Where-Object { $_.providerCode -eq "DEEPSEEK" }) | Select-Object -First 1
    Assert-True ($null -ne $deepSeekCharge) "Canonical DeepSeek charge was not visible through the API"
    Write-Output "[PASS] DeepSeek synthetic upload, worker READY_FOR_REVIEW, confirm and canonical charge read"

    Write-Stage "Employee expense submit"
    $expense = Invoke-Json "Post" "$BaseUrl/expenses" ($headers + @{ "Idempotency-Key" = "compose-smoke-expense-create-$([guid]::NewGuid().ToString('N'))" }) @{
        expenseDate = $smokeBusinessDates.ExpenseDate
        amount = "12.34000000"
        currency = "CNY"
    } $session
    $expenseId = [long]$expense.id
    Assert-True ($expense.status -eq "DRAFT") "Expense create did not return DRAFT"
    $evidenceRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-costops-expense-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
    $evidencePath = Join-Path $evidenceRoot "synthetic-receipt.txt"
    [System.IO.File]::WriteAllText($evidencePath, "synthetic expense evidence; no real user data")
    try {
        Add-Type -AssemblyName System.Net.Http
        $client = [System.Net.Http.HttpClient]::new()
        $stream = [System.IO.File]::OpenRead($evidencePath)
        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        try {
            $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $login.accessToken)
            $fileContent = [System.Net.Http.StreamContent]::new($stream)
            $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("text/plain")
            $multipart.Add($fileContent, "file", "synthetic-receipt.txt")
            $multipart.Add([System.Net.Http.StringContent]::new("0"), "expectedVersion")
            $evidenceResponse = $client.PostAsync("$BaseUrl/expenses/$expenseId/evidence", $multipart).GetAwaiter().GetResult()
            Assert-True ($evidenceResponse.IsSuccessStatusCode) "Expense evidence upload failed with HTTP $([int]$evidenceResponse.StatusCode)"
        } finally {
            $multipart.Dispose()
            $stream.Dispose()
            $client.Dispose()
        }
    } finally {
        Remove-Item -LiteralPath $evidenceRoot -Recurse -Force
    }
    $submitted = Invoke-Json "Post" "$BaseUrl/expenses/$expenseId/submit" ($headers + @{ "Idempotency-Key" = "compose-smoke-expense-submit-$([guid]::NewGuid().ToString('N'))" }) @{ expectedVersion = 1 } $session
    Assert-True ($submitted.status -eq "SUBMITTED") "Expense submit did not return SUBMITTED"
    Write-Output "[PASS] synthetic employee expense create, evidence upload and submit"

    Write-Stage "Audit query"
    $audit = Invoke-Json "Get" "$BaseUrl/audit-events?orgId=$($me.organizationId)&page=0&size=100" $headers $null $session
    Assert-True ([int]$audit.totalElements -ge 1) "Audit query returned no smoke-generated events"
    Write-Output "[PASS] AUDIT_READ query returned a PageResponse with smoke events"

    Write-Stage "Smoke completed"
    Write-Output "SMOKE_V1_PASS"
} finally {
    if ($zipPath -and (Test-Path -LiteralPath $zipPath)) {
        Remove-Item -LiteralPath $zipPath -Force
    }
}
