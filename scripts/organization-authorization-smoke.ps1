param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$EnvFile = ".env.example"
)

$ErrorActionPreference = "Stop"

function Get-EnvValue([string]$Name) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if (-not $line) { throw "Missing $Name in $EnvFile" }
    return ($line -split '=', 2)[1]
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-ProblemBody($ErrorRecord) {
    if ($ErrorRecord.ErrorDetails.Message) { return $ErrorRecord.ErrorDetails.Message }
    $response = $ErrorRecord.Exception.Response
    if (-not $response) { return $null }
    if ($response.Content) { return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-ProblemCode($ErrorRecord) {
    $body = Get-ProblemBody $ErrorRecord
    if ($body) {
        try { return (($body | ConvertFrom-Json).code) } catch { }
    }
    return $null
}

function Get-ProblemStatus($ErrorRecord) {
    $body = Get-ProblemBody $ErrorRecord
    if ($body) {
        try { return [int](($body | ConvertFrom-Json).status) } catch { }
    }
    $response = $ErrorRecord.Exception.Response
    if ($response -and $response.StatusCode) { return [int]$response.StatusCode }
    return $null
}

function Invoke-ApiJson([string]$Method, [string]$Uri, [hashtable]$Headers, $Body = $null) {
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
    }
    return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType "application/json" -Body ($Body | ConvertTo-Json)
}

function Assert-ApiError([string]$Method, [string]$Uri, [hashtable]$Headers, $Body, [int]$ExpectedStatus, [string]$ExpectedCode, [string]$Message) {
    try {
        Invoke-ApiJson $Method $Uri $Headers $Body | Out-Null
        throw "${Message}: request unexpectedly succeeded"
    } catch {
        if ($_.Exception.Message -like "*unexpectedly succeeded*") { throw }
        $status = Get-ProblemStatus $_
        $code = Get-ProblemCode $_
        Assert-True ($status -eq $ExpectedStatus) "$Message (expected HTTP $ExpectedStatus, got '$status')"
        Assert-True ($code -eq $ExpectedCode) "$Message (expected $ExpectedCode, got '$code')"
    }
}

function Invoke-Mysql([string]$Sql) {
    docker compose --env-file $EnvFile exec -T mysql mysql "-u$(Get-EnvValue 'MYSQL_USER')" "-p$(Get-EnvValue 'MYSQL_PASSWORD')" "$(Get-EnvValue 'MYSQL_DATABASE')" -Nse $Sql
}

$origin = ([uri]$BaseUrl).GetLeftPart([System.UriPartial]::Authority)
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$adminEmail = "m1-admin-$stamp@example.test"
$memberEmail = "m1-member-$stamp@example.test"
$inviteEmail = "m1-invite-$stamp@example.test"
$password = "Smoke-password-1"

# Clear login rate-limit counters so repeated runs stay deterministic. Redis is
# a non-authoritative cache; clearing it never grants access.
$redisPassword = Get-EnvValue 'REDIS_PASSWORD'
docker compose --env-file $EnvFile exec -T redis sh -c "redis-cli -a '$redisPassword' --no-auth-warning KEYS 'aicostops:v1:ratelimit:login:*' | xargs -r redis-cli -a '$redisPassword' --no-auth-warning DEL" | Out-Null

# ---------------------------------------------------------------
# Bootstrap: public registration identifies the organization member,
# then ONE direct SQL insert grants ORG SYSTEM_ADMIN and bumps the
# security version. Everything after the re-login is HTTP-only.
# ---------------------------------------------------------------
$register = Invoke-ApiJson "Post" "$BaseUrl/auth/register" @{} @{ email=$adminEmail; displayName="M1 Admin"; password=$password }
Assert-True ($register.userId -match '^\d+$') "registration did not return a string user ID"
$adminUserId = $register.userId

$employeeLogin = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$adminEmail; password=$password }
$employeeMe = Invoke-ApiJson "Get" "$BaseUrl/auth/me" @{ Authorization = "Bearer $($employeeLogin.accessToken)" }
Assert-True ($employeeMe.organizationMemberId -match '^\d+$') "login did not expose the organization member ID"
$adminMemberId = $employeeMe.organizationMemberId

