import { execFileSync } from 'node:child_process'

const MYSQL_IN_CONTAINER =
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names'

function mysqlContainerId(): string {
  const project = process.env.AICOSTOPS_M22_COMPOSE_PROJECT ?? ''
  if (!/^aicostops-[a-z0-9][a-z0-9-]{0,50}$/.test(project)) {
    throw new Error('M22 E2E requires an isolated AICOSTOPS_M22_COMPOSE_PROJECT name.')
  }

  const containers = execFileSync('docker', [
    'ps',
    '--filter', `label=com.docker.compose.project=${project}`,
    '--filter', 'label=com.docker.compose.service=mysql',
    '--format', '{{.ID}}',
  ], { encoding: 'utf8' }).trim().split(/\r?\n/).filter(Boolean)

  if (containers.length !== 1) {
    throw new Error(`Expected one running MySQL container for isolated Compose project ${project}.`)
  }
  return containers[0]
}

export function runM22Sql(sql: string): string {
  const output = execFileSync('docker', [
    'exec', '-i', mysqlContainerId(), 'sh', '-lc', MYSQL_IN_CONTAINER,
  ], { input: sql, encoding: 'utf8' }).trim()
  return output
}

function decimalId(value: string, label: string): string {
  if (!/^\d+$/.test(value)) {
    throw new Error(`${label} must be a decimal ID returned by the API.`)
  }
  return value
}

function slug(value: string): string {
  if (!/^m22-org-b-[a-z0-9-]{8,40}$/.test(value)) {
    throw new Error('M22 fixture slug is outside the test-only namespace.')
  }
  return value
}

/**
 * The product has no organization-create or organization-switch endpoint.
 * Registration is performed through the real public API first; this fixture
 * moves that one newly registered membership into a second, isolated test org
 * and gives it the same seeded admin roles as the first test identity.
 */
export function createSecondOrganizationFixture(input: {
  slug: string
  userId: string
  memberId: string
  firstOrganizationId: string
}): string {
  const orgSlug = slug(input.slug)
  const userId = decimalId(input.userId, 'userId')
  const memberId = decimalId(input.memberId, 'organizationMemberId')
  const firstOrgId = decimalId(input.firstOrganizationId, 'organizationId')

  const output = runM22Sql(`
START TRANSACTION;
INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
VALUES ('M22 Browser Organization B','${orgSlug}','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @m22_org_b = LAST_INSERT_ID();
UPDATE role_assignment
SET scope_id=@m22_org_b
WHERE org_member_id=${memberId} AND scope_type='ORG' AND scope_id=${firstOrgId};
UPDATE organization_member
SET org_id=@m22_org_b
WHERE id=${memberId} AND user_id=${userId} AND org_id=${firstOrgId} AND status='ACTIVE';
INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
SELECT ${memberId},r.id,'ORG',@m22_org_b,NULL,UTC_TIMESTAMP(6)
FROM \`role\` r
WHERE r.code IN ('FINANCE_ADMIN','SYSTEM_ADMIN');
INSERT INTO billing_period(org_id,period_start,period_end,status,created_at,updated_at)
SELECT @m22_org_b,bp.period_start,bp.period_end,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM billing_period bp
WHERE bp.org_id=${firstOrgId} AND bp.status='OPEN'
ORDER BY bp.period_start DESC
LIMIT 1;
SELECT @m22_org_b, ${memberId},
  (SELECT org_id FROM organization_member WHERE id=${memberId} AND user_id=${userId}),
  (SELECT COUNT(*) FROM role_assignment WHERE org_member_id=${memberId} AND scope_type='ORG' AND scope_id=@m22_org_b),
  (SELECT COUNT(*) FROM billing_period WHERE org_id=@m22_org_b AND status='OPEN');
COMMIT;
`)

  const result = output.split(/\r?\n/).at(-1)?.split('\t') ?? []
  if (result.length !== 5 || result[1] !== memberId || result[2] !== result[0]
    || result[3] !== '3' || result[4] !== '1') {
    throw new Error('M22 second-organization fixture did not establish one member, three org roles, and one open period.')
  }
  return decimalId(result[0], 'second organizationId')
}

export function createPrivateModelFixture(input: {
  organizationId: string
  providerAccountId: string
  suffix: string
}): { modelId: string; providerModelId: string; modelKey: string; wireName: string } {
  const organizationId = decimalId(input.organizationId, 'organizationId')
  const providerAccountId = decimalId(input.providerAccountId, 'providerAccountId')
  if (!/^[a-z0-9-]{8,40}$/.test(input.suffix)) {
    throw new Error('M22 model suffix is outside the test-only namespace.')
  }
  const modelKey = `m22-${input.suffix}`
  const wireName = `m22-wire-${input.suffix}`
  const escapedKey = modelKey.replaceAll("'", "''")
  const escapedName = wireName.replaceAll("'", "''")

  const output = runM22Sql(`
START TRANSACTION;
INSERT INTO model_catalog(model_key,name,owner_org_id,status,capabilities_json,
  default_max_output_tokens,max_output_tokens,created_at,updated_at)
VALUES ('${escapedKey}','${escapedName}',${organizationId},'ACTIVE',
  JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),8192,131072,
  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @m22_model = LAST_INSERT_ID();
INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
  routing_eligible,capabilities_json,owner_org_id,provider_account_id,created_at,updated_at)
VALUES ('OPENCODE_ZEN',@m22_model,'${escapedName}','ACTIVE',TRUE,
  JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
  ${organizationId},${providerAccountId},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @m22_provider_model = LAST_INSERT_ID();
SELECT @m22_model, @m22_provider_model;
COMMIT;
`)
  const result = output.split(/\r?\n/).at(-1)?.split('\t') ?? []
  if (result.length !== 2) {
    throw new Error('M22 private model fixture did not return both generated IDs.')
  }
  return {
    modelId: decimalId(result[0], 'modelId'),
    providerModelId: decimalId(result[1], 'providerModelId'),
    modelKey,
    wireName,
  }
}
