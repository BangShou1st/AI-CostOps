package com.aicostops.allocation.application;

import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.attribution.application.AllocationTargetDirectory.TargetRef;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-side target directory for allocation editors: the same-org ACTIVE
 * project, cost center, and team safe refs. Requires ALLOCATION_EDIT at ORG
 * scope so Finance roles can pick targets without needing the master-data
 * read permissions of /projects, /teams, and /cost-centers.
 */
@Service
public class AllocationTargetQueryService {

    private static final String PERMISSION_ALLOCATION_EDIT = "ALLOCATION_EDIT";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationTargetDirectory targets;

    public AllocationTargetQueryService(
            AuthorizationContextService authorizationContexts,
            AllocationTargetDirectory targets) {
        this.authorizationContexts = authorizationContexts;
        this.targets = targets;
    }

    public List<TargetRef> list(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_EDIT);
        return targets.activeTargets(context.organizationId());
    }
}
