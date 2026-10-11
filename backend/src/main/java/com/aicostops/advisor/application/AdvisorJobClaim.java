package com.aicostops.advisor.application;

public record AdvisorJobClaim(long jobId, long organizationId, String token, int attemptNo) {}
