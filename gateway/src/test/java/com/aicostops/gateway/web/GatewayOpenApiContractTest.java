package com.aicostops.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Gate the frozen {@code docs/02-development/api/gateway-openapi.yaml} machine
 * contract against the runtime: 3.1 parse, the two recognized operations,
 * Bearer/Idempotency-Key requirements, optional success usage, the OpenAI
 * error envelope, fully resolving local {@code $ref} targets, and the exact
 * AIC-092 error-code set matching {@link GatewayErrorCode}.
 */
class GatewayOpenApiContractTest {

    private static final Path YAML = Path.of("..", "docs", "02-development", "api",
            "gateway-openapi.yaml");

    @Test
    void parsesAsOpenApi31WithTwoOperationsAtGatewayBase() throws Exception {
        var root = load();

        assertThat(root.get("openapi")).isEqualTo("3.1.0");
        var servers = asList(root.get("servers"));
        assertThat(servers).singleElement().satisfies(server -> {
            var map = (Map<?, ?>) server;
            assertThat(map.get("url")).isEqualTo("/v1");
        });
        var security = asList(root.get("security"));
        assertThat(security).isNotEmpty();
        assertThat(security.get(0)).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) security.get(0)).get("GatewayBearer")).isNotNull();

        var paths = asMap(root.get("paths"));
        assertThat(paths.keySet())
                .containsExactlyInAnyOrder("/chat/completions", "/gateway/requests/{requestId}");
        var post = asMap(paths.get("/chat/completions"));
        assertThat(post.get("post")).isNotNull();
        assertThat(asMap(post.get("post")).get("operationId")).isEqualTo("createChatCompletion");
        var get = asMap(paths.get("/gateway/requests/{requestId}"));
        assertThat(get.get("get")).isNotNull();
        assertThat(asMap(get.get("get")).get("operationId")).isEqualTo("getGatewayRequestStatus");
    }

    @Test
    void idempotencyKeyIsRequiredAndSuccessUsageIsOptional() throws Exception {
        var root = load();
        var paths = asMap(root.get("paths"));
        var postOperation = asMap(asMap(paths.get("/chat/completions")).get("post"));
        var params = asList(postOperation.get("parameters"));
        var components = asMap(root.get("components"));
        var idempotencyRef = (String) asMap(params.get(0)).get("$ref");
        assertThat(idempotencyRef).isEqualTo("#/components/parameters/IdempotencyKey");
        var idempotency = asMap(asMap(components.get("parameters")).get("IdempotencyKey"));
        assertThat(idempotency.get("required")).isEqualTo(true);
        assertThat(idempotency.get("in")).isEqualTo("header");

        var schemas = asMap(components.get("schemas"));
        var chatCompletion = asMap(schemas.get("ChatCompletion"));
        var required = asList(chatCompletion.get("required")).stream()
                .map(Object::toString)
                .toList();
        assertThat(required).contains("id", "object", "created", "model", "choices")
                .doesNotContain("usage");
        assertThat(chatCompletion.get("additionalProperties")).isEqualTo(false);

        var requestSchema = asMap(schemas.get("ChatCompletionRequest"));
        assertThat(requestSchema.get("additionalProperties")).isEqualTo(false);
        var requestRequired = asList(requestSchema.get("required")).stream()
                .map(Object::toString)
                .toList();
        assertThat(requestRequired).containsExactlyInAnyOrder("model", "messages");
    }

    @Test
    void errorEnvelopeCodesMatchFrozenGatewayErrorCodeSet() throws Exception {
        var root = load();
        var components = asMap(root.get("components"));
        var schemas = asMap(components.get("schemas"));
        var error = asMap(schemas.get("GatewayError"));
        var errorProperties = asMap(error.get("properties"));
        var yamlCodes = asList(asMap(errorProperties.get("code")).get("enum")).stream()
                .map(Object::toString)
                .toList();
        var javaCodes = java.util.Arrays.stream(GatewayErrorCode.values())
                .map(Enum::name)
                .toList();

        assertThat(yamlCodes).containsExactlyInAnyOrderElementsOf(javaCodes);
        var errorTypes = asList(asMap(errorProperties.get("type")).get("enum")).stream()
                .map(Object::toString)
                .toList();
        assertThat(errorTypes).contains(
                "invalid_request_error", "authentication_error", "permission_error",
                "conflict_error", "insufficient_quota", "rate_limit_error", "server_error");
    }

    @Test
    void streamSurfaceAndStatusSchemaAreFrozen() throws Exception {
        var root = load();
        var components = asMap(root.get("components"));
        var schemas = asMap(components.get("schemas"));

        var status = asMap(schemas.get("GatewayRequestStatus"));
        var statusProperties = asMap(status.get("properties"));
        var required = asList(status.get("required")).stream()
                .map(Object::toString)
                .toList();
        assertThat(required).contains("requestId", "requestState", "meteringStatus",
                "settlementStatus", "createdAt", "updatedAt");
        assertThat(asList(asMap(statusProperties.get("requestState")).get("enum")).stream()
                .map(Object::toString).toList())
                .contains("TRANSPORT_COMPLETED", "CANCELED_AFTER_DISPATCH",
                        "TIMED_OUT_AFTER_DISPATCH", "FAILED_AFTER_DISPATCH");

        var requestId = asMap(schemas.get("GatewayRequestId"));
        assertThat(requestId.get("pattern").toString()).startsWith("^gwr_");
    }

    @Test
    void everyLocalRefResolvesWithinTheDocument() throws Exception {
        var root = load();
        var refs = new java.util.ArrayList<String>();
        collectRefs(root, refs);

        assertThat(refs).isNotEmpty();
        for (var ref : refs) {
            assertThat(ref).startsWith("#/");
            var resolved = resolve(root, ref);
            assertThat(resolved).describedAs("unresolved ref %s", ref).isNotNull();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() throws Exception {
        try (var in = Files.newInputStream(YAML)) {
            var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            var loaded = yaml.load(in);
            assertThat(loaded).isInstanceOf(Map.class);
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isNotNull();
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        assertThat(value).isNotNull();
        return (List<Object>) value;
    }

    private static void collectRefs(Object node, List<String> refs) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref) {
                    refs.add(ref);
                } else {
                    collectRefs(entry.getValue(), refs);
                }
            }
        } else if (node instanceof List<?> list) {
            for (var item : list) {
                collectRefs(item, refs);
            }
        }
    }

    private static Object resolve(Map<String, Object> root, String ref) {
        Object current = root;
        for (var segment : ref.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}