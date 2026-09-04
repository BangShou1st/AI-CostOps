package com.aicostops.gateway.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seeds one org-scoped M11 runtime projection for gateway integration tests:
 * OPEN billing period + project + SERVICE credential + explicit model
 * relation + MIMO provider route with an ACTIVE Provider credential and an
 * ACTIVE Pricing Version.
 */
public final class GatewayTestFixture {

    public static final String PROVIDER_CODE = "MIMO";
    public static final String PROVIDER_MODEL_NAME = "mimo-v2.5-pro";
    public static final String MODEL_KEY = "default-chat";
    public static final String TEST_KEK = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    public static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";

    // Shared test-only nonce source. SecureRandom seeding is expensive, so one
    // static instance serves every provider-secret encryption in tests.
    private static final java.security.SecureRandom NONCE_RANDOM = new java.security.SecureRandom();

    private GatewayTestFixture() {
    }

    /** @param rawKey a full aic_<prefix>_<secret> key; the digest is derived from its secret part. */
    public static SeededEnv seed(JdbcTemplate jdbc, String suffix, String hmacKeyBase64, String rawKey) {
        return seed(jdbc, suffix, hmacKeyBase64, rawKey, TEST_KEK, "sk-test-secret", DEFAULT_BASE_URL);
    }

    /** Extended seed for controller tests: real encryption + mock upstream base URL. */
    public static SeededEnv seed(JdbcTemplate jdbc, String suffix, String hmacKeyBase64, String rawKey,
            String kekBase64, String providerSecret, String baseUrl) {
        var orgId = insertId(jdbc, "organization(name,slug,status,settings_json,created_at,updated_at)",
                "VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "M11 " + suffix, "gw-test-" + suffix);

        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,closing_started_at,
                  closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 7 DAY),
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY),'OPEN',0,NULL,NULL,NULL,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = insertLastId(jdbc);

        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Gateway Test Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "proj-" + suffix);
        var projectId = insertLastId(jdbc);

        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Gateway Test Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceIdentityId = insertLastId(jdbc);

        // model_catalog and provider_model are global (org-independent) catalog
        // tables: reuse them idempotently by their global unique key so several
        // org-scoped seeds in one test class share the same logical model.
        var modelRows = jdbc.queryForList(
                "SELECT id FROM model_catalog WHERE model_key=?", Long.class, MODEL_KEY);
        long modelId;
        if (modelRows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO model_catalog(
                      model_key,name,status,capabilities_json,default_max_output_tokens,
                      max_output_tokens,created_at,updated_at)
                    VALUES (?,'Gateway Test Model','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                      8192,131072,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, MODEL_KEY);
            modelId = insertLastId(jdbc);
        } else {
            modelId = modelRows.get(0);
        }

        // Global catalogs are shared across orgs; seed them idempotently.
        var existingProviders = jdbc.queryForList(
                "SELECT provider_code FROM provider_catalog WHERE provider_code=?",
                String.class, PROVIDER_CODE);
        if (existingProviders.isEmpty()) {
            jdbc.update("""
                    INSERT INTO provider_catalog(
                      provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at)
                    VALUES (?,'MiMo','MIMO',?,'ACTIVE',JSON_OBJECT(),
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, PROVIDER_CODE, baseUrl);
        }
        var providerModelRows = jdbc.queryForList("""
                SELECT id FROM provider_model
                WHERE provider_code=? AND model_id=? AND provider_model_name=?
                """, Long.class, PROVIDER_CODE, modelId, PROVIDER_MODEL_NAME);
        long providerModelId;
        if (providerModelRows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO provider_model(
                      provider_code,model_id,provider_model_name,status,routing_eligible,
                      capabilities_json,created_at,updated_at)
                    VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, PROVIDER_CODE, modelId, PROVIDER_MODEL_NAME);
            providerModelId = insertLastId(jdbc);
        } else {
            providerModelId = providerModelRows.get(0);
        }

        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,?,'Gateway Test Account','gw-acct','ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, PROVIDER_CODE);
        var providerAccountId = insertLastId(jdbc);

        var encrypted = encryptProviderSecret(providerSecret, kekBase64, orgId, providerAccountId);
        jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?,'API_KEY',?,?,1,'gw-test','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, orgId, providerAccountId, encrypted.ciphertext(), encrypted.nonce());
        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,effective_to,status,created_at,activated_at,retired_at)
                VALUES (?,?,?,1,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 30 DAY),
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 DAY),'ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = insertLastId(jdbc);
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'INPUT_TOKEN',1000000,'30.00000000')
                """, orgId, pricingVersionId);
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'OUTPUT_TOKEN',1000000,'60.00000000')
                """, orgId, pricingVersionId);

        var parsed = parseKey(rawKey);
        var digest = hmac(parsed.secretPart(), hmacKeyBase64);
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?, 'PROJECT',?,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, parsed.prefix(), digest, serviceIdentityId, projectId, projectId);
        var credentialId = insertLastId(jdbc);
        jdbc.update("""
                INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, credentialId, orgId, modelId);

        // M12: one PROJECT Budget for the credential financial scope so
        // reservation admission has a matching Budget by default. Tests that
        // need REQUIRED-no-Budget / OPTIONAL-unbudgeted delete or exhaust it.
        jdbc.update("""
                INSERT INTO budget(
                  org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?, 'PROJECT',?,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        var budgetId = insertLastId(jdbc);

        return new SeededEnv(orgId, periodId, modelId, providerAccountId, providerModelId,
                pricingVersionId, credentialId, serviceIdentityId, projectId, budgetId, rawKey,
                parsed.prefix(), parsed.secretPart(), MODEL_KEY);
    }

    /** FK-safe cleanup of all M11/M12 rows for tests sharing one container. */
    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id = NULL");
        jdbc.update("DELETE FROM gateway_usage_dimension");
        jdbc.update("UPDATE gateway_usage_fact SET supersedes_usage_fact_id = NULL");
        jdbc.update("DELETE FROM gateway_usage_fact");
        jdbc.update("DELETE FROM budget_reservation");
        jdbc.update("DELETE FROM budget");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id = NULL");
        jdbc.update("DELETE FROM gateway_route_attempt");
        jdbc.update("DELETE FROM gateway_request");
        jdbc.update("DELETE FROM gateway_credential_model");
        jdbc.update("DELETE FROM gateway_credential");
        jdbc.update("DELETE FROM provider_credential");
        jdbc.update("DELETE FROM pricing_rate");
        jdbc.update("DELETE FROM pricing_version");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM service_identity");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM billing_period");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE FROM provider_model");
        jdbc.update("DELETE FROM model_catalog");
        jdbc.update("DELETE FROM provider_catalog");
    }

    /** AES-256-GCM encryption mirroring the Control Plane encryptor AAD contract. */
    private static Encrypted encryptProviderSecret(String secret, String kekBase64,
            long orgId, long providerAccountId) {
        try {
            var nonce = new byte[12];
            NONCE_RANDOM.nextBytes(nonce);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(
                            Base64.getDecoder().decode(kekBase64.trim()), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            var aad = ("aicostops:v2:provider-credential:v1\0" + orgId + "\0" + providerAccountId
                    + "\0API_KEY\0" + "1").getBytes(StandardCharsets.UTF_8);
            cipher.updateAAD(aad);
            return new Encrypted(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), nonce);
        } catch (Exception ex) {
            throw new IllegalStateException("Test crypto unavailable", ex);
        }
    }

    private record Encrypted(byte[] ciphertext, byte[] nonce) {
    }

    private static long insertId(JdbcTemplate jdbc, String columns, String values, Object... args) {
        jdbc.update("INSERT INTO " + columns + " " + values, args);
        return insertLastId(jdbc);
    }

    private static long insertLastId(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static Parsed parseKey(String rawKey) {
        var matcher = java.util.regex.Pattern
                .compile("^aic_([0-9a-hjkmnp-tv-z]{12})_([A-Za-z0-9_-]{43})$")
                .matcher(rawKey.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid test gateway key");
        }
        return new Parsed(matcher.group(1), matcher.group(2));
    }

    private static byte[] hmac(String value, String hmacKeyBase64) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(hmacKeyBase64.trim()), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    public record SeededEnv(
            long orgId,
            long periodId,
            long modelId,
            long providerAccountId,
            long providerModelId,
            long pricingVersionId,
            long credentialId,
            long serviceIdentityId,
            long projectId,
            long budgetId,
            String rawKey,
            String prefix,
            String secretPart,
            String modelKey) {
    }

    private record Parsed(String prefix, String secretPart) {
    }
}
