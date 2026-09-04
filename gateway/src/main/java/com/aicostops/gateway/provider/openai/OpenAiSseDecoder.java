package com.aicostops.gateway.provider.openai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded incremental SSE decoder for the OpenAI Chat Completions stream. */
public final class OpenAiSseDecoder {

    private final int maxEventBytes;
    private final ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
    private final List<String> currentEventData = new ArrayList<>();

    public OpenAiSseDecoder(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }

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
        if (line.startsWith(":")) return;
        if (line.startsWith("data:")) {
            var value = line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            if (value.length() > maxEventBytes) {
                throw new IllegalStateException("SSE data exceeds the bounded event size");
            }
            currentEventData.add(value);
        }
    }
}
