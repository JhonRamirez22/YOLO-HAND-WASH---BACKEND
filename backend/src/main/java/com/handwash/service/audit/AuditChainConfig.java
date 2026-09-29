package com.handwash.service.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuditChainConfig {
    /** Cloud requires the secret (see CloudProfileSafetyGuard); local runs unkeyed and says so. */
    @Bean
    AuditSealStore auditSealStore(@Value("${handwash.audit.seal-bucket:}") String bucket,
                                  @Value("${handwash.audit.seal-kms-key-id:}") String kmsKeyId,
                                  @Value("${handwash.audit.seal-region:sa-east-1}") String region,
                                  @Value("${handwash.audit.seal-dir:}") String sealDir) {
        // On-premises: a local folder replaces the Object Lock bucket.
        if (!sealDir.isBlank()) return new LocalFileAuditSealStore(java.nio.file.Path.of(sealDir));
        return bucket.isBlank() || kmsKeyId.isBlank()
            ? new UnavailableAuditSealStore() : new S3AuditSealStore(region, bucket, kmsKeyId);
    }

    @Bean
    AuditChainHasher auditChainHasher(@Value("${handwash.audit.chain-secret:}") String secret) {
        return new AuditChainHasher(secret);
    }
}
