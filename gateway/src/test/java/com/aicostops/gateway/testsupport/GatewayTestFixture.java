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

    private GatewayTestFixture() {
    }

    /** @param rawKey a full aic_<prefix>_<secret> key; the digest is derived from its secret part. */
    public static SeededEnv seed(JdbcTemplate jdbc, String suffix, String hmacKeyBase64, String rawKey) {
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

        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,'Gateway Test Model','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                  8192,131072,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, MODEL_KEY + "-" + suffix);
        var modelId = insertLastId(jdbc);

        // Global catalogs are shared across orgs; seed them idempotently.
        var existingProviders = jdbc.queryForList(
                "SELECT provider_code FROM provider_catalog WHERE provider_code=?",
                String.class, PROVIDER_CODE);
        if (existingProviders.isEmpty()) {
            jdbc.update("""
                    INSERT INTO provider_catalog(
                      provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at)
                    VALUES (?,'MiMo','MIMO','https://api.xiaomimimo.com/v1','ACTIVE',JSON_OBJECT(),
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, PROVIDER_CODE);
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

        jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?,'API_KEY',?,'123456789012',1,'gw-test','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, orgId, providerAccountId, new byte[48]);
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

        return new SeededEnv(orgId, periodId, modelId, providerAccountId, providerModelId,
                pricingVersionId, credentialId, serviceIdentityId, projectId, rawKey,
                parsed.prefix(), parsed.secretPart());
    }

    /** FK-safe cleanup of all M11 rows for tests sharing one container. */
    public static void clean(JdbcTemplate jdbc) {
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
            String rawKey,
            String prefix,
            String secretPart) {
    }

    private record Parsed(String prefix, String secretPart) {
    }
}