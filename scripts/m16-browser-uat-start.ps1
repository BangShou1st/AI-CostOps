<#
 .SYNOPSIS
     M16 Browser-UAT deterministic acceptance startup (harness-only).
 .DESCRIPTION
     One idempotent entry point for the isolated Browser acceptance runtime
     on the durable m16-accept-net network. It never touches product source,
     never deletes volumes or DB data, never clears Redis keys, never prints
     secrets, and never uses hardcoded container IPs.
     Image freshness is tracked by the m16.tree label (exact git HEAD the
     image was built from): images whose label differs from HEAD are rebuilt
     from the tracked tree. (Image timestamps are NOT a freshness signal:
     the R2 images were built from the pre-commit worktree and are content
     identical to the sealed HEAD.)
     Backend runtime wiring (isolated loopback acceptance only, production
     defaults untouched): preserves the container allow-origin + insecure-
     cookie runtime config, guarantees AICOSTOPS_ALLOW_PUBLIC_REGISTRATION
     (required so R3 identities are provisioned through the governed
     registration API), and optionally pins the registration org slug via
     -PublicRegistrationOrgSlug. The backend container carries the stable
     Docker DNS alias "backend" required by the frontend nginx upstream
     (http://backend:8080). The stack starts in dependency order and the
     run proves GREEN (DNS + frontend + proxy + readiness).
     Browser entrypoint after GREEN: http://127.0.0.1:18082 (use this single
     hostname for the whole session; the refresh cookie is host-scoped).
     Gate discipline: readiness helpers signal only through the sticky
     script-scope flag (Fail is sticky; success never clears it), so no gate
     can pass vacuously and native exit codes are read before any pipeline.
 .EXAMPLE
     ./scripts/m16-browser-uat-start.ps1
 .EXAMPLE
     ./scripts/m16-browser-uat-start.ps1 -PublicRegistrationOrgSlug m16-uat-browser-r3-20260909000000
 #>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [string]$PublicRegistrationOrgSlug = "",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$NetworkName = "m16-accept-net",
    [int]$WaitSeconds = 300
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$script:failures = @()
$script:gateOk = $true
function Fail([string]$Message) {
    Write-Output ("[M16-BROWSER-START-RED] " + $Message)
    $script:failures += $Message
    $script:gateOk = $false
}
function Info([string]$Message) { Write-Output ("[M16-BROWSER-START] " + $Message) }
function Reset-Gate() { $script:gateOk = $true }
function Get-TreeLabel([string]$Tag) {
    return (docker inspect $Tag --format '{{index .Config.Labels "m16.tree"}}' 2>$null | Out-String).Trim()
}
function Get-ContainerEnv([string]$Name) {
    return @(docker inspect $Name --format "{{range .Config.Env}}{{println .}}{{end}}" 2>$null | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" })
}
function Test-TcpPort([string]$TargetHost, [int]$Port, [int]$TimeoutMs = 2000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch { return $false } finally { $client.Close() }
}
function Wait-Http([string]$Name, [string]$Url, [int]$TimeoutSec) {
    Info ("waiting: " + $Name + " " + $Url)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing -SkipHttpErrorCheck
            if ($r.StatusCode -lt 500) { Info ($Name + " HTTP " + $r.StatusCode); return }
        } catch { Start-Sleep 2 }
        Start-Sleep 5
    }
    Fail ($Name + " not ready: " + $Url)
}
function Get-ResponseText($Response) {
    if ($null -eq $Response.Content) { return "" }
    if ($Response.Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Response.Content) }
    return ($Response.Content | Out-String)
}
Push-Location $RepoRoot
try {
    $head = (git -C $RepoRoot rev-parse HEAD 2>&1 | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($head) -or $head -match "fatal") { Fail "cannot resolve git HEAD."; throw "prerequisite failed" }
    $headShort = $head.Substring(0, 7)
    Info ("repo HEAD=" + $headShort)
    $null = docker info 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "docker is unavailable."; throw "prerequisite failed" }
    foreach ($durable in @("m16-mysql-accept", "m16-redis-accept")) {
        $exists = (docker inspect $durable --format "{{.Name}}" 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($exists)) { Fail ("durable container missing: " + $durable) }
    }
    foreach ($img in @("python:3.11-alpine", "prom/prometheus:v2.54.1")) {
        $exists = (docker images -q $img 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($exists)) { Fail ("base image missing: " + $img) }
    }
    if ($script:failures.Count -gt 0) { throw "prerequisites failed" }
    $net = (docker network inspect $NetworkName --format "{{.Name}}" 2>$null | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($net)) {
        Info ("creating network " + $NetworkName)
        $null = docker network create $NetworkName 2>&1
        if ($LASTEXITCODE -ne 0) { Fail "cannot create network"; throw "network failed" }
    } else { Info ("network present: " + $NetworkName) }
    $modules = @(
        @{ Name = "backend"; Tag = "ai-costops-backend:m16"; Path = "backend" },
        @{ Name = "frontend"; Tag = "ai-costops-frontend:m16"; Path = "frontend" },
        @{ Name = "gateway"; Tag = "ai-costops-gateway:m16"; Path = "gateway" }
    )
    foreach ($m in $modules) {
        $label = Get-TreeLabel $m.Tag
        $needBuild = $Rebuild.IsPresent -or ([string]::IsNullOrWhiteSpace($label)) -or ($label -ne $head)
        if ($needBuild) {
            if ([string]::IsNullOrWhiteSpace($label)) { Info ("image unlabeled, building " + $m.Tag) }
            elseif ($label -ne $head) { Info ("image label mismatch, rebuilding " + $m.Tag) }
            else { Info ("force rebuild " + $m.Tag) }
            $buildOut = docker build --label ("m16.tree=" + $head) -t $m.Tag ("./" + $m.Path) 2>&1
            $buildCode = $LASTEXITCODE
            foreach ($line in ($buildOut | Select-Object -Last 3)) { Info $line.ToString().Trim() }
            if ($buildCode -ne 0) { Fail ("docker build failed: " + $m.Tag + " (exit " + $buildCode + ")"); continue }
            $after = Get-TreeLabel $m.Tag
            if ($after -ne $head) { Fail ("image label not applied: " + $m.Tag) }
            else { Info ("image rebuilt+labeled: " + $m.Tag) }
        } else { Info ("image fresh (label=" + $headShort + "): " + $m.Tag) }
    }
    if ($script:failures.Count -gt 0) { throw "image build failed" }
    $specs = @(
        @{ Container = "m16-backend-accept"; Image = "ai-costops-backend:m16"; HostPort = "18080"; CPort = "8080"; Alias = "backend" },
        @{ Container = "m16-frontend-accept"; Image = "ai-costops-frontend:m16"; HostPort = "18082"; CPort = "8080"; Alias = "" },
        @{ Container = "m16-gateway-accept"; Image = "ai-costops-gateway:m16"; HostPort = "18081"; CPort = "8081"; Alias = "" }
    )
    foreach ($s in $specs) {
        $cid = (docker inspect $s.Container --format "{{.Id}}" 2>$null | Out-String).Trim()
        if ([string]::IsNullOrWhiteSpace($cid)) { Fail ("stateless container missing: " + $s.Container); continue }
        $runningImage = (docker inspect $s.Container --format "{{.Image}}" 2>$null | Out-String).Trim()
        $currentImage = (docker inspect $s.Image --format "{{.Id}}" 2>$null | Out-String).Trim()
        $oldEnv = Get-ContainerEnv $s.Container
        $desiredEnv = @($oldEnv)
        if ($s.Container -eq "m16-backend-accept") {
            if ($oldEnv.Count -eq 0) { Fail "cannot read backend runtime env; refusing reconcile."; continue }
            $keys = @($oldEnv | ForEach-Object { ($_ -split "=", 2)[0] })
            if ($keys -notcontains "AICOSTOPS_ALLOW_PUBLIC_REGISTRATION") {
                $desiredEnv += "AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=true"
                Info "backend: wiring runtime-only AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=true (isolated acceptance only)"
            } elseif ($oldEnv -notcontains "AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=true") {
                Fail "backend has explicit AICOSTOPS_ALLOW_PUBLIC_REGISTRATION != true; refusing to override."
                continue
            }
            if ($PublicRegistrationOrgSlug -ne "") {
                $slugLine = "PUBLIC_REGISTRATION_ORG_SLUG=" + $PublicRegistrationOrgSlug
                $hasSlug = @($oldEnv | Where-Object { $_ -like "PUBLIC_REGISTRATION_ORG_SLUG=*" })
                if ($hasSlug.Count -eq 0) {
                    $desiredEnv += $slugLine
                    Info ("backend: wiring registration org slug " + $PublicRegistrationOrgSlug)
                } elseif ($hasSlug[0] -ne $slugLine) {
                    $desiredEnv = @($desiredEnv | Where-Object { $_ -notlike "PUBLIC_REGISTRATION_ORG_SLUG=*" }) + @($slugLine)
                    Info ("backend: updating registration org slug " + $PublicRegistrationOrgSlug)
                }
            }
        }
        $envChanged = (Compare-Object $oldEnv $desiredEnv -SyncWindow 0) -ne $null
        if (($runningImage -ne $currentImage) -or $envChanged) {
            Info ("recreating " + $s.Container + " from " + $s.Image)
            $envArgs = @()
            foreach ($line in $desiredEnv) { $envArgs += "-e"; $envArgs += $line }
            $null = docker rm -f $s.Container 2>$null
            if ($s.Alias -ne "") {
                $null = docker create --name $s.Container --network $NetworkName --network-alias $s.Alias -p ("127.0.0.1:" + $s.HostPort + ":" + $s.CPort) @envArgs $s.Image 2>&1
            } else {
                $null = docker create --name $s.Container --network $NetworkName -p ("127.0.0.1:" + $s.HostPort + ":" + $s.CPort) @envArgs $s.Image 2>&1
            }
            if ($LASTEXITCODE -ne 0) { Fail ("cannot recreate " + $s.Container) }
        } else { Info ("container current: " + $s.Container) }
    }
    if ($script:failures.Count -gt 0) { throw "container reconcile failed" }
    $aliases = (docker inspect m16-backend-accept --format "{{range .NetworkSettings.Networks}}{{json .Aliases}}{{end}}" 2>$null | Out-String).Trim()
    if ($aliases -notmatch "backend") {
        Info "attaching stable DNS alias backend to m16-backend-accept"
        $null = docker network disconnect $NetworkName m16-backend-accept 2>$null
        $null = docker network connect --alias backend $NetworkName m16-backend-accept 2>&1
        if ($LASTEXITCODE -ne 0) { Fail "cannot attach DNS alias backend"; throw "alias failed" }
    } else { Info "DNS alias present: backend" }
    foreach ($c in @("m16-mysql-accept", "m16-redis-accept")) { $null = docker start $c 2>$null }
    Info "waiting for MySQL/Redis ports"
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ((Test-TcpPort "127.0.0.1" 13307) -and (Test-TcpPort "127.0.0.1" 16379)) { break }
        Start-Sleep 3
    }
    if (-not (Test-TcpPort "127.0.0.1" 13307)) { Fail "mysql port 13307 not reachable" }
    if (-not (Test-TcpPort "127.0.0.1" 16379)) { Fail "redis port 16379 not reachable" }
    if ($script:failures.Count -gt 0) { throw "data services failed" }
    foreach ($c in @("m16-backend-accept", "m16-gateway-accept", "m16-mock-provider")) { $null = docker start $c 2>$null }
    Reset-Gate
    Wait-Http "backend-liveness" "http://127.0.0.1:18080/actuator/health/liveness" $WaitSeconds
    Wait-Http "gateway-liveness" "http://127.0.0.1:18081/actuator/health/liveness" $WaitSeconds
    Wait-Http "mock-health" "http://127.0.0.1:18089/health" 120
    if (-not $script:gateOk) { throw "readiness failed" }
    foreach ($c in @("m16-frontend-accept", "m16-prometheus")) {
        $exists = (docker inspect $c --format "{{.Name}}" 2>$null | Out-String).Trim()
        if (-not [string]::IsNullOrWhiteSpace($exists)) { $null = docker start $c 2>$null }
    }
    Reset-Gate
    Wait-Http "frontend" "http://127.0.0.1:18082/" 120
    if (-not $script:gateOk) { throw "frontend not ready" }
    $dnsOut = docker run --rm --network $NetworkName ai-costops-frontend:m16 getent hosts backend 2>&1
    $dnsCode = $LASTEXITCODE
    $dns = ($dnsOut | Out-String).Trim()
    if ($dnsCode -ne 0 -or [string]::IsNullOrWhiteSpace($dns)) { Fail "Docker DNS: backend unresolved" }
    else { Info "Docker DNS GREEN: backend resolves from frontend network context" }
    try {
        $evil = Invoke-WebRequest -Uri "http://127.0.0.1:18082/api/v1/auth/refresh" -Method Post -Headers @{ Origin = "https://evil.example" } -TimeoutSec 15 -UseBasicParsing -SkipHttpErrorCheck
        $body = Get-ResponseText $evil
        if ($evil.StatusCode -eq 403 -and $body -match "FORBIDDEN") { Info "proxy+Origin GREEN: evil Origin 403 FORBIDDEN via frontend" }
        else { Fail ("proxy check unexpected HTTP " + $evil.StatusCode) }
    } catch { Fail ("proxy check failed: " + $_.Exception.Message) }
    if ($script:failures.Count -gt 0) { throw "GREEN proofs failed" }
    Info "M16-BROWSER-START-GREEN entrypoint=http://127.0.0.1:18082"
    exit 0
} catch {
    if ($script:failures.Count -eq 0) { Fail $_.Exception.Message }
    Write-Output "[M16-BROWSER-START-RED] M16_BROWSER_START_RED"
    exit 1
} finally { Pop-Location }
