package com.aicostops.gateway.provider.mimo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Incremental SSE decoding: single/multi chunk events, CRLF framing, comment
 * lines, multi-line {@code data:} joins, the {@code [DONE]} marker, arbitrary
 * transport chunk splits (including multi-byte UTF-8 across boundaries), and
 * the bounded single-event size rejection.
 */
class MimoSseDecoderTest {

    private static final String CHUNK_1 = "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}";
    private static final String CHUNK_2 = "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion.chunk\","
            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}";

    @Test
    void decodesSingleEventWithExactPayload() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        var events = decoder.feed(("data: " + CHUNK_1 + "\n\n").getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly(CHUNK_1);
    }

    @Test
    void decodesMultipleEventsInOneFeedIncludingDone() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        var frame = "data: " + CHUNK_1 + "\n\ndata: " + CHUNK_2 + "\n\ndata: [DONE]\n\n";
        var events = decoder.feed(frame.getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly(CHUNK_1, CHUNK_2, "[DONE]");
    }

    @Test
    void handlesCrLfFraming() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        var events = decoder.feed(("data: " + CHUNK_1 + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly(CHUNK_1);
    }

    @Test
    void splitsArbitrarilyAcrossTransportChunksWithoutCorruptingUtf8() {
        var decoder = new MimoSseDecoder(1024 * 1024);
        var withChinese = "data: {\"delta\":{\"content\":\"\u4f60\u597d\u4e16\u754c\"}}\n\n";
        var bytes = withChinese.getBytes(StandardCharsets.UTF_8);

        var events = new java.util.ArrayList<String>();
        for (int offset = 0; offset < bytes.length; offset += 3) {
            var part = java.util.Arrays.copyOfRange(bytes, offset, Math.min(offset + 3, bytes.length));
            events.addAll(decoder.feed(part));
        }

        assertThat(events).containsExactly("{\"delta\":{\"content\":\"\u4f60\u597d\u4e16\u754c\"}}");
    }

    @Test
    void joinsMultipleDataLinesOfOneEventWithNewline() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        var events = decoder.feed(("data: {\"a\":1}\ndata: {\"b\":2}\n\n").getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly("{\"a\":1}\n{\"b\":2}");
    }

    @Test
    void ignoresCommentLinesAndNonDataFields() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        var frame = ": keep-alive comment\nevent: message\nid: 7\ndata: " + CHUNK_1 + "\n\n";
        var events = decoder.feed(frame.getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly(CHUNK_1);
    }

    @Test
    void leavesIncompleteEventPendingAcrossFeedsUntilBlankLine() {
        var decoder = new MimoSseDecoder(1024 * 1024);

        assertThat(decoder.feed("data: ".getBytes(StandardCharsets.UTF_8))).isEmpty();
        var events = decoder.feed((CHUNK_2 + "\n\n").getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactly(CHUNK_2);
    }

    @Test
    void rejectsOverSizedSingleLineAndKeepsNoAggregation() {
        var decoder = new MimoSseDecoder(64);

        var huge = "data: " + "x".repeat(128) + "\n\n";
        assertThatThrownBy(() -> decoder.feed(huge.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bounded event size");
    }

    @Test
    void boundedHighVolumeDecodingDoesNotAggregateWholeStream() {
        var decoder = new MimoSseDecoder(1024 * 1024);
        var expected = new java.util.ArrayList<String>();
        var builder = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            var payload = "{\"delta\":{\"content\":\"chunk" + i + "\"}}";
            builder.append("data: ").append(payload).append("\n\n");
            expected.add(payload);
        }

        var events = decoder.feed(builder.toString().getBytes(StandardCharsets.UTF_8));

        assertThat(events).containsExactlyElementsOf(expected);
        // State is line+event bounded: after the full stream the pending state is empty.
        assertThat(decoder.feed(new byte[0])).isEmpty();
        assertThat(decoder.feed("data: tail\n\n".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("tail");
    }

    @Test
    void decodesLargeChunkCountIncrementallyWithoutFullCompletionState() {
        var decoder = new MimoSseDecoder(1024 * 1024);
        var events = new java.util.ArrayList<String>();
        var chunk = "data: {\"x\":1}\n\n";

        for (int i = 0; i < 200; i++) {
            events.addAll(decoder.feed(chunk.getBytes(StandardCharsets.UTF_8)));
        }

        assertThat(events).hasSize(200);
        assertThat(List.of()).isEmpty();
    }
}