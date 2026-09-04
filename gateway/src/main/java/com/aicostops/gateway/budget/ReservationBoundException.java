package com.aicostops.gateway.budget;

/**
 * Fail-closed signal for reservation upper-bound calculation. A
 * budget-controlled request that cannot be safely bounded must be rejected
 * before Provider dispatch; it must never proceed with a guessed amount.
 */
public class ReservationBoundException extends RuntimeException {

    public ReservationBoundException(String message) {
        super(message);
    }

    public ReservationBoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Always true: every bound failure is a fail-closed rejection. */
    public boolean failClosed() {
        return true;
    }
}
