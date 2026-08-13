package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;

public record PermissionResponse(ApiId id, String code, String name) {
}
