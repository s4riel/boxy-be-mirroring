package com.boxy.boxy.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 1 regression guard: every endpoint on every {@code @RestController} must declare
 * {@code @PreAuthorize}, either on the method or on the class. Without this, a new
 * controller method silently defaults to "authenticated only" — exactly the gap that let
 * ~93 endpoints (Sales, Purchasing, Report, Company, Dashboard, Product writes) ship with
 * no role/permission check at all (see the Fase 0/1 security hardening plan).
 *
 * {@link com.boxy.boxy.modules.auth.controller.AuthController} is the only allowed exception —
 * login/refresh/register must be reachable by an unauthenticated caller.
 */
class PreAuthorizeCoverageTest {

    private static final Set<Class<? extends Annotation>> MAPPING_ANNOTATIONS = Set.of(
            org.springframework.web.bind.annotation.GetMapping.class,
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            RequestMapping.class);

    // PublicCatalogController is the anonymous storefront: GET-only, permitted by SecurityConfig
    // (/api/v1/public/**), and it only returns the public DTOs (no costs, no quantities).
    private static final Set<String> EXEMPT_CONTROLLERS = Set.of(
            "com.boxy.boxy.modules.auth.controller.AuthController",
            "com.boxy.boxy.modules.catalog.controller.PublicCatalogController");

    @Test
    void everyControllerEndpointDeclaresPreAuthorize() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> violations = new ArrayList<>();

        for (var candidate : scanner.findCandidateComponents("com.boxy.boxy")) {
            String className = candidate.getBeanClassName();
            if (className == null || EXEMPT_CONTROLLERS.contains(className)) {
                continue;
            }

            Class<?> controllerClass;
            try {
                controllerClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Could not load scanned controller " + className, e);
            }

            boolean classLevelGuard = AnnotationUtils.findAnnotation(controllerClass, PreAuthorize.class) != null;

            for (Method method : controllerClass.getDeclaredMethods()) {
                boolean isEndpoint = MAPPING_ANNOTATIONS.stream()
                        .anyMatch(a -> AnnotationUtils.findAnnotation(method, a) != null);
                if (!isEndpoint) {
                    continue;
                }

                boolean methodLevelGuard = AnnotationUtils.findAnnotation(method, PreAuthorize.class) != null;
                if (!classLevelGuard && !methodLevelGuard) {
                    violations.add(className + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("Endpoints missing @PreAuthorize (add one, or add %s to EXEMPT_CONTROLLERS with a comment "
                        + "explaining why it must stay open)", PreAuthorize.class.getSimpleName())
                .isEmpty();
    }
}
