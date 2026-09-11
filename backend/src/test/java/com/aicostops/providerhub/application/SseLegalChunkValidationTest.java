package com.aicostops.providerhub.application;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class SseLegalChunkValidationTest {
    private final ModelProbeService probes = new ModelProbeService(null, null, null, null, null, null, null, new ObjectMapper(), null, null, null);
    @Test void legalChunkIsAccepted() {
        assertTrue(probes.isLegalStreamingChunk("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}"));
    }
    @Test void nonObjectChoiceIsRejected() {
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[1]}"));
    }
    @Test void emptyChoiceIsRejected() {
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{}]}"));
    }
    @Test void malformedDeltaIsRejected() {
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":123},\"finish_reason\":null}]}"));
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":0,\"delta\":\"oops\",\"finish_reason\":null}]}"));
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":0,\"delta\":{\"role\":7},\"finish_reason\":null}]}"));
    }
    @Test void missingDeltaIsRejected() {
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":0,\"finish_reason\":null}]}"));
    }
    @Test void fractionalIndexIsRejected() {
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":0.5,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}"));
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":1.5,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}"));
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":-1,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}"));
        assertFalse(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":1e3,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}"));
    }
    @Test void nonNegativeIntIndexIsAccepted() {
        assertTrue(probes.isLegalStreamingChunk("{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}"));
    }
} 
