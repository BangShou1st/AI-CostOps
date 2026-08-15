package com.aicostops.cost.application;

/** Outbound port for persisting canonical facts inside the caller's transaction. */
public interface CanonicalCostWritePort {

    CanonicalWriteResult write(CanonicalizationInput input);
}
