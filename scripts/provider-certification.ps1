<#
.SYNOPSIS
AIC-082 real-but-redacted Provider import certification harness.

Runs one real Provider export through the live running stack's real import
pipeline (login -> provider account -> upload -> await attempt -> confirm) and
records a redacted certification record. The raw export must live OUTSIDE Git
under .local-provider-certification/input/ (or an explicitly approved external
local path); this script refuses to process any file git tracks or that lives
anywhere else inside the repository.

The script never prints or persists API keys, raw rows, account/invoice
identifiers, emails, prompts or responses. Evidence written under
.local-provider-certification/evidence/ contains only counts, aggregates,
fingerprints and numeric local identifiers.

.PARAMETER Provider
Lowercase provider code: deepseek, glm, kimi, mimo, openai.

.PARAMETER InputPath
Full path to the real Provider export. Must be git-ignored and untracked.

.PARAMETER BaseUrl
Local system API root, e.g. http://localhost:8080.

.PARAMETER Email / Password
Finance-admin credentials for the running stack. Defaults to the dev bootstrap
identity used by browser E2E (admin@example.test).

.PARAMETER EnvFile / ProjectName
Compose stack identity used for the read-only MySQL reconciliation query.

.PARAMETER EvidenceDir
Output directory for the raw evidence record (default
.local-provider-certification/evidence).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("deepseek", "glm", "kimi", "mimo", "openai")]
    [string]$Provider,

    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $false)]
    [string]$BaseUrl = "http://localhost:8080",

    [Parameter(Mandatory = $false)]
    [string]$Email = "admin@example.test",

    [Parameter(Mandatory = $false)]
    [string]$Password = "change-me-local-only",

    [Parameter(Mandatory = $false)]
    [string]$EnvFile = ".env",

    [Parameter(Mandatory = $false)]
    [string]$ProjectName = "ai-costops",

    [Parameter(Mandatory = $false)]
    [string]$EvidenceDir = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Stage([string]$Name) { Write-Output "[PROVIDER-CERT] $Name" }
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Get-EnvValue([string]$Name) {
    $escaped = [regex]::Escape($Name)
    $line = Get-Content -LiteralPath $EnvFile |
        Where-Object { $_ -match "^$escaped=" } |
        Select-Object -First 1
    if (-not $line) { throw "Missing $Name in $EnvFile" }
    return (($line -split "=", 2)[1]).Trim()
}
function Normalize-Header([string]$raw) {
    if ([string]::IsNullOrWhiteSpace($raw)) { return "" }
    $stripped = if ($raw.StartsWith([char]0xFEFF)) { $raw.Substring(1) } else { $raw }
    $nfkc = $stripped.Normalize([System.Text.NormalizationForm]::FormKC)
    return ($nfkc.Trim() -replace "\s+", " ")
}
function Read-MimoWorkbookSummary {
    # In-memory read of a MiMo usage workbook (xlsx). Returns only aggregates and
    # counts: never cell values. Sums the "Consumed Amount" column of both the
    # "Model usage detail" and "Plugin usage detail" sheets and counts data rows.
    param([string]$InputFile)
    $modelSheet = "Model usage detail"
    $pluginSheet = "Plugin usage detail"
    $amountHeader = "Consumed Amount"
    $rowCount = 0
    $aggregate = [decimal]0
    $currencies = @{}
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($InputFile)
    try {
        $workbookEntry = $zip.Entries | Where-Object { $_.FullName -eq "xl/workbook.xml" }
        Assert-True ($null -ne $workbookEntry) "xl/workbook.xml missing from workbook"
        $reader = [System.IO.StreamReader]::new($workbookEntry.Open())
        try { $workbookXml = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $xml = [System.Xml.XmlDocument]::new()
        $xml.LoadXml($workbookXml)
        $ns = [System.Xml.XmlNamespaceManager]::new($xml.NameTable)
        $ns.AddNamespace("m", "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
        $sheetNames = @($xml.SelectNodes("//m:sheets/m:sheet", $ns) | ForEach-Object { $_.GetAttribute("name") })

        # shared strings (header texts and string cell values)
        $shared = @()
        $ssEntry = $zip.Entries | Where-Object { $_.FullName -eq "xl/sharedStrings.xml" }
        if ($null -ne $ssEntry) {
            $r = [System.IO.StreamReader]::new($ssEntry.Open())
            try { $ssXml = $r.ReadToEnd() } finally { $r.Dispose() }
            $ssDoc = [System.Xml.XmlDocument]::new()
            $ssDoc.LoadXml($ssXml)
            $ns2 = [System.Xml.XmlNamespaceManager]::new($ssDoc.NameTable)
            $ns2.AddNamespace("m", "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
            foreach ($si in $ssDoc.SelectNodes("//m:sst/m:si", $ns2)) {
                $text = ""
                foreach ($t in $si.SelectNodes(".//m:t", $ns2)) { $text += $t.InnerText }
                $shared += $text
            }
        }

        foreach ($sheetName in $sheetNames) {
            $idx = [array]::IndexOf($sheetNames, $sheetName) + 1
            if ($sheetName -ne $modelSheet -and $sheetName -ne $pluginSheet) { continue }
            $sheetEntry = $zip.Entries | Where-Object { $_.FullName -eq "xl/worksheets/sheet$idx.xml" }
            if ($null -eq $sheetEntry) { continue }
            $r = [System.IO.StreamReader]::new($sheetEntry.Open())
            try { $sheetXml = $r.ReadToEnd() } finally { $r.Dispose() }
            $sh = [System.Xml.XmlDocument]::new()
            $sh.LoadXml($sheetXml)
            $ns3 = [System.Xml.XmlNamespaceManager]::new($sh.NameTable)
            $ns3.AddNamespace("m", "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
            $rows = @($sh.SelectNodes("//m:sheetData/m:row", $ns3))
            if ($rows.Count -eq 0) { continue }
            # first row = headers
            $headers = @()
            foreach ($cell in $rows[0].SelectNodes("./m:c", $ns3)) {
                $cellText = Resolve-CellText -Cell $cell -Ns $ns3 -Shared $shared
                $headers += $cellText
            }
            $amountIndex = -1
            $currencyIndex = -1
            for ($c = 0; $c -lt $headers.Count; $c++) {
                $norm = Normalize-Header $headers[$c]
                if ($norm -eq (Normalize-Header $amountHeader)) { $amountIndex = $c }
                if ($norm -eq "currency") { $currencyIndex = $c }
            }
            if ($amountIndex -lt 0) { continue }
            for ($rIdx = 1; $rIdx -lt $rows.Count; $rIdx++) {
                $vals = @()
                foreach ($cell in $rows[$rIdx].SelectNodes("./m:c", $ns3)) {
                    $vals += Resolve-CellText -Cell $cell -Ns $ns3 -Shared $shared
                }
                if ($amountIndex -ge $vals.Count) { continue }
                $amountText = [string]$vals[$amountIndex]
                if ([string]::IsNullOrWhiteSpace($amountText)) { continue }
                $amount = [decimal]0
                $cleaned = $amountText -replace ",", ""
                if ([decimal]::TryParse($cleaned, [System.Globalization.NumberStyles]::Number,
                        [System.Globalization.CultureInfo]::InvariantCulture, [ref]$amount)) {
                    $aggregate += $amount
                    $rowCount++
                }
                if ($currencyIndex -ge 0 -and $currencyIndex -lt $vals.Count) {
                    $ccy = ([string]$vals[$currencyIndex]).Trim()
                    if ($ccy) { $currencies[$ccy] = $true }
                }
            }
        }
    } finally {
        $zip.Dispose()
    }
    return [pscustomobject]@{ rows = $rowCount; aggregate = $aggregate; currencies = $currencies }
}
function Resolve-CellText {
    # Inline string vs shared-indexed string vs raw value for one <c> cell.
    param($Cell, $Ns, [string[]]$Shared)
    $text = ""
    $isNode = $Cell.SelectSingleNode("./m:is", $Ns)
    if ($null -ne $isNode) {
        foreach ($t in $isNode.SelectNodes(".//m:t", $Ns)) { $text += $t.InnerText }
        return $text
    }
    $vNode = $Cell.SelectSingleNode("./m:v", $Ns)
    if ($null -eq $vNode) { return "" }
    if ($Cell.GetAttribute("t") -eq "s") {
        $sharedIdx = [int]$vNode.InnerText
        if ($sharedIdx -ge 0 -and $sharedIdx -lt $Shared.Count) { return $Shared[$sharedIdx] }
        return ""
    }
    return $vNode.InnerText
}

# Walk up from the script directory until the git repository root (.git) is found.
$repoRoot = $PSScriptRoot
while (-not (Test-Path (Join-Path $repoRoot ".git"))) {
    $parent = Split-Path -Parent $repoRoot
    if ($parent -eq $repoRoot -or [string]::IsNullOrWhiteSpace($parent)) {
        throw "Could not locate repository root from $PSScriptRoot"
    }
    $repoRoot = $parent
}
$repoRoot = (Resolve-Path $repoRoot).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $EvidenceDir = Join-Path $repoRoot ".local-provider-certification\evidence"
}
$EvidenceDir = [System.IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null

# ---------------------------------------------------------------- Step 1
Write-Stage "Fail-fast input gate"
Assert-True (Test-Path -LiteralPath $InputPath -PathType Leaf) "Input file does not exist: $InputPath"
$inputFile = (Resolve-Path -LiteralPath $InputPath).Path
# Must not be tracked by git (git ls-files --error-unmatch exits 0 when tracked).
& git -C $repoRoot ls-files --error-unmatch -- $inputFile 2>$null | Out-Null
Assert-True ($LASTEXITCODE -ne 0) "Refusing to certify a git-tracked file: $inputFile"
# Must be inside the git ignore zone.
& git -C $repoRoot check-ignore --quiet -- $inputFile
Assert-True ($LASTEXITCODE -eq 0) "Input is not git-ignored: $inputFile (add .local-provider-certification/ to .gitignore)"
# Must not be a repository fixture: inside the repo it may only live under
# .local-provider-certification/input; anything else inside the repo is refused.
$inRepo = $inputFile.StartsWith($repoRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
$allowedInputRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".local-provider-certification\input"))
if ($inRepo) {
    Assert-True ($inputFile.StartsWith($allowedInputRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) `
        "Refusing in-repo input outside .local-provider-certification/input: $inputFile"
}

$fileInfo = Get-Item -LiteralPath $inputFile
$fileType = $fileInfo.Extension.ToLowerInvariant()
$assertedProvider = $Provider.ToLowerInvariant()
$mappedType = @{
    deepseek = ".zip"; glm = ".xlsx"; kimi = ".xlsx"; mimo = ".xlsx"; openai = ".zip"
}[$assertedProvider]
Assert-True ($fileType -eq $mappedType) "Provider $assertedProvider expects '$mappedType' files but got '$fileType'"
# Never print raw contents: only the SHA-256 prefix and byte size leave this step.
$sha = (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant()
$shaPrefix = $sha.Substring(0, 12)
Write-Stage "Input gate OK: $($fileInfo.Length) bytes, $fileType, sha256 prefix $shaPrefix"

# ---------------------------------------------------------------- Step 2
Write-Stage "Source-side inspection and monetary aggregate (provider-aware, in-memory)"
$source = @{ rows = 0; aggregate = [decimal]0; currencies = @{} }
if ($assertedProvider -eq "mimo") {
    $source = Read-MimoWorkbookSummary -InputFile $inputFile
} else {
    Write-Output "[PROVIDER-CERT][INFO] Source aggregate extraction not implemented for provider '$assertedProvider'; counts come from the adapter response."
}
$sourceRows = $source.rows
$sourceAggregate = $source.aggregate
$sourceCurrency = ($source.currencies.Keys | Sort-Object) -join ","

# ---------------------------------------------------------------- Step 3
Write-Stage "Login to local stack"
$loginBody = @{ email = $Email; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" `
    -ContentType "application/json" -Body $loginBody
Assert-True (-not [string]::IsNullOrWhiteSpace($login.accessToken)) "Login returned no access token"
$bearer = @{ Authorization = "Bearer $($login.accessToken)" }

Write-Stage "Select or create the $assertedProvider provider account"
$page = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/provider-accounts?page=0&size=50" -Headers $bearer
$account = @($page.items) | Where-Object { $_.providerCode -eq $assertedProvider.ToUpperInvariant() } |
    Select-Object -First 1
if (-not $account) {
    $createBody = @{
        providerCode = $assertedProvider.ToUpperInvariant()
        displayName  = "AIC-082 $assertedProvider certification"
    } | ConvertTo-Json
    $account = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/provider-accounts" `
        -Headers $bearer -ContentType "application/json" -Body $createBody
}
$providerAccountId = [string]$account.id
Write-Stage "Provider account id $providerAccountId (numeric local identifier only)"

# ---------------------------------------------------------------- Step 4
Write-Stage "Upload and process the real $assertedProvider export"
$upload = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/provider-imports" `
    -Headers $bearer -Form @{
        file              = Get-Item -LiteralPath $inputFile
        providerAccountId = $providerAccountId
        sourceType        = "FILE_EXPORT"
    }
$importBatchId = [string]$upload.importBatchId
$latestAttemptId = [string]$upload.latestAttemptId
Write-Stage "Import batch $importBatchId, attempt $latestAttemptId"

# ---------------------------------------------------------------- Step 5
Write-Stage "Await attempt terminal state"
$attemptStatus = ""
$deadline = (Get-Date).AddHours(1)
do {
    Start-Sleep -Seconds 5
    $import = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/imports/$importBatchId" -Headers $bearer
    $attemptStatus = [string]$import.latestAttempt.status
} while ($attemptStatus -notin @("SUCCEEDED", "FAILED") -and (Get-Date) -lt $deadline)
if ($attemptStatus -ne "SUCCEEDED") {
    throw "Import attempt did not succeed (attemptStatus=$attemptStatus, errorCode=$($import.latestAttempt.errorCode))"
}
$parserVersion = [string]$import.latestAttempt.parserVersion
$schemaFingerprint = [string]$import.latestAttempt.schemaFingerprint
$detectedProvider = [string]$import.latestAttempt.detectedProviderCode
$recordsSeen = [long]$import.latestAttempt.recordsSeen
$recordsValid = [long]$import.latestAttempt.recordsValid
$warningCount = [long]$import.latestAttempt.warningCount
$errorCount = [long]$import.latestAttempt.errorCount
Write-Stage "Attempt SUCCEEDED: parser=$parserVersion rows=$recordsSeen validated=$recordsValid warnings=$warningCount"

Write-Stage "Confirm import $importBatchId (Idempotency-Key, IMPORT_CONFIRM)"
$confirmHeaders = @{ Authorization = "Bearer $($login.accessToken)"; "Idempotency-Key" = "aic082-$importBatchId-$(Get-Random)" }
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/imports/$importBatchId/confirm" `
    -Headers $confirmHeaders | Out-Null

# ---------------------------------------------------------------- Step 6
Write-Stage "Canonical monetary reconciliation (read-only MySQL query)"
$recon = [ordered]@{
    charge_count = 0
    charge_sum   = [decimal]0
    org_id       = ""
}
$mysqlUser = Get-EnvValue "MYSQL_USER"
$mysqlDatabase = Get-EnvValue "MYSQL_DATABASE"
$mysqlPassword = Get-EnvValue "MYSQL_PASSWORD"
# SQL travels as base64 to avoid Windows/PowerShell quoting issues entirely.
function Invoke-MysqlQuery([string]$Query) {
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Query))
    $lines = @(& docker compose --env-file $EnvFile -p $ProjectName exec -T `
            -e "MYSQL_PWD=$mysqlPassword" `
            mysql sh -c "echo $encoded | base64 -d | mysql -u$mysqlUser -N $mysqlDatabase" 2>$null |
            ForEach-Object { $_.Trim() })
    return $lines
}
$orgId = (Invoke-MysqlQuery "SELECT org_id FROM provider_account WHERE id=$providerAccountId") |
    Where-Object { $_ -match '^\d+$' } | Select-Object -First 1
Assert-True (-not [string]::IsNullOrWhiteSpace($orgId)) "Could not resolve org for provider account $providerAccountId"
$reconCells = [array](Invoke-MysqlQuery "SELECT COALESCE(SUM(cf.amount),0), COUNT(*) FROM charge_fact cf JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id WHERE rpr.import_attempt_id=$latestAttemptId")
if ($reconCells.Count -gt 0) {
    $parts = $reconCells[0] -split "\s+"
    if ($parts.Count -ge 2) {
        $recon.charge_sum = [decimal]$parts[0]
        $recon.charge_count = [long]$parts[1]
    }
}
$recon.org_id = $orgId
$difference = [decimal]$sourceAggregate - [decimal]$recon.charge_sum
Write-Stage "Canonical: charge_fact=$($recon.charge_count) sum=$($recon.charge_sum) diff=$difference"

# ---------------------------------------------------------------- Step 7
$testedSha = (& git -C $repoRoot rev-parse HEAD).Trim()
$now = (Get-Date).ToUniversalTime().ToString("o")
$result = if ($attemptStatus -eq "SUCCEEDED" -and $errorCount -eq 0) { "PASS" } else { "FAIL" }
$record = [ordered]@{
    provider                  = $assertedProvider
    parser_version            = $parserVersion
    tested_implementation_sha = $testedSha
    execution_timestamp       = $now
    base_url_class            = "local-stack"
    real_input                = $true
    input_tracked             = $false
    input_ignored             = $true
    input_type                = $fileType
    input_size_bytes          = $fileInfo.Length
    input_sha256_prefix       = $shaPrefix
    schema_fingerprint        = $schemaFingerprint
    detected_provider_code    = $detectedProvider
    source_rows               = $sourceRows
    source_monetary_aggregate = "$sourceAggregate"
    source_currency           = $sourceCurrency
    records_seen              = $recordsSeen
    records_valid             = $recordsValid
    warning_count             = $warningCount
    error_count               = $errorCount
    import_batch_id           = $importBatchId
    latest_attempt_id         = $latestAttemptId
    attempt_status            = $attemptStatus
    canonical_charge_count    = $recon.charge_count
    canonical_monetary_sum    = "$($recon.charge_sum)"
    reconciliation_difference = "$difference"
    secrets_included          = $false
    emails_included           = $false
    raw_rows_included         = $false
    prompts_included          = $false
    result                    = $result
}
$evidenceFile = Join-Path $EvidenceDir "provider-certification-$assertedProvider.json"
$record | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $evidenceFile -Encoding utf8
Write-Stage "Evidence record written to $evidenceFile"
Write-Stage "REDACTION REVIEW REQUIRED before any docs/03-acceptance copy (Step 5 of AIC-082)."
if ($result -eq "PASS") {
    Write-Output "REAL_PROVIDER_CERTIFICATION_PASS|provider=$assertedProvider|rows=$recordsValid|diff=$difference|evidence=$evidenceFile"
} else {
    Write-Output "REAL_PROVIDER_CERTIFICATION_FAIL|provider=$assertedProvider|attempt=$attemptStatus"
    exit 1
}