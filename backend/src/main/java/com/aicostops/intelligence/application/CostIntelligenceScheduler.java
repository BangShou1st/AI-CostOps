package com.aicostops.intelligence.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Low-frequency DB-backed analysis trigger (M18 V3).
 *
 * <p>MySQL run identity/claim is the convergence authority across
 * replicas; the scheduler only proposes due work.
 */
@Component
public class CostIntelligenceScheduler {

    private final CostIntelligenceService intelligence;

    public CostIntelligenceScheduler(CostIntelligenceService intelligence) {
        this.intelligence = intelligence;
    }

    @Scheduled(fixedDelayString = "${aicostops.intelligence.schedule-delay-ms:3600000}")
    public void runDueAnalyses() {
        intelligence.runDueAnalyses();
    }
}