# The ONLY direct SQL in the acceptance flow: bootstrap the first test administrator.
$bootstrapSql = "INSERT INTO role_assignment (org_member_id, role_id, scope_type, scope_id, assigned_by, created_at) " +
    "SELECT om.id, r.id, 'ORG', om.org_id, NULL, NOW(6) FROM organization_member om " +
    "JOIN ``role`` r ON r.code = 'SYSTEM_ADMIN' WHERE om.user_id = $adminUserId; " +
    "UPDATE app_user SET security_version = security_version + 1, updated_at = NOW(6) WHERE id = $adminUserId;"
Invoke-Mysql $bootstrapSql | Out-Null

$adminLogin = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$adminEmail; password=$password }
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.accessToken)" }

# ---------------------------------------------------------------
# Assert-AdminPermissions: /auth/me exposes the M1 admin projection.
# ---------------------------------------------------------------
function Assert-AdminPermissions([string]$Name) {
    $me = Invoke-ApiJson "Get" "$BaseUrl/auth/me" $adminHeaders
    $expected = @('USER_READ','USER_MANAGE','USER_INVITE','ROLE_ASSIGN','ROLE_READ',
        'PROJECT_READ','PROJECT_MANAGE','PROJECT_MEMBER_MANAGE',
        'TEAM_READ','TEAM_MANAGE','COST_CENTER_READ','COST_CENTER_MANAGE',
        'PROVIDER_ACCOUNT_READ','PROVIDER_ACCOUNT_MANAGE')
    foreach ($code in $expected) {
        Assert-True ($me.permissions -contains $code) "${Name}: /auth/me does not expose $code"
    }
    return $me
}
$adminMe = Assert-AdminPermissions "Assert-AdminPermissions"
Assert-True ($adminMe.organizationMemberId -eq $adminMemberId) "Assert-AdminPermissions: identity changed after bootstrap"

# ---------------------------------------------------------------
# Wrong role: a plain EMPLOYEE cannot create master data.
# ---------------------------------------------------------------
$memberRegister = Invoke-ApiJson "Post" "$BaseUrl/auth/register" @{} @{ email=$memberEmail; displayName="M1 Member"; password=$password }
$memberUserId = $memberRegister.userId
$memberLogin = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$memberEmail; password=$password }
$memberHeaders = @{ Authorization = "Bearer $($memberLogin.accessToken)" }
$memberMe = Invoke-ApiJson "Get" "$BaseUrl/auth/me" $memberHeaders
$memberMemberId = $memberMe.organizationMemberId
Assert-True (-not ($memberMe.permissions -contains 'PROJECT_MANAGE')) "Assert-WrongRole403: EMPLOYEE unexpectedly has PROJECT_MANAGE"
Assert-ApiError "Post" "$BaseUrl/projects" $memberHeaders @{ code="EMP-PROJ"; name="Employee Project" } 403 "FORBIDDEN" "Assert-WrongRole403: EMPLOYEE project create was not 403 FORBIDDEN"

# ---------------------------------------------------------------
# Master data created by the administrator (all through HTTP).
# ---------------------------------------------------------------
$project1 = Invoke-ApiJson "Post" "$BaseUrl/projects" $adminHeaders @{ code="SMOKE-P1-$stamp"; name="Smoke Project One" }
$project2 = Invoke-ApiJson "Post" "$BaseUrl/projects" $adminHeaders @{ code="SMOKE-P2-$stamp"; name="Smoke Project Two" }
Assert-True ($project1.id -match '^\d+$') "project create did not return a string ID"
$project1Id = $project1.id
$project2Id = $project2.id

$projectList = Invoke-ApiJson "Get" "$BaseUrl/projects" $adminHeaders
Assert-True ($projectList.totalElements -ge 2) "project list did not include the created projects"
$project1Updated = Invoke-ApiJson "Patch" "$BaseUrl/projects/$project1Id" $adminHeaders @{ name="Smoke Project One Updated" }
Assert-True ($project1Updated.name -eq "Smoke Project One Updated") "project update did not persist the new name"

$team1 = Invoke-ApiJson "Post" "$BaseUrl/teams" $adminHeaders @{ code="SMOKE-T1-$stamp"; name="Smoke Team" }
$team1Id = $team1.id
$teamList = Invoke-ApiJson "Get" "$BaseUrl/teams" $adminHeaders
Assert-True ($teamList.totalElements -ge 1) "team list did not include the created team"
$team1Updated = Invoke-ApiJson "Patch" "$BaseUrl/teams/$team1Id" $adminHeaders @{ name="Smoke Team Updated" }
Assert-True ($team1Updated.name -eq "Smoke Team Updated") "team update did not persist the new name"

