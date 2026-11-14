/**
 * HC-01 synthetic governed fixture, seeded through the DBA identity into a
 * dedicated database. It mirrors the M16 acceptance seed contract but adds the
 * hotspot/contrast topology the HC-01 matrix needs:
 *
 * - hot: one org + one OPEN period + one project/budget/credential (hotspot)
 * - multiBudget: the same org/period with 7 additional budgets/credentials
 * - multiOrg: 7 additional orgs, each with its own period/budget/credential
 * - exhaust: a small budget used to prove no overspend under concurrent denial
 * - uncertain: a separate budget used for UNKNOWN/INCOMPLETE and SSE cancel
 * - rateLimit: a dedicated credential for the normal-rate-limit phase
 *
 * All values are synthetic; raw Gateway keys exist only in the local
 * .local-dev/hc01 seed file, never in committed evidence.
 */
import { createCipheriv, createHmac, randomBytes } from 'node:crypto'
import { join } from 'node:path'
import {
  DB_NAME, HC01_DIR, MODEL_KEY, PROVIDER_CODE, config, ensureDirs, mysqlScalar, sqlQuote, writeJson,
} from './lib.mjs'

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz'

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

function encryptProviderSecret(cfg, orgId, accountId, plaintext) {
  const key = Buffer.from(cfg.providerKek.trim(), 'base64')
  if (key.length !== 32) throw new Error('AICOSTOPS_PROVIDER_KEK_V1 必须是 32 字节 base64')
  const nonce = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  const aad = Buffer.from(`aicostops:v2:provider-credential:v1\u0000${orgId}\u0000${accountId}\u0000API_KEY\u00001`, 'utf8')
  cipher.setAAD(aad)
  const ciphertext = Buffer.concat([cipher.update(Buffer.from(plaintext, 'utf8')), cipher.final()])
  const tag = cipher.getAuthTag()
  return { ciphertext: Buffer.concat([ciphertext, tag]).toString('hex'), nonce: nonce.toString('hex') }
}

function credentialDigest(cfg, secret) {
  const key = Buffer.from(cfg.credentialHmacKey.trim(), 'base64')
  return createHmac('sha256', key).update(secret, 'utf8').digest('hex')
}

function insertId(sql) {
  const id = mysqlScalar(sql)
  if (!id) throw new Error(`insert did not return an id: ${sql.slice(0, 120)}`)
  return Number(id)
}

