/**
 * HC-03 synthetic fixture. Everything is seeded into the dedicated HC-03
 * database with the DBA identity; raw Gateway keys remain only in
 * `.local-dev/hc03/seed.json` (git-ignored) and never enter committed evidence.
 *
 * Topology:
 * - one "hot" organization and OPEN period shared by F01-F05 fixtures, each
 *   fixture in its own project/budget/credential so budget and route facts stay
 *   attributable;
 * - one independent "F06" organization with its own OPEN period, so a real
 *   Close never touches any other scenario's financial facts;
 * - one controlled Mock Provider account per organization, all pointing at the
 *   HC-03 mock provider port.
 */
import { createCipheriv, createHmac, randomBytes } from 'node:crypto'
import { join } from 'node:path'
import { DB_NAME, HC03_DIR, MODEL_KEY, MOCK_PORT, PROVIDER_CODE, MYSQL_PORT } from './env.mjs'
import {
  ensureDirs, sqlQuote, sqlRows, sqlScalar, sqlStatement, writeJson,
} from './lib.mjs'

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz'
const KEK = () => {
  const value = process.env.HC03_PROVIDER_KEK
  if (!value) throw new Error('HC03_PROVIDER_KEK is required')
  return value
}
const CREDENTIAL_HMAC_KEY = () => {
  const value = process.env.HC03_CREDENTIAL_HMAC_KEY
  if (!value) throw new Error('HC03_CREDENTIAL_HMAC_KEY is required')
  return value
}

function base64url(bytes) {
  return randomBytes(bytes).toString('base64url')
}

function newCredential() {
  let prefix = ''
  for (let index = 0; index < 12; index++) {
    prefix += CROCKFORD[randomBytes(1)[0] % CROCKFORD.length]
  }
  const secret = base64url(32)
  return { prefix, secret, rawKey: `aic_${prefix}_${secret}` }
}

function encryptProviderSecret(orgId, accountId, plaintext) {
  const key = Buffer.from(KEK().trim(), 'base64')
  if (key.length !== 32) throw new Error('HC03_PROVIDER_KEK must be 32-byte base64')
  const nonce = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  const aad = Buffer.from(
    `aicostops:v2:provider-credential:v1\u0000${orgId}\u0000${accountId}\u0000API_KEY\u00001`, 'utf8')
  cipher.setAAD(aad)
  const ciphertext = Buffer.concat([cipher.update(Buffer.from(plaintext, 'utf8')), cipher.final()])
  return {
    ciphertext: Buffer.concat([ciphertext, cipher.getAuthTag()]).toString('hex'),
    nonce: nonce.toString('hex'),
  }
}

function credentialDigest(secret) {
  const key = Buffer.from(CREDENTIAL_HMAC_KEY().trim(), 'base64')
  return createHmac('sha256', key).update(secret, 'utf8').digest('hex')
}

function insertId(text) {
  const id = sqlScalar(text)
  if (!id) throw new Error(`insert did not return an id: ${text.slice(0, 120)}`)
  return Number(id)
}

