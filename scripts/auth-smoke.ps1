param([string]$BaseUrl = "http://localhost:8080/api/v1", [string]$EnvFile = ".env.example")

$ErrorActionPreference = "Stop"

function Get-EnvValue([string]$Name) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name in $EnvFile" }
    return ($line -split '=', 2)[1]
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-ProblemCode($ErrorRecord) {
    if ($ErrorRecord.ErrorDetails.Message) {
        try { return (($ErrorRecord.ErrorDetails.Message | ConvertFrom-Json).code) } catch { }
    }
    $response = $ErrorRecord.Exception.Response
    if (-not $response) { return $null }
    if ($response.Content) { return (($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json).code) }
    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
    try { return (($reader.ReadToEnd() | ConvertFrom-Json).code) } finally { $reader.Dispose() }
}

$origin = ([uri]$BaseUrl).GetLeftPart([System.UriPartial]::Authority)
$email = "auth-smoke-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())@example.test"
$oldPassword = "Smoke-password-1"
$newPassword = "Smoke-password-2"

$orgCount = docker compose --env-file $EnvFile exec -T mysql mysql "-u$(Get-EnvValue 'MYSQL_USER')" "-p$(Get-EnvValue 'MYSQL_PASSWORD')" "$(Get-EnvValue 'MYSQL_DATABASE')" -Nse "SELECT COUNT(*) FROM organization WHERE slug='local-dev' AND status='ACTIVE'"
Assert-True ([int]$orgCount -eq 1) "local-dev organization was not bootstrapped"

$register = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/register" -ContentType "application/json" -Body (@{ email=$email; displayName="Auth Smoke"; password=$oldPassword } | ConvertTo-Json)
Assert-True ($register.userId -match '^\d+$') "registration did not return a string ID"

$loginResponse = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/auth/login" -SessionVariable authSession -ContentType "application/json" -Body (@{ email=$email; password=$oldPassword } | ConvertTo-Json)
$login = $loginResponse.Content | ConvertFrom-Json
Assert-True ($login.expiresIn -eq 900) "login did not return the configured access lifetime"
$oldAccess = $login.accessToken
$oldRefresh = [regex]::Match($loginResponse.Headers['Set-Cookie'], 'aicostops_refresh=([^;]+)').Groups[1].Value
Assert-True (-not [string]::IsNullOrWhiteSpace($oldRefresh)) "login did not set the refresh cookie"

$headers = @{ Authorization = "Bearer $oldAccess" }
$me = Invoke-RestMethod -Method Get -Uri "$BaseUrl/auth/me" -Headers $headers
Assert-True ($me.email -eq $email) "/auth/me returned the wrong identity"

$refreshResponse = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/auth/refresh" -WebSession $authSession -Headers @{ Origin=$origin }
$refresh = $refreshResponse.Content | ConvertFrom-Json
Assert-True ($refresh.accessToken -ne $oldAccess) "refresh did not rotate the access JWT"
$currentRefresh = [regex]::Match($refreshResponse.Headers['Set-Cookie'], 'aicostops_refresh=([^;]+)').Groups[1].Value

# Replay uses an independent live session so it cannot revoke the session used to prove logout.
$replayLogin = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/auth/login" -SessionVariable replayAuthSession -ContentType "application/json" -Body (@{ email=$email; password=$oldPassword } | ConvertTo-Json)
$replayOldRefresh = [regex]::Match($replayLogin.Headers['Set-Cookie'], 'aicostops_refresh=([^;]+)').Groups[1].Value
Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/auth/refresh" -WebSession $replayAuthSession -Headers @{ Origin=$origin } | Out-Null
Start-Sleep -Seconds 11
Invoke-WebRequest -UseBasicParsing -Uri $origin -SessionVariable replaySession | Out-Null
$replaySession.Cookies.Add([System.Net.Cookie]::new('aicostops_refresh', $replayOldRefresh, '/api/v1/auth', ([uri]$BaseUrl).Host))
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/refresh" -WebSession $replaySession -Headers @{ Origin=$origin }
    throw "stale refresh replay was accepted"
} catch {
    Assert-True ((Get-ProblemCode $_) -eq 'AUTH_REFRESH_REPLAY') "stale refresh did not return AUTH_REFRESH_REPLAY"
}

Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/logout" -WebSession $authSession -Headers @{ Authorization="Bearer $oldAccess"; Origin=$origin }
$refreshRejected = $false
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/refresh" -Headers @{ Origin=$origin; Cookie="aicostops_refresh=$currentRefresh" }
} catch { $refreshRejected = $true }
Assert-True $refreshRejected "refresh succeeded after logout"

$forgot = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/password/forgot" -ContentType "application/json" -Body (@{ email=$email } | ConvertTo-Json)
Assert-True ($forgot.accepted -eq $true) "forgot password did not return the generic response"

$messagePath = (docker compose --env-file $EnvFile exec -T backend sh -c "grep -l 'email=$email' /var/lib/aicostops/mailbox/*.txt | tail -n 1").Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($messagePath)) "dev password reset delivery did not create a mailbox message"
$messageBody = docker compose --env-file $EnvFile exec -T backend sh -c "cat '$messagePath'"
$resetLinkLine = $messageBody | Where-Object { $_ -like 'resetLink=*' } | Select-Object -First 1
$resetLink = ($resetLinkLine -split '=', 2)[1]
$resetTokenMatch = [regex]::Match(([uri]$resetLink).Query, '(?:^|[?&])token=([^&]+)')
$resetToken = if ($resetTokenMatch.Success) {
    [uri]::UnescapeDataString($resetTokenMatch.Groups[1].Value.Replace('+', ' '))
} else { $null }
Assert-True (-not [string]::IsNullOrWhiteSpace($resetToken)) "dev mailbox reset link did not contain a token"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/password/reset" -ContentType "application/json" -Body (@{ token=$resetToken; newPassword=$newPassword } | ConvertTo-Json)

$oldAccessRejected = $false
try {
    Invoke-RestMethod -Method Get -Uri "$BaseUrl/auth/me" -Headers $headers
} catch { $oldAccessRejected = $true }
Assert-True $oldAccessRejected "old access JWT remained valid after password reset"
$newLogin = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType "application/json" -Body (@{ email=$email; password=$newPassword } | ConvertTo-Json)
Assert-True (-not [string]::IsNullOrWhiteSpace($newLogin.accessToken)) "new password login failed"

Write-Output "AUTH_SMOKE_PASS email=$email"