function createOrg(slug, name) {
  return insertId(`INSERT INTO organization(name,slug,status,created_at,updated_at)
    VALUES (${sqlQuote(name)},${sqlQuote(slug)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createPeriod(orgId) {
  return insertId(`INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
    VALUES (${orgId},DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 23 DAY),
      'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createProject(orgId, code) {
  return insertId(`INSERT INTO project(org_id,code,name,status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(code)},${sqlQuote(`HC-01 ${code}`)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createServiceIdentity(orgId, code) {
  return insertId(`INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(code)},${sqlQuote(`HC-01 ${code}`)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
}

function createProviderStack(cfg, orgId, suffix) {
  const accountId = insertId(`INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(PROVIDER_CODE)},${sqlQuote(`HC-01 Mock Account ${suffix}`)},${sqlQuote(`hc01-acct-${suffix}`)},
      'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  const encrypted = encryptProviderSecret(cfg, orgId, accountId, `sk-hc01-${suffix}-${base64url(12)}`)
  mysqlScalar(`INSERT INTO provider_credential(org_id,provider_account_id,credential_type,ciphertext,nonce,
      encryption_key_version,safe_label,status,created_at)
    VALUES (${orgId},${accountId},'API_KEY',UNHEX(${sqlQuote(encrypted.ciphertext)}),UNHEX(${sqlQuote(encrypted.nonce)}),
      1,${sqlQuote(`hc01-${suffix}`)},'ACTIVE',UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  const providerModelId = insertId(`SELECT id FROM provider_model
    WHERE provider_code=${sqlQuote(PROVIDER_CODE)} AND model_id=(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)})
      AND provider_model_name='hc01-mock-chat';`)
  const pricingVersionId = insertId(`INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,currency,
      effective_from,effective_to,status,created_at,activated_at)
    VALUES (${orgId},${accountId},${providerModelId},1,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
      DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  mysqlScalar(`INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
    VALUES (${orgId},${pricingVersionId},'INPUT_TOKEN',1000000,'30.00000000'),
           (${orgId},${pricingVersionId},'OUTPUT_TOKEN',1000000,'60.00000000');`)
  // V24 backfills profiles only for accounts that existed at migration time.
  // Runtime candidates INNER JOIN an ACTIVE profile, so a post-migration seed
  // must create the same v1 profile explicitly.
  mysqlScalar(`INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,
      template_code,protocol_code,base_url,completion_path,models_path,auth_type,auth_header_name,
      network_policy,user_agent,connect_timeout_ms,response_timeout_ms,status,created_by,created_at,
      activated_at,retired_at)
    VALUES (${orgId},${accountId},1,'CUSTOM',NULL,'OPENAI_CHAT_COMPLETIONS',
      ${sqlQuote(`http://127.0.0.1:${cfg.mockPort}/v1`)},'/chat/completions','/models',
      'API_KEY_HEADER','X-API-Key','DIRECT_PUBLIC_ONLY',NULL,5000,60000,'ACTIVE',NULL,
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL);
    SELECT LAST_INSERT_ID();`)
  const policyId = insertId(`INSERT INTO routing_policy(org_id,project_id,model_id,version,status,created_at,activated_at)
    VALUES (${orgId},NULL,(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)}),1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  mysqlScalar(`INSERT INTO routing_policy_candidate(org_id,routing_policy_id,provider_account_id,provider_model_id,
      priority,status,created_at)
    VALUES (${orgId},${policyId},${accountId},${providerModelId},0,'ACTIVE',UTC_TIMESTAMP(6));`)
  return { accountId, providerModelId, pricingVersionId, policyId }
}

function createGatewayCredential(cfg, orgId, serviceIdentityId, projectId, label, enforcementMode = 'OPTIONAL') {
  const credential = newCredential()
  const digest = credentialDigest(cfg, credential.secret)
  const credentialId = insertId(`INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
      principal_type,service_identity_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
      status,created_at,updated_at)
    VALUES (${orgId},${sqlQuote(credential.prefix)},UNHEX(${sqlQuote(digest)}),1,'SERVICE',${serviceIdentityId},${projectId},
      'PROJECT',${projectId},${sqlQuote(enforcementMode)},'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  mysqlScalar(`INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
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

export function seed() {
  ensureDirs()
  const cfg = config()
  const suffix = randomBytes(4).toString('hex')

  mysqlScalar(`INSERT INTO model_catalog(model_key,name,status,capabilities_json,default_max_output_tokens,
      max_output_tokens,created_at,updated_at)
    VALUES (${sqlQuote(MODEL_KEY)},'HC-01 Load Model','ACTIVE',
      JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),8192,131072,
      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  const providerCatalogId = insertId(`INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
      capabilities_json,created_at,updated_at)
    VALUES (${sqlQuote(PROVIDER_CODE)},'HC-01 Mock Provider','CUSTOM_OPENAI_COMPATIBLE',${sqlQuote(`http://127.0.0.1:${cfg.mockPort}/v1`)},
      'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
    SELECT LAST_INSERT_ID();`)
  mysqlScalar(`INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,routing_eligible,
      capabilities_json,created_at,updated_at)
    VALUES (${sqlQuote(PROVIDER_CODE)},(SELECT id FROM model_catalog WHERE model_key=${sqlQuote(MODEL_KEY)}),
      'hc01-mock-chat','ACTIVE',TRUE,
      JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS','SSE_STREAMING')),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));`)

  const hotOrgId = createOrg(`hc01-hot-${suffix}`, `HC-01 Hot ${suffix}`)
  const hotPeriodId = createPeriod(hotOrgId)
  const hotServiceId = createServiceIdentity(hotOrgId, `hc01-svc-hot-${suffix}`)
  const hotProvider = createProviderStack(cfg, hotOrgId, `hot-${suffix}`)

  const hotProjectId = createProject(hotOrgId, `hc01-hot-${suffix}`)
  const hotBudgetId = createBudget(hotOrgId, hotPeriodId, hotProjectId, '100000000.00000000')
  const hotCredential = createGatewayCredential(cfg, hotOrgId, hotServiceId, hotProjectId, 'hot')

  const multiBudget = []
  for (let index = 0; index < 7; index++) {
    const projectId = createProject(hotOrgId, `hc01-mb-${index}-${suffix}`)
    const budgetId = createBudget(hotOrgId, hotPeriodId, projectId, '100000000.00000000')
    const credential = createGatewayCredential(cfg, hotOrgId, hotServiceId, projectId, `multi-budget-${index}`)
    multiBudget.push({ orgId: hotOrgId, periodId: hotPeriodId, projectId, budgetId, ...credential })
  }

  const exhaustProjectId = createProject(hotOrgId, `hc01-exhaust-${suffix}`)
  const exhaustBudgetId = createBudget(hotOrgId, hotPeriodId, exhaustProjectId, '64.00000000')
  const exhaust = {
    orgId: hotOrgId, periodId: hotPeriodId, projectId: exhaustProjectId, budgetId: exhaustBudgetId,
    ...createGatewayCredential(cfg, hotOrgId, hotServiceId, exhaustProjectId, 'exhaust', 'REQUIRED'),
  }

  const uncertainProjectId = createProject(hotOrgId, `hc01-uncertain-${suffix}`)
  const uncertainBudgetId = createBudget(hotOrgId, hotPeriodId, uncertainProjectId, '100000000.00000000')
  const uncertain = {
    orgId: hotOrgId, periodId: hotPeriodId, projectId: uncertainProjectId, budgetId: uncertainBudgetId,
    ...createGatewayCredential(cfg, hotOrgId, hotServiceId, uncertainProjectId, 'uncertain'),
  }

  const rateLimitProjectId = createProject(hotOrgId, `hc01-rate-${suffix}`)
  const rateLimitBudgetId = createBudget(hotOrgId, hotPeriodId, rateLimitProjectId, '100000000.00000000')
  const rateLimit = {
    orgId: hotOrgId, periodId: hotPeriodId, projectId: rateLimitProjectId, budgetId: rateLimitBudgetId,
    ...createGatewayCredential(cfg, hotOrgId, hotServiceId, rateLimitProjectId, 'rate-limit'),
  }

  const providerFailureProjectId = createProject(hotOrgId, `hc01-pfail-${suffix}`)
  const providerFailureBudgetId = createBudget(hotOrgId, hotPeriodId, providerFailureProjectId, '100000000.00000000')
  const providerFailure = {
    orgId: hotOrgId, periodId: hotPeriodId, projectId: providerFailureProjectId, budgetId: providerFailureBudgetId,
    ...createGatewayCredential(cfg, hotOrgId, hotServiceId, providerFailureProjectId, 'provider-failure'),
  }

  const sseCancelProjectId = createProject(hotOrgId, `hc01-ssecancel-${suffix}`)
  const sseCancelBudgetId = createBudget(hotOrgId, hotPeriodId, sseCancelProjectId, '100000000.00000000')
  const sseCancel = {
    orgId: hotOrgId, periodId: hotPeriodId, projectId: sseCancelProjectId, budgetId: sseCancelBudgetId,
    ...createGatewayCredential(cfg, hotOrgId, hotServiceId, sseCancelProjectId, 'sse-cancel'),
  }

  const multiOrg = []
  for (let index = 0; index < 7; index++) {
    const orgId = createOrg(`hc01-org-${index}-${suffix}`, `HC-01 Org ${index} ${suffix}`)
    const periodId = createPeriod(orgId)
    const serviceId = createServiceIdentity(orgId, `hc01-svc-${index}-${suffix}`)
    createProviderStack(cfg, orgId, `org-${index}-${suffix}`)
    const projectId = createProject(orgId, `hc01-proj-${index}-${suffix}`)
    const budgetId = createBudget(orgId, periodId, projectId, '100000000.00000000')
    multiOrg.push({
      orgId, periodId, projectId, budgetId,
      ...createGatewayCredential(cfg, orgId, serviceId, projectId, `multi-org-${index}`),
    })
  }

  const seedData = {
    generatedAt: new Date().toISOString(),
    database: DB_NAME,
    modelKey: MODEL_KEY,
    providerCode: PROVIDER_CODE,
    providerCatalogId,
    hotProvider,
    hot: {
      orgId: hotOrgId, periodId: hotPeriodId, projectId: hotProjectId,
      budgetId: hotBudgetId, ...hotCredential,
    },
    multiBudget,
    multiOrg,
    exhaust,
    uncertain,
    rateLimit,
    providerFailure,
    sseCancel,
  }
  writeJson(join(HC01_DIR, 'seed.json'), seedData)
  return seedData
}

export function allFixtureBudgets(seedData) {
  return [
    seedData.hot,
    ...seedData.multiBudget,
    ...seedData.multiOrg,
    seedData.exhaust,
    seedData.uncertain,
    seedData.rateLimit,
    seedData.providerFailure,
    seedData.sseCancel,
  ]
}
