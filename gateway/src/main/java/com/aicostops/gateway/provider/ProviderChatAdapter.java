package com.aicostops.gateway.provider;

import com.aicostops.gateway.request.ChatCompletionCommand;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provider Adapter boundary (AIC-088). The adapter owns Provider-specific
 * wire/request/response/stream/usage/error semantics only; it never selects
 * Budget, mutates Reservations or posts Ledger/financial truth. After a
 * committed dispatch fence there is no automatic Provider retry.
 */
public interface ProviderChatAdapter {

    Mono<ProviderChatCompletion> complete(ProviderCallContext context, ChatCompletionCommand command);

    Flux<ProviderChatStreamEvent> stream(ProviderCallContext context, ChatCompletionCommand command);
}
