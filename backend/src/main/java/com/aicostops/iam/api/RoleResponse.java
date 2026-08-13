package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;

public record RoleResponse(ApiId id, String code, String name) {
}
