package com.aicostops.organization.infrastructure;

import com.aicostops.organization.application.ProviderAccountDirectory;
import com.aicostops.organization.domain.ProviderAccountSnapshot;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MyBatisProviderAccountDirectory implements ProviderAccountDirectory {

    private final ProviderAccountMapper mapper;

    public MyBatisProviderAccountDirectory(ProviderAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ProviderAccountSnapshot> findActive(long organizationId, long providerAccountId) {
        var account = mapper.findCurrentOrganization(providerAccountId, organizationId, "ACTIVE");
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(new ProviderAccountSnapshot(
                account.id(), account.orgId(), account.providerCode(), account.displayName(),
                account.externalAccountRef(), account.status().name()));
    }
}
