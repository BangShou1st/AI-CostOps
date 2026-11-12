package com.aicostops.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * P2-17 coverage gate: every Control Plane route implemented by a backend
 * {@code *Controller} must be published in the Control Plane OpenAPI contract.
 *
 * <p>The contract is the union of the core {@code openapi.yaml} and the frozen
 * V3 surface {@code m18-v3-openapi.yaml}; the latter already owns the
 * Provider Hub, Cost Intelligence and AI Advisor routes. Scanned classes are
 * the compiled {@code backend/src/main/java/**}{@code /api/*Controller.java}
 * sources on the test classpath, so a newly added controller route fails this
 * test until it is published. Parameter names are normalized so
 * {@code {id}} and {@code {chargeFactId}} compare equal.
 */
class ControlPlaneOpenApiRouteCoverageTest {

    private static final String API_V1 = "/api/v1";

    private static final List<Path> CONTRACT_FILES = List.of(
            Path.of("..", "docs", "02-development", "api", "openapi.yaml"),
            Path.of("..", "docs", "02-development", "api", "m18-v3-openapi.yaml"));

    @Test
    void everyControllerRouteIsPublishedInTheControlPlaneContract() {
        var implemented = implementedPaths();
        var published = publishedPaths();

        var missing = new TreeSet<String>();
        for (var path : implemented) {
            if (!published.contains(normalize(path))) missing.add(path);
        }
        assertTrue(missing.isEmpty(),
                () -> "Controller routes missing from the Control Plane OpenAPI contract: " + missing);
    }

    private static Set<String> publishedPaths() {
        var published = new HashSet<String>();
        for (var file : CONTRACT_FILES) {
            if (!Files.exists(file)) fail("OpenAPI contract was not found: " + file);
            Map<String, Object> document;
            try (InputStream in = Files.newInputStream(file)) {
                var options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                document = new Yaml(new SafeConstructor(options)).load(in);
            } catch (Exception ex) {
                fail("OpenAPI contract could not be parsed: " + file + " (" + ex.getMessage() + ")");
                return published;
            }
            @SuppressWarnings("unchecked")
            var paths = (Map<String, Object>) document.get("paths");
            if (paths == null) fail("OpenAPI contract has no paths: " + file);
            for (var path : paths.keySet()) published.add(normalize(path));
        }
        return published;
    }

    private static Set<String> implementedPaths() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var paths = new TreeSet<String>();
        for (var candidate : scanner.findCandidateComponents("com.aicostops")) {
            Class<?> controller;
            try {
                controller = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException ex) {
                fail("Controller class disappeared: " + candidate.getBeanClassName());
                return paths;
            }
            if (isTestClasspath(controller)) continue;
            var bases = annotationPaths(controller.getAnnotation(RequestMapping.class));
            for (var method : controller.getDeclaredMethods()) {
                var subs = methodPaths(method);
                if (subs.isEmpty()) continue;
                for (var base : bases) {
                    for (var sub : subs) paths.add(join(base, sub));
                }
            }
        }
        return paths;
    }

    /** Test fixtures declare throwaway controllers; only production routes are contractual. */
    private static boolean isTestClasspath(Class<?> controller) {
        var source = controller.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) return false;
        var location = source.getLocation().toString().replace('\\', '/');
        return location.contains("/test-classes");
    }

    private static List<String> annotationPaths(RequestMapping mapping) {
        if (mapping == null) return List.of("");
        var paths = new ArrayList<String>();
        paths.addAll(List.of(mapping.value()));
        paths.addAll(List.of(mapping.path()));
        if (paths.isEmpty()) paths.add("");
        return paths;
    }

    private static List<String> methodPaths(Method method) {
        var paths = new ArrayList<String>();
        var annotated = false;
        for (var annotation : method.getAnnotations()) {
            if (annotation instanceof GetMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            } else if (annotation instanceof PostMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            } else if (annotation instanceof PutMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            } else if (annotation instanceof PatchMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            } else if (annotation instanceof DeleteMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            } else if (annotation instanceof RequestMapping mapping) {
                annotated = true;
                add(paths, mapping.value(), mapping.path());
            }
        }
        if (!annotated) return List.of();
        if (paths.isEmpty()) return List.of("");
        paths.removeIf(String::isEmpty);
        return paths;
    }

    private static void add(List<String> paths, String[] values, String[] alternatives) {
        for (var value : values) paths.add(value);
        for (var alternative : alternatives) paths.add(alternative);
    }

    private static String join(String base, String sub) {
        var joined = (base + sub).replaceAll("/+", "/");
        return joined.isEmpty() ? "/" : joined;
    }

    /** Contract paths are relative to the {@code /api/v1} server URL. */
    private static String normalize(String path) {
        var normalized = path.replaceAll("\\{[^}]+}", "{}");
        if (normalized.equals(API_V1)) return "/";
        if (normalized.startsWith(API_V1 + "/")) return normalized.substring(API_V1.length());
        return normalized;
    }
}
