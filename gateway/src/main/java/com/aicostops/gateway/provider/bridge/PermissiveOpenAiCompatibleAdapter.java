package com.aicostops.gateway.provider.bridge;

import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.ProviderType;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TurnRequest;
import io.github.bangshou1st.modelbridge.core.model.TurnResult;
import io.github.bangshou1st.modelbridge.core.protocol.ChatProtocolAdapter;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.protocol.openai.OpenAiCompatibleAdapter;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps AI CostOps' existing stream contract while using the bridge wire parser.
 *
 * <p>The bridge is deliberately strict for general consumers. A Gateway stream
 * is complete when the Provider sends {@code [DONE]}; some compatible providers
 * (and the frozen integration fixtures) omit the terminal {@code finish_reason}.
 * CostOps must still finalize the observed usage and close the stream, so this
 * wrapper converts only those end-of-stream validation failures into a terminal
 * {@link StreamChunk}. Malformed frames and upstream stream errors stay fatal.
 */
final class PermissiveOpenAiCompatibleAdapter implements ChatProtocolAdapter {

    private final OpenAiCompatibleAdapter delegate;

    PermissiveOpenAiCompatibleAdapter(OpenAiCompatibleAdapter delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Set<ProviderType> providerTypes() {
        return delegate.providerTypes();
    }

    @Override
    public Set<ProtocolId> protocols() {
        return delegate.protocols();
    }

    @Override
    public ChatResponse complete(ProviderConfig provider, ChatRequest request) {
        return delegate.complete(provider, request);
    }

    @Override
    public void completeStream(
            ProviderConfig provider, ChatRequest request, Consumer<StreamChunk> consumer) {
        var done = new AtomicBoolean();
        try {
            delegate.completeStream(provider, request, chunk -> {
                if (chunk.type() == StreamChunk.Type.DONE) {
                    done.set(true);
                }
                consumer.accept(chunk);
            });
        } catch (ModelBridgeException failure) {
            if (!benignStreamEnd(failure)) {
                throw failure;
            }
            if (!done.get()) {
                consumer.accept(StreamChunk.done(
                        FinishReason.UNKNOWN,
                        failure.usage(), request.model(), provider.id()));
            }
        }
    }

    @Override
    public TurnResult turn(ProviderConfig provider, TurnRequest request) {
        return delegate.turn(provider, request);
    }

    private static boolean benignStreamEnd(ModelBridgeException failure) {
        if (failure.category() != ErrorCategory.INVALID_RESPONSE) {
            return false;
        }
        var message = failure.getMessage();
        return message != null
                && (message.contains("finish_reason")
                        || message.contains("neither text nor tool calls")
                        || message.contains("returned no choices"));
    }
}
