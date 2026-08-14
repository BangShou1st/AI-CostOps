package com.aicostops.evidence.infrastructure;

import com.aicostops.evidence.application.ObjectStoragePort;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceStorageProperties.class)
public class EvidenceStorageConfiguration {

    @Bean
    MinioClient minioClient(EvidenceStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    ObjectStoragePort objectStoragePort(MinioClient minioClient, EvidenceStorageProperties properties) {
        return new MinioObjectStorageAdapter(
                minioClient, properties.bucket(), properties.autoCreateBucket());
    }
}