$costCenter = Invoke-ApiJson "Post" "$BaseUrl/cost-centers" $adminHeaders @{ code="SMOKE-CC-$stamp"; name="Smoke Cost Center" }
$costCenterId = $costCenter.id
$costCenterList = Invoke-ApiJson "Get" "$BaseUrl/cost-centers" $adminHeaders
Assert-True ($costCenterList.totalElements -ge 1) "cost center list did not include the created cost center"
$costCenterUpdated = Invoke-ApiJson "Patch" "$BaseUrl/cost-centers/$costCenterId" $adminHeaders @{ status="ARCHIVED" }
Assert-True ($costCenterUpdated.status -eq "ARCHIVED") "cost center update did not persist the lifecycle status"

$providerAccount = Invoke-ApiJson "Post" "$BaseUrl/provider-accounts" $adminHeaders @{ providerCode="AWS"; displayName="Smoke AWS $stamp"; externalAccountRef="smoke-account-1" }
$providerAccountId = $providerAccount.id
$providerList = Invoke-ApiJson "Get" "$BaseUrl/provider-accounts" $adminHeaders
Assert-True ($providerList.totalElements -ge 1) "provider account list did not include the created account"
$providerUpdated = Invoke-ApiJson "Patch" "$BaseUrl/provider-accounts/$providerAccountId" $adminHeaders @{ displayName="Smoke AWS Renamed $stamp" }
Assert-True ($providerUpdated.displayName -eq "Smoke AWS Renamed $stamp") "provider account update did not persist the display name"

# ---------------------------------------------------------------
# Scoped authorization: assign PROJECT_OWNER at PROJECT:1 to the member,
# then prove the real scoped resource API returns a privacy 404 outside scope.
# ---------------------------------------------------------------
$roles = Invoke-ApiJson "Get" "$BaseUrl/roles" $adminHeaders
$projectOwnerRole = $roles | Where-Object { $_.code -eq "PROJECT_OWNER" } | Select-Object -First 1
Assert-True ($null -ne $projectOwnerRole) "role catalog did not contain PROJECT_OWNER"

$assignment = Invoke-ApiJson "Post" "$BaseUrl/role-assignments" $adminHeaders @{
    organizationMemberId = $memberMemberId; roleId = $projectOwnerRole.id; scopeType = "PROJECT"; scopeId = $project1Id
}
Assert-True ($assignment.id -match '^\d+$') "role assignment did not return a string ID"
$assignmentId = $assignment.id

# The assignment bumped the member's security version: re-login for a valid token.
$memberLogin2 = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$memberEmail; password=$password }
$memberHeaders2 = @{ Authorization = "Bearer $($memberLogin2.accessToken)" }

$scopedProjectList = Invoke-ApiJson "Get" "$BaseUrl/projects" $memberHeaders2
Assert-True ($scopedProjectList.totalElements -eq 1 -and $scopedProjectList.items[0].id -eq $project1Id) "Assert-WrongScope404: PROJECT_OWNER list was not limited to the explicit project"

# A scoped SYSTEM_ADMIN grant carries PROJECT_MANAGE inside the explicit
# project only; the same permission on another real project must be a 404.
$systemAdminRole = $roles | Where-Object { $_.code -eq "SYSTEM_ADMIN" } | Select-Object -First 1
Assert-True ($null -ne $systemAdminRole) "role catalog did not contain SYSTEM_ADMIN"
$scopedAdminAssignment = Invoke-ApiJson "Post" "$BaseUrl/role-assignments" $adminHeaders @{
    organizationMemberId = $memberMemberId; roleId = $systemAdminRole.id; scopeType = "PROJECT"; scopeId = $project1Id
}
Assert-True ($scopedAdminAssignment.id -match '^\d+$') "scoped SYSTEM_ADMIN assignment did not return a string ID"
$scopedAdminAssignmentId = $scopedAdminAssignment.id
$memberLogin2b = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$memberEmail; password=$password }
$memberHeaders2 = @{ Authorization = "Bearer $($memberLogin2b.accessToken)" }

