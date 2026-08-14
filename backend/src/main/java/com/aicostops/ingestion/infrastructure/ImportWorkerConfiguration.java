package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.application.ImportAttemptExecutor;
import com.aicostops.ingestion.application.ImportLeaseService;
import com.aicostops.ingestion.application.ImportWorkerCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportWorkerProperties.class)
@EnableScheduling
public class ImportWorkerConfiguration {

    @Bean
    TaskExecutor importTaskExecutor(ImportWorkerProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerConcurrency());
        executor.setMaxPoolSize(properties.workerConcurrency());
        // No unbounded queue: local permits are the backpressure.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("import-worker-");
        executor.initialize();
        return executor;
    }

    @Bean
    TaskScheduler importHeartbeatScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("import-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(name = "aicostops.ingestion.worker-enabled", havingValue = "true")
    ImportWorkerCoordinator importWorkerCoordinator(
            ImportLeaseService leases,
            ImportAttemptExecutor executor,
            ImportWorkerProperties properties,
            @Qualifier("importTaskExecutor") TaskExecutor taskExecutor) {
        return new ImportWorkerCoordinator(leases, executor, properties, taskExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "aicostops.ingestion.worker-enabled", havingValue = "true")
    java.util.concurrent.ScheduledFuture<?> importHeartbeatTask(
            TaskScheduler scheduler,
            ImportAttemptExecutor executor,
            ImportWorkerProperties properties) {
        return scheduler.scheduleAtFixedRate(
                executor::heartbeatActiveExecutions, properties.heartbeatInterval());
    }
}
