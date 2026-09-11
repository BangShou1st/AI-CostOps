package com.aicostops.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * M18 backend contract freeze gate: the frozen YAML and the implemented
 * controllers must agree on every V3 path, money stays decimal-string and
 * secrets never appear in API projections.
 */
class M18OpenApiContractTest {

    private static final Set<String> FROZEN_PREFIXES = Set.of(
            "/api/v1/provider-templates", "/api/v1/provider-connections",
            "/api/v1/cost-intelligence", "/api/v1/ai-advisor");

    private static final Set<String> ALWAYS_FORBIDDEN_FRAGMENTS = Set.of(
            "ciphertext", "nonce", "digest", "authorization", "password", "providersecret");

    @Test
    void frozenPathsMatchImplementedControllers() throws Exception {
        var implemented = implementedPaths();
        var frozen = frozenPaths();
        var missing = new TreeSet<>(frozen);
        missing.removeAll(implemented);
        assertTrue(missing.isEmpty(), () -> "Frozen paths without implementation: " + missing);
        var undocumented = new TreeSet<String>();
        for (var path : implemented) {
            if (isFrozenScope(path) && !frozen.contains(path)) undocumented.add(path);
        }
        assertTrue(undocumented.isEmpty(), () -> "Implemented V3 paths missing from freeze: " + undocumented);
    }

    @Test
    void moneyFieldsStayDecimalString() throws Exception {
        var violations = new TreeSet<String>();
        for (var type : apiRecords()) {
            for (var field : type.getRecordComponents()) {
                var name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("amount") || name.contains("cost") || name.contains("saving")
                        || name.contains("price") || name.contains("percent") || name.contains("total")
                        || name.contains("exposure")) {
                    var fieldType = field.getType();
                    if (!fieldType.equals(String.class)
                            && !fieldType.equals(java.math.BigDecimal.class)) {
                        violations.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Money must be decimal-string: " + violations);
    }

    @Test
    void secretsNeverAppearInApiProjections() throws Exception {
        var violations = new TreeSet<String>();
        for (var type : apiRecords()) {
            for (var field : type.getRecordComponents()) {
                var name = field.getName().toLowerCase(java.util.Locale.ROOT);
                for (var fragment : ALWAYS_FORBIDDEN_FRAGMENTS) {
                    if (name.contains(fragment)) {
                        violations.add(type.getSimpleName() + "." + field.getName());
                    }
                }
                if (name.contains("rawsecret") && !type.getSimpleName().endsWith("Body")
                        && !type.getSimpleName().endsWith("Request")) {
                    violations.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Secret leak in API projection: " + violations);
    }

    private static boolean isFrozenScope(String path) {
        return FROZEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> frozenPaths() throws Exception {
        var candidates = new Path[] {
            Path.of("../docs/03-acceptance/m18-openapi.yaml"),
            Path.of("docs/03-acceptance/m18-openapi.yaml") };
        Path found = null;
        for (var candidate : candidates) {
            if (Files.exists(candidate)) {
                found = candidate;
                break;
            }
        }
        if (found == null) fail("m18-openapi.yaml was not found");
        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(found)) {
            document = new Yaml().load(in);
        }
        var paths = (Map<String, Object>) document.get("paths");
        var frozen = new HashSet<String>();
        for (var path : paths.keySet()) frozen.add("/api/v1" + path);
        return frozen;
    }

    private static Set<String> implementedPaths() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var paths = new HashSet<String>();
        for (var candidate : scanner.findCandidateComponents("com.aicostops")) {
            try {
                var type = Class.forName(candidate.getBeanClassName());
                var bases = basePaths(type.getAnnotation(RequestMapping.class));
                for (var method : type.getDeclaredMethods()) {
                    for (var sub : methodPaths(method)) {
                        for (var base : bases) paths.add(normalize(base + sub));
                    }
                }
            } catch (ClassNotFoundException ex) {
                fail("Controller class disappeared: " + candidate.getBeanClassName());
            }
        }
        return paths;
    }

    private static Set<Class<?>> apiRecords() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var types = new HashSet<Class<?>>();
        for (var candidate : scanner.findCandidateComponents("com.aicostops")) {
            try {
                var controller = Class.forName(candidate.getBeanClassName());
                collectRecords(controller, types);
            } catch (ClassNotFoundException ex) {
                fail("Controller class disappeared: " + candidate.getBeanClassName());
            }
        }
        return types;
    }

    private static void collectRecords(Class<?> type, Set<Class<?>> sink) {
        collectRecords(type, sink, new HashSet<>());
    }

    private static void collectRecords(Class<?> type, Set<Class<?>> sink, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        if (type.isRecord() && type.getPackageName() != null
                && type.getPackageName().contains(".api.")) sink.add(type);
        for (var nested : type.getDeclaredClasses()) collectRecords(nested, sink, visited);
        for (var method : type.getDeclaredMethods()) {
            addIfApiRecord(method.getReturnType(), sink, visited);
            for (var param : method.getParameterTypes()) addIfApiRecord(param, sink, visited);
            for (var param : method.getParameters()) {
                addIfApiRecord(param.getType(), sink, visited);
                for (var nested : param.getType().getDeclaredClasses()) {
                    collectRecords(nested, sink, visited);
                }
            }
        }
    }

    private static void addIfApiRecord(Class<?> type, Set<Class<?>> sink, Set<Class<?>> visited) {
        if (type != null && type.isRecord() && type.getPackageName() != null
                && type.getPackageName().contains(".api.")) collectRecords(type, sink, visited);
    }

    private static List<String> basePaths(RequestMapping mapping) {
        if (mapping == null) return java.util.List.of("");
        if (mapping.value().length > 0) return java.util.List.of(mapping.value());
        return java.util.List.of(mapping.path());
    }

    private static List<String> methodPaths(Method method) {
        var paths = new HashSet<String>();
        for (var annotation : method.getAnnotations()) {
            if (annotation instanceof GetMapping get) Collections.addAll(paths, get.value(), get.path());
            else if (annotation instanceof PostMapping post) Collections.addAll(paths, post.value(), post.path());
            else if (annotation instanceof PutMapping put) Collections.addAll(paths, put.value(), put.path());
            else if (annotation instanceof PatchMapping patch) Collections.addAll(paths, patch.value(), patch.path());
            else if (annotation instanceof DeleteMapping delete) Collections.addAll(paths, delete.value(), delete.path());
            else if (annotation instanceof RequestMapping mapping) {
                Collections.addAll(paths, mapping.value(), mapping.path());
            }
        }
        if (paths.isEmpty()) return java.util.List.of("");
        if (paths.size() == 1 && paths.contains("")) return java.util.List.of("");
        paths.remove("");
        return new java.util.ArrayList<>(paths);
    }

    private static String normalize(String path) {
        return path.replace("//", "/");
    }

    private static final class Collections {
        private Collections() {
        }

        static void addAll(Set<String> sink, String[] a, String[] b) {
            for (var value : a) sink.add(value);
            for (var value : b) sink.add(value);
        }
    }
}
