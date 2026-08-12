package com.aicostops.iam.api;

import com.aicostops.iam.application.LoginResult;
import com.aicostops.shared.json.ApiId;

public record LoginResponse(
        String accessToken,
        long expiresIn,
        LoginUserResponse user) {

    static LoginResponse from(LoginResult result) {
        return new LoginResponse(
                result.accessToken().token(),
                result.accessToken().expiresInSeconds(),
                new LoginUserResponse(ApiId.of(result.userId()), result.displayName()));
    }
}
