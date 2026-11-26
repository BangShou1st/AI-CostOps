function Test-GatewayBudgetGrants([string]$Grants) {
    $budgetColumns = @()
    foreach ($line in ($Grants -split "`n")) {
        if ($line -match '(?i)\bON\s+`?[^.\s]+`?\.\*\s+TO\b' -and
            $line -match '(?i)\b(UPDATE|INSERT|DELETE|ALL)\b') {
            return $false
        }
        if ($line -notmatch '(?i)\bON\s+`?[^.\s]+`?\.`?(budget|billing_period)`?\s+TO\b') {
            continue
        }
        $table = $Matches[1]
        $privileges = [regex]::Match($line, '(?i)^GRANT\s+(.+?)\s+ON\b').Groups[1].Value
        if ($table -eq 'budget' -and $privileges -match '(?i)\bUPDATE\s*\(([^)]+)\)') {
            $budgetColumns += $Matches[1].Replace('`', '').Split(',').Trim().ToLowerInvariant()
            $privileges = $privileges -replace '(?i)\bUPDATE\s*\([^)]+\)', ''
        }
        if ($privileges -match '(?i)\b(UPDATE|INSERT|DELETE|ALL)\b') {
            return $false
        }
    }
    return (($budgetColumns | Sort-Object) -join ',') -eq 'reserved_amount,updated_at,version'
}
