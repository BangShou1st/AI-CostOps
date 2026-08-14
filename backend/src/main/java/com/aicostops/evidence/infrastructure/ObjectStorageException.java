package com.aicostops.evidence.infrastructure;

/**
 * Bounded, non-secret storage dependency failure. The message must never carry
 * provider file contents or credentials.
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message) {
        super(message);
    }

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