function createOrg(slug, name) {
  return insertId(`INSERT INTO organization(name,slug,status,created_at,updated_at)
    VALUES (${sqlQuote(name)},${sqlQuote(slug)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createPeriod(orgId, { exactUtcMonth = false } = {}) {
  if (exactUtcMonth) {
    // Exactly the DevAuthenticationBootstrap current-UTC-month window, so the
    // bootstrap's "insert if missing" is a no-op and never creates a second,
    // overlapping OPEN period for the Close-drill organization.
    return insertId(`INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
      VALUES (${orgId},DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-01 00:00:00'),
        DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MONTH),'%Y-%m-01 00:00:00'),
        'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
      SELECT LAST_INSERT_ID();`)
  }
  return insertId(`INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
    VALUES (${orgId},DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 23 DAY),
      'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createProject(orgId, code) {
  return insertId(`INSERT INTO project(org_id,code,name,status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(code)},${sqlQuote(`HC-03 ${code}`)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createServiceIdentity(orgId, code) {
  return insertId(`INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(code)},${sqlQuote(`HC-03 ${code}`)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createProviderStack(orgId, suffix) {
  const accountId = insertId(`INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(PROVIDER_CODE)},${sqlQuote(`HC-03 Mock Account ${suffix}`)},${sqlQuote(`hc03-acct-${suffix}`)},
      'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  const encrypted = encryptProviderSecret(orgId, accountId, `sk-hc03-${suffix}-${base64url(12)}`)
  sqlStatement(`INSERT INTO provider_credential(org_id,provider_account_id,credential_type,ciphertext,nonce,
      encryption_key_version,safe_label,status,created_at)
    VALUES (${orgId},${accountId},'API_KEY',UNHEX(${sqlQuote(encrypted.ciphertext)}),UNHEX(${sqlQuote(encrypted.nonce)}),
      1,${sqlQuote(`hc03-${suffix}`)},'ACTIVE',UTC_TIMESTAMP(6));`)
  const providerModelId = insertId(`SELECT id FROM provider_model
    WHERE provider_code=${sqlQuote(PROVIDER_CODE)}
      AND model_id=(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)})
      AND provider_model_name='hc03-mock-chat';`)
  const pricingVersionId = insertId(`INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,currency,
      effective_from,effective_to,status,created_at,activated_at)
    VALUES (${orgId},${accountId},${providerModelId},1,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
      DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  sqlStatement(`INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
    VALUES (${orgId},${pricingVersionId},'INPUT_TOKEN',1000000,'30.00000000'),
           (${orgId},${pricingVersionId},'OUTPUT_TOKEN',1000000,'60.00000000');`)
  sqlStatement(`INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,
      template_code,protocol_code,base_url,completion_path,models_path,auth_type,auth_header_name,
      network_policy,user_agent,connect_timeout_ms,response_timeout_ms,status,created_by,created_at,
      activated_at,retired_at)
    VALUES (${orgId},${accountId},1,'CUSTOM',NULL,'MIMO_CHAT_COMPLETIONS',
      ${sqlQuote(`http://127.0.0.1:${MOCK_PORT}/v1`)},'/chat/completions','/models',
      'API_KEY_HEADER','X-API-Key','DIRECT_PUBLIC_ONLY',NULL,3000,30000,'ACTIVE',NULL,
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL);`)
  const policyId = insertId(`INSERT INTO routing_policy(org_id,project_id,model_id,version,status,created_at,activated_at)
    VALUES (${orgId},NULL,(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)}),1,'ACTIVE',
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  sqlStatement(`INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,provider_model_id,
      priority,status,created_at)
    VALUES (${orgId},${policyId},${accountId},${providerModelId},0,'ACTIVE',UTC_TIMESTAMP(6));`)
  return { accountId, providerModelId, pricingVersionId, policyId }
}

