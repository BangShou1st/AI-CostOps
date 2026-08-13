package com.aicostops.iam.api;

import com.aicostops.iam.application.RoleCatalogService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RoleCatalogController {

    private final RoleCatalogService catalog;

    public RoleCatalogController(RoleCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return catalog.roles(authenticatedUser);
    }

    @GetMapping("/permissions")
    public List<PermissionResponse> permissions(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return catalog.permissions(authenticatedUser);
    }
}
