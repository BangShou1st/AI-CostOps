package com.aicostops.organization.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.organization.api.CreateProviderAccountRequest;
import com.aicostops.organization.api.ProviderAccountResponse;
import com.aicostops.organization.api.UpdateProviderAccountRequest;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.domain.ProviderAccount;
import com.aicostops.organization.infrastructure.ProviderAccountMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.lang.reflect.Array;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderAccountService {

    private static final List<String> SECRET_KEY_FRAGMENTS =
            List.of("password", "token", "secret", "apikey");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };

    private final AuthorizationContextService authorizationContexts;
    private final ProviderAccountMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProviderAccountService(
            AuthorizationContextService authorizationContexts,
            ProviderAccountMapper mapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PageResponse<ProviderAccountResponse> list(
            AuthenticatedUser authenticatedUser, MasterDataStatus status, PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var statusValue = status == null ? null : status.name();
        var total = mapper.countCurrentOrganization(context.organizationId(), statusValue);
        var providerAccounts = mapper.findCurrentOrganizationPage(
                context.organizationId(), statusValue,
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(
                providerAccounts.stream().map(this::response).toList(), page, total);
    }

    @Transactional
    public ProviderAccountResponse create(
            AuthenticatedUser authenticatedUser, CreateProviderAccountRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var providerCode = normalizeRequired(request.providerCode(), 100, "Provider code");
        var displayName = normalizeRequired(request.displayName(), 200, "Provider account display name");
        var externalAccountRef = normalizeOptional(request.externalAccountRef(), 255, "External account reference");
        var metadataJson = serializeMetadata(request.metadata());
        var now = clock.instant();
        try {
            if (mapper.insert(context.organizationId(), providerCode, displayName,
                    externalAccountRef, metadataJson, now) != 1) {
                throw new IllegalStateException("Provider account creation must insert exactly one row");
            }
        } catch (DuplicateKeyException exception) {
            throw naturalKeyConflict();
        }
        var providerAccount = mapper.findCurrentOrganization(
                mapper.lastInsertId(), context.organizationId(), null);
        if (providerAccount == null) {
            throw new IllegalStateException("Created provider account must be readable in its organization");
        }
        return response(providerAccount);
    }

    @Transactional
    public ProviderAccountResponse update(
            AuthenticatedUser authenticatedUser, long providerAccountId,
            UpdateProviderAccountRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var providerAccount = mapper.findCurrentOrganizationForUpdate(
                context.organizationId(), null, providerAccountId);
        if (providerAccount == null) {
            throw notFound();
        }
        if (request.displayName() == null
                && request.externalAccountRef() == null
                && request.status() == null
                && request.metadata() == null) {
            throw validationFailed("A provider account field is required.");
        }

        var displayName = request.displayName() == null
                ? providerAccount.displayName()
                : normalizeRequired(request.displayName(), 200, "Provider account display name");
        var externalAccountRef = request.externalAccountRef() == null
                ? providerAccount.externalAccountRef()
                : normalizeOptional(request.externalAccountRef(), 255, "External account reference");
        var status = request.status() == null ? providerAccount.status() : request.status();
        if (request.status() != null && !providerAccount.status().canTransitionTo(request.status())) {
            throw conflict("Provider account status conflict",
                    "The requested provider account status transition is not allowed.");
        }
        var metadataJson = request.metadata() == null
                ? providerAccount.metadataJson()
                : serializeMetadata(request.metadata());
        try {
            if (mapper.updateCurrentOrganization(
                    providerAccountId, context.organizationId(), null, displayName,
                    externalAccountRef, status.name(), metadataJson, clock.instant()) != 1) {
                throw notFound();
            }
        } catch (DuplicateKeyException exception) {
            throw naturalKeyConflict();
        }
        var updated = mapper.findCurrentOrganization(providerAccountId, context.organizationId(), null);
        if (updated == null) {
            throw notFound();
        }
        return response(updated);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        rejectSecretKeys(metadata);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw validationFailed("Provider account metadata must be valid JSON.");
        }
    }

    private void rejectSecretKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var normalizedKey = entry.getKey().toString().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                if (SECRET_KEY_FRAGMENTS.stream().anyMatch(normalizedKey::contains)) {
                    throw validationFailed("Provider account metadata contains a forbidden secret key.");
                }
                rejectSecretKeys(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::rejectSecretKeys);
        } else if (value != null && value.getClass().isArray()) {
            for (var index = 0; index < Array.getLength(value); index++) {
                rejectSecretKeys(Array.get(value, index));
            }
        }
    }

    private ProviderAccountResponse response(ProviderAccount providerAccount) {
        try {
            var metadata = providerAccount.metadataJson() == null
                    ? null
                    : objectMapper.readValue(providerAccount.metadataJson(), METADATA_TYPE);
            return ProviderAccountResponse.from(providerAccount, metadata);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored provider account metadata must be valid JSON", exception);
        }
    }

    private String normalizeRequired(String value, int maxLength, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw validationFailed(field + " must be nonblank and at most "
                    + maxLength + " characters.");
        }
        return value.trim();
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw validationFailed(field + " must be at most " + maxLength + " characters.");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private DomainException naturalKeyConflict() {
        return conflict("Provider account conflict",
                "The provider code and display name already exist in the current organization.");
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Provider account validation failed", detail);
    }

    private DomainException conflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT, title, detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The provider account is not available in the current organization.");
    }
}