function createGatewayCredential(orgId, serviceIdentityId, projectId, label, enforcementMode = 'OPTIONAL') {
  const credential = newCredential()
  const digest = credentialDigest(credential.secret)
  const credentialId = insertId(`INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
      principal_type,service_identity_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
      status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(credential.prefix)},UNHEX(${sqlQuote(digest)}),1,'SERVICE',${serviceIdentityId},${projectId},
      'PROJECT',${projectId},${sqlQuote(enforcementMode)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  sqlStatement(`INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
    VALUES (${credentialId},${orgId},(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)}),'ACTIVE',UTC_TIMESTAMP(6));`)
  return { credentialId, rawKey: credential.rawKey, prefix: credential.prefix, label }
}

function createBudget(orgId, periodId, projectId, totalAmount) {
  return insertId(`INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,total_amount,
      actual_amount,committed_amount,status,version,created_at,updated_at)
    VALUES (${orgId},${periodId},'PROJECT',${projectId},'USD',${sqlQuote(totalAmount)},0,0,'ACTIVE',0,
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createFixture(orgId, periodId, serviceIdentityId, code, totalAmount = '100000000.00000000') {
  const projectId = createProject(orgId, code)
  const budgetId = createBudget(orgId, periodId, projectId, totalAmount)
  const credential = createGatewayCredential(orgId, serviceIdentityId, projectId, code)
  return { orgId, periodId, projectId, budgetId, ...credential }
}

export function seed() {
  ensureDirs()
  const suffix = randomBytes(4).toString('hex')

  sqlStatement(`INSERT INTO model_catalog(model_key,name,status,capabilities_json,default_max_output_tokens,
      max_output_tokens,created_at,updated_at)
    VALUES (${sqlQuote(MODEL_KEY)},'HC-03 Failure Model','ACTIVE',
      JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),8192,131072,
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));`)
  sqlStatement(`INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
      capabilities_json,created_at,updated_at)
    VALUES (${sqlQuote(PROVIDER_CODE)},'HC-03 Mock Provider','MIMO',
      ${sqlQuote(`http://127.0.0.1:${MOCK_PORT}/v1`)},'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));`)
  sqlStatement(`INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,routing_eligible,
      capabilities_json,created_at,updated_at)
    VALUES (${sqlQuote(PROVIDER_CODE)},(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)}),
      'hc03-mock-chat','ACTIVE',TRUE,
      JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));`)

  const hotOrgSlug = `hc03-hot-${suffix}`
  const hotOrgId = createOrg(hotOrgSlug, `HC-03 Hot ${suffix}`)
  const hotPeriodId = createPeriod(hotOrgId)
  const hotServiceId = createServiceIdentity(hotOrgId, `hc03-svc-hot-${suffix}`)
  const hotProvider = createProviderStack(hotOrgId, `hot-${suffix}`)

  const fixtures = {
    hot: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-hot-${suffix}`),
    f01: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f01-${suffix}`),
    f02: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f02-${suffix}`),
    f03pre: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f03pre-${suffix}`),
    f03post: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f03post-${suffix}`),
    f04: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f04-${suffix}`),
    f05: createFixture(hotOrgId, hotPeriodId, hotServiceId, `hc03-f05-${suffix}`),
  }

  const closeOrgSlug = `hc03-close-${suffix}`
  const closeOrgId = createOrg(closeOrgSlug, `HC-03 Close ${suffix}`)
  const closePeriodId = createPeriod(closeOrgId, { exactUtcMonth: true })
  const closeServiceId = createServiceIdentity(closeOrgId, `hc03-svc-close-${suffix}`)
  createProviderStack(closeOrgId, `close-${suffix}`)
  const f06 = createFixture(closeOrgId, closePeriodId, closeServiceId, `hc03-f06-${suffix}`)

  const seedData = {
    generatedAt: new Date().toISOString(),
    database: process.env.HC01_DB,
    mysqlPort: MYSQL_PORT,
    modelKey: MODEL_KEY,
    providerCode: PROVIDER_CODE,
    hotOrg: {
      orgId: hotOrgId, slug: hotOrgSlug, periodId: hotPeriodId,
      serviceIdentityId: hotServiceId, provider: hotProvider,
    },
    closeOrg: {
      orgId: closeOrgId, slug: closeOrgSlug, periodId: closePeriodId,
      serviceIdentityId: closeServiceId,
    },
    fixtures,
    f06,
    suffix,
  }
  writeJson(join(HC03_DIR, 'seed.json'), seedData)
  return seedData
}

/** Creates the least-privilege Gateway MySQL identity for this dedicated DB. */
export function createGatewayDbUser(gatewayDbUser, password) {
  const owned = ['budget_reservation', 'gateway_request', 'gateway_route_attempt',
    'gateway_usage_fact', 'gateway_usage_dimension']
  const grants = owned
    .map(table => `GRANT SELECT, INSERT, UPDATE ON \`${DB_NAME}\`.\`${table}\` TO '${gatewayDbUser}'@'%';`)
    .join('\n')
  const advisorGrants = [
    'GRANT SELECT, UPDATE ON `%DB%`.advisor_inference_job TO \'%USER%\'@\'%\';',
    'GRANT SELECT, UPDATE ON `%DB%`.advisor_inference_attempt TO \'%USER%\'@\'%\';',
    'GRANT INSERT ON `%DB%`.advisor_explanation TO \'%USER%\'@\'%\';',
    'GRANT SELECT ON `%DB%`.advisor_profile TO \'%USER%\'@\'%\';',
    'GRANT SELECT ON `%DB%`.advisor_evidence_snapshot TO \'%USER%\'@\'%\';',
  ].map(statement => statement.replaceAll('%DB%', DB_NAME).replaceAll('%USER%', gatewayDbUser)).join('\n')
  sqlStatement(`CREATE USER IF NOT EXISTS '${gatewayDbUser}'@'%' IDENTIFIED BY '${password}';
    ALTER USER '${gatewayDbUser}'@'%' IDENTIFIED BY '${password}';
    GRANT SELECT ON \`${DB_NAME}\`.* TO '${gatewayDbUser}'@'%';
    GRANT LOCK TABLES ON \`${DB_NAME}\`.* TO '${gatewayDbUser}'@'%';
    ${grants}
    ${advisorGrants}
    FLUSH PRIVILEGES;`, { database: null })
  const grantRows = sqlRows(`SHOW GRANTS FOR '${gatewayDbUser}'@'%';`, { database: null })
    .map(row => row.join(' ')).join('\n')
  if (grantRows.includes('DELETE') || grantRows.includes('GRANT OPTION')) {
    throw new Error('Gateway DB identity unexpectedly holds DELETE or GRANT OPTION')
  }
  return { grants: grantRows.split('\n').length }
}
