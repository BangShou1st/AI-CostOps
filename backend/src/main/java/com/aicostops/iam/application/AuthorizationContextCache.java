package com.aicostops.iam.application;

import com.aicostops.iam.domain.AuthorizationContext;

public interface AuthorizationContextCache {

    AuthorizationContext get(long userId, long securityVersion);

    void put(AuthorizationContext context);

    void evict(long userId, long securityVersion);
}
