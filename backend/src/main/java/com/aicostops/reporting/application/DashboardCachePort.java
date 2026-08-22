package com.aicostops.reporting.application;

import java.time.Duration;

/**
 * Cache-aside seam for reporting dashboards. Implementations must treat any
 * cache infrastructure failure as a miss (and swallow write failures) so the
 * MySQL read path remains the source of truth — Redis being down never fails
 * a workbench request.
 */
public interface DashboardCachePort {

    <T> T read(String key, Class<T> type);

    void write(String key, Object value, Duration ttl);
}