function Assert-WrongScope404([string]$Name) {
    Assert-ApiError "Patch" "$BaseUrl/projects/$project2Id" $memberHeaders2 @{ name="Hijack" } 404 "RESOURCE_NOT_FOUND" "${Name}: out-of-scope project update was not a privacy 404"
    Assert-ApiError "Post" "$BaseUrl/projects" $memberHeaders2 @{ code="SMOKE-P3-$stamp"; name="Third" } 404 "RESOURCE_NOT_FOUND" "${Name}: scoped grant could not create master data"
    $inScope = Invoke-ApiJson "Patch" "$BaseUrl/projects/$project1Id" $memberHeaders2 @{ name="Smoke Project One Updated" }
    Assert-True ($inScope.name -eq "Smoke Project One Updated") "${Name}: in-scope project update did not succeed"
}
Assert-WrongScope404 "Assert-WrongScope404"

# ---------------------------------------------------------------
# User status with optimistic concurrency.
# ---------------------------------------------------------------
$memberUser = Invoke-ApiJson "Get" "$BaseUrl/users/$memberUserId" $adminHeaders
Assert-True ($memberUser.securityVersion -match '^\d+$') "user detail did not expose a decimal-string securityVersion"
$disabled = Invoke-ApiJson "Patch" "$BaseUrl/users/$memberUserId/status" $adminHeaders @{ status="DISABLED"; expectedVersion=$memberUser.securityVersion }
Assert-True ($disabled.status -eq "DISABLED") "user disable did not persist"
Assert-True ([string]::IsNullOrWhiteSpace($disabled.securityVersion) -eq $false) "user disable did not return a new securityVersion"

Assert-ApiError "Get" "$BaseUrl/auth/me" $memberHeaders2 $null 401 "AUTH_SESSION_EXPIRED" "Assert-OldJwt401: disabled user token was not rejected with 401 AUTH_SESSION_EXPIRED"

$memberUser2 = Invoke-ApiJson "Get" "$BaseUrl/users/$memberUserId" $adminHeaders
$enabled = Invoke-ApiJson "Patch" "$BaseUrl/users/$memberUserId/status" $adminHeaders @{ status="ACTIVE"; expectedVersion=$memberUser2.securityVersion }
Assert-True ($enabled.status -eq "ACTIVE") "user re-enable did not persist"

# ---------------------------------------------------------------
# Project and team membership lifecycle (HTTP only).
# ---------------------------------------------------------------
$projectMembers = Invoke-ApiJson "Get" "$BaseUrl/projects/$project1Id/members" $adminHeaders
Assert-True ($projectMembers.totalElements -eq 0) "new project unexpectedly had members"
$projectMember = Invoke-ApiJson "Post" "$BaseUrl/projects/$project1Id/members" $adminHeaders @{ organizationMemberId=$memberMemberId }
Assert-True ($projectMember.organizationMemberId -eq $memberMemberId) "project member add returned the wrong member"
$projectMembers2 = Invoke-ApiJson "Get" "$BaseUrl/projects/$project1Id/members" $adminHeaders
Assert-True ($projectMembers2.totalElements -eq 1) "project member add did not persist"
Invoke-ApiJson "Delete" "$BaseUrl/projects/$project1Id/members/$($projectMember.id)" $adminHeaders $null | Out-Null
# DELETE is a lifecycle transition: the row survives but is no longer ACTIVE.
$projectActiveMembers = Invoke-ApiJson "Get" "$BaseUrl/projects/$project1Id/members?status=ACTIVE" $adminHeaders
Assert-True ($projectActiveMembers.totalElements -eq 0) "project member remove did not deactivate the membership"

$teamMember = Invoke-ApiJson "Post" "$BaseUrl/teams/$team1Id/members" $adminHeaders @{ organizationMemberId=$memberMemberId }
Assert-True ($teamMember.organizationMemberId -eq $memberMemberId) "team member add returned the wrong member"
$teamMembers = Invoke-ApiJson "Get" "$BaseUrl/teams/$team1Id/members" $adminHeaders
Assert-True ($teamMembers.totalElements -eq 1) "team member add did not persist"
Invoke-ApiJson "Delete" "$BaseUrl/teams/$team1Id/members/$($teamMember.id)" $adminHeaders $null | Out-Null
$teamActiveMembers = Invoke-ApiJson "Get" "$BaseUrl/teams/$team1Id/members?status=ACTIVE" $adminHeaders
Assert-True ($teamActiveMembers.totalElements -eq 0) "team member remove did not deactivate the membership"

