-- M18: register the second built-in Provider, Kilo Gateway.
--
-- V24 is immutable evidence and seeded only OPENCODE_ZEN / CUSTOM_OPENAI_COMPATIBLE, so the
-- additional server-owned Provider is registered by a new forward migration. Kilo Gateway is an
-- OpenAI-compatible universal inference gateway; the server-owned base URL mirrors the Kilo shared
-- library's {@code kilo.gateway.base-url} default (https://ai-gateway.kilo.ai/api/v1), and the same
-- base serves /chat/completions and /models.
--
-- provider_code must equal adapter_code: the Gateway projects adapter_code from this row
-- (RoutingPolicyMapper.findCandidates) and dispatches the matching ProviderChatAdapter, so the
-- catalog value and the adapter's {@code adapterCode()} have to agree byte for byte.
-- status must stay ACTIVE: a DISABLED row nulls adapter_code for every candidate and the Gateway
-- rejects it with ADAPTER_UNAVAILABLE.
--
-- No connection profile is backfilled: the built-in Kilo template is created through the Provider
-- Hub so its server-owned, browser-immutable fields stay governed there.
INSERT INTO provider_catalog(provider_code, name, adapter_code, base_url, status, capabilities_json, created_at, updated_at)
VALUES ('KILO_GATEWAY', 'Kilo Gateway', 'KILO_GATEWAY', 'https://ai-gateway.kilo.ai/api/v1', 'ACTIVE',
        CAST('{"protocols": ["OPENAI_CHAT_COMPLETIONS"]}' AS JSON), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE name = VALUES(name), adapter_code = VALUES(adapter_code),
    base_url = VALUES(base_url), status = VALUES(status),
    capabilities_json = VALUES(capabilities_json), updated_at = UTC_TIMESTAMP(6);
