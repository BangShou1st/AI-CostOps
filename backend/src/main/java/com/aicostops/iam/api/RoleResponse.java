package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;
import java.util.List;

public record RoleResponse(ApiId id, String code, String name, List<PermissionResponse> permissions) {
}