# Lifecycle transition preserves the row: the archived team remains listed.
$teamArchived = Invoke-ApiJson "Patch" "$BaseUrl/teams/$team1Id" $adminHeaders @{ status="ARCHIVED" }
Assert-True ($teamArchived.status -eq "ARCHIVED") "team lifecycle transition did not persist"
$teamList2 = Invoke-ApiJson "Get" "$BaseUrl/teams" $adminHeaders
Assert-True ($teamList2.totalElements -ge 1) "archived team disappeared from the list"

# ---------------------------------------------------------------
# Invitation creation delivers a mailbox link; the API response never
# returns the raw token, and the delivery message carries the accept
# link (Assert-AuditSecretAbsence). Audit secret absence itself is
# proven by the HTTP-driven integration tests over API-created
# subjects, not by direct SQL from this script.
# ---------------------------------------------------------------
$invitation = Invoke-ApiJson "Post" "$BaseUrl/invitations" $adminHeaders @{ email=$inviteEmail; initialRoleCode="EMPLOYEE"; expiresInHours=72 }
Assert-True ($invitation.status -eq "PENDING") "invitation create did not return a PENDING invitation"
Assert-True ($null -eq $invitation.PSObject.Properties['token'] -and $null -eq $invitation.PSObject.Properties['acceptToken']) "Assert-AuditSecretAbsence: invitation response exposed a raw token"
$inviteMessagePath = (docker compose --env-file $EnvFile exec -T backend sh -c "grep -l 'email=$inviteEmail' /var/lib/aicostops/invitations/*.txt | tail -n 1").Trim()
Assert-True (-not [string]::IsNullOrWhiteSpace($inviteMessagePath)) "Assert-AuditSecretAbsence: dev invitation mailbox did not create a message"
$inviteBody = docker compose --env-file $EnvFile exec -T backend sh -c "cat '$inviteMessagePath'"
$inviteLinkLine = $inviteBody | Where-Object { $_ -like 'acceptLink=*' } | Select-Object -First 1
$inviteLink = ($inviteLinkLine -split '=', 2)[1]
$inviteTokenMatch = [regex]::Match(([uri]$inviteLink).Query, '(?:^|[?&])token=([^&]+)')
Assert-True $inviteTokenMatch.Success "Assert-AuditSecretAbsence: invitation link did not contain a token"
$inviteToken = [uri]::UnescapeDataString($inviteTokenMatch.Groups[1].Value.Replace('+', ' '))
Assert-True ($inviteToken.Length -ge 32) "Assert-AuditSecretAbsence: invitation token is not high-entropy"

# ---------------------------------------------------------------
# Assert-OldJwt401: revoking a REAL role assignment bumps the target
# user's security version and the pre-revoke JWT is rejected.
# ---------------------------------------------------------------
$memberLogin3 = Invoke-ApiJson "Post" "$BaseUrl/auth/login" @{} @{ email=$memberEmail; password=$password }
$memberHeaders3 = @{ Authorization = "Bearer $($memberLogin3.accessToken)" }
Invoke-ApiJson "Get" "$BaseUrl/auth/me" $memberHeaders3 | Out-Null
Invoke-ApiJson "Delete" "$BaseUrl/role-assignments/$scopedAdminAssignmentId" $adminHeaders $null | Out-Null
Assert-ApiError "Get" "$BaseUrl/auth/me" $memberHeaders3 $null 401 "AUTH_SESSION_EXPIRED" "Assert-OldJwt401: pre-revoke JWT was not rejected with 401 AUTH_SESSION_EXPIRED"

# ---------------------------------------------------------------
# Assert-M2Denied: M2 endpoints stay behind the final denyAll.
# ---------------------------------------------------------------
Assert-ApiError "Get" "$BaseUrl/costs/charges" $adminHeaders $null 403 "FORBIDDEN" "Assert-M2Denied: /costs/charges was not denied with 403 FORBIDDEN"
Assert-ApiError "Get" "$BaseUrl/budgets" $adminHeaders $null 403 "FORBIDDEN" "Assert-M2Denied: /budgets was not denied with 403 FORBIDDEN"

Write-Output "ORG_AUTH_SMOKE_PASS admin=$adminEmail member=$memberEmail"
