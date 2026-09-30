package com.handwash.service.audit;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Puts seals in a bucket whose default Object Lock retention makes them undeletable for the retention period. */
public final class S3AuditSealStore implements AuditSealStore {
    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

    public S3AuditSealStore(String region, String bucket, String kmsKeyId) {
        this(S3Client.builder().region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build(), bucket, kmsKeyId);
    }

    S3AuditSealStore(S3Client client, String bucket, String kmsKeyId) {
        this.client = client;
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public void put(String key, byte[] bytes) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/json")
                .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId).build(),
                RequestBody.fromBytes(bytes));
        } catch (SdkException failure) {
            throw new IllegalStateException("No se pudo guardar el sello de auditoría", failure);
        }
    }

    @Override public boolean durable() { return true; }
}
