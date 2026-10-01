package com.handwash.service.evidence;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.Optional;

/** S3 with SSE-KMS using the task role; the bucket is private and the key is customer-managed. */
public final class S3EvidenceStore implements EvidenceStore {
    private final S3Client client;
    private final String bucket;
    private final String kmsKeyId;

    public S3EvidenceStore(EvidenceProperties properties) {
        this(S3Client.builder().region(Region.of(properties.region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
            properties.bucket(), properties.kmsKeyId());
    }

    S3EvidenceStore(S3Client client, String bucket, String kmsKeyId) {
        this.client = client;
        this.bucket = bucket;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public void put(String key, byte[] bytes) {
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg")
                .cacheControl("no-store").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(kmsKeyId).build(), RequestBody.fromBytes(bytes));
        } catch (SdkException failure) {
            throw new EvidenceStoreException("No se pudo guardar la foto", failure);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asByteArray());
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (SdkException failure) {
            throw new EvidenceStoreException("No se pudo leer la foto", failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException failure) {
            throw new EvidenceStoreException("No se pudo borrar la foto", failure);
        }
    }
}
