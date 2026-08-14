package com.aicostops.evidence.application;

import java.nio.file.Path;

public record StagedEvidence(String sha256, long sizeBytes, Path tempFile) {
}
