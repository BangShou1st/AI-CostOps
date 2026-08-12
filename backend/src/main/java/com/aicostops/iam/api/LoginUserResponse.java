package com.aicostops.iam.api;

import com.aicostops.shared.json.ApiId;

public record LoginUserResponse(ApiId id, String displayName) {
}
