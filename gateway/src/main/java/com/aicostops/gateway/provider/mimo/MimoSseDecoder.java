package com.aicostops.gateway.provider.mimo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental SSE decoder for the MiMo/OpenAI stream shape. It never
 * aggregates the whole completion: state is bounded to the current line plus
 * the current event's {@code data:} payloads, and one over-sized line/event
 * fails the stream instead of growing memory without limit.
 *
 * <p>Only {@code data:} fields are observed. Comment lines ({@code : ...})
 * and {@code event:}/{@code id:}/{@code retry:} fields are ignored for the
 * M11 subset. An empty line terminates an event; multiple {@code data:} lines
 * of one event are joined with {@code \n}, matching SSE semantics.
 *
 * <p>Byte-level buffering keeps multi-byte UTF-8 JSON intact across arbitrary
 * transport chunk boundaries: {@code \n}/{@code \r} are single ASCII bytes, so
 * a character is never split at the point where the decoder scans line ends.
 */
public final class MimoSseDecoder {

    private final int maxEventBytes;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private final List<String> currentEventData = new ArrayList<>();

    public MimoSseDecoder(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }

    /** Feeds transport bytes and returns the completed {@code data:} payloads, in order. */
    public List<String> feed(byte[] bytes) {
        var completed = new ArrayList<String>();
        for (byte value : bytes) {
            if (value == (byte) '\n') {
                handleLine(pendingLine.toString(StandardCharsets.UTF_8), completed);
                pendingLine.reset();
            } else {
                pendingLine.write(value & 0xFF);
                if (pendingLine.size() > maxEventBytes) {
                    throw new IllegalStateException("SSE data line exceeds the bounded event size");
                }
            }
        }
        return completed;
    }

    private void handleLine(String rawLine, List<String> completed) {
        var line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        if (line.isEmpty()) {
            if (!currentEventData.isEmpty()) {
                completed.add(String.join("\n", currentEventData));
                currentEventData.clear();
            }
            return;
        }
        if (line.startsWith(":")) {
            return;
        }
        if (line.startsWith("data:")) {
            var value = line.substring("data:".length());
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (value.length() > maxEventBytes) {
                throw new IllegalStateException("SSE data exceeds the bounded event size");
            }
            currentEventData.add(value);
        }
    }
}