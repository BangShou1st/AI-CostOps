package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BillingPeriodQueryService {

    private static final String PERMISSION_BUDGET_READ = "BUDGET_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodMapper mapper;

    public BillingPeriodQueryService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public List<BillingPeriod> list(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_BUDGET_READ);
        return mapper.selectByOrganization(context.organizationId());
    }
}
