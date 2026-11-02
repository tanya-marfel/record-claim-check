/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.UUID;

public class S3Service {
    private final S3Client s3Client;
    private final String bucketName;
    private final String region;

    public S3Service(S3ClaimCheckConfig config) {
        try {
            this.bucketName = config.bucketName();
            this.region = config.region();

            var builder = S3Client.builder().region(Region.of(config.region()));

            if (config.roleArn() != null && !config.roleArn().isEmpty()) {
                String sessionName = config.roleSessionName() != null && !config.roleSessionName().isEmpty()
                        ? config.roleSessionName()
                        : "s3-claim-check-session-" + System.currentTimeMillis();

                StsClient stsClient = StsClient.builder()
                        .region(Region.of(config.region()))
                        .credentialsProvider(DefaultCredentialsProvider.builder().build())
                        .build();

                StsAssumeRoleCredentialsProvider credentialsProvider = StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(AssumeRoleRequest.builder()
                                .roleArn(config.roleArn())
                                .roleSessionName(sessionName)
                                .tags(java.util.Collections.emptyList())
                                .build())
                        .build();

                builder.credentialsProvider(credentialsProvider);
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
            }

            this.s3Client = builder.build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize S3Service: " + e.getMessage(), e);
        }
    }

    public String upload(byte[] data, String topic) {
        String key = String.format("%s/%s/%s",
                topic,
                java.time.LocalDate.now(),
                UUID.randomUUID()
        );

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentLength((long) data.length)
                        .build(),
                RequestBody.fromBytes(data)
        );

        return String.format("s3://%s/%s", bucketName, key);
    }

    public byte[] download(String s3Url) {
        String key = extractKeyFromUrl(s3Url);

        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .build()
        ).asByteArray();
    }

    private String extractKeyFromUrl(String s3Url) {
        if (s3Url.startsWith("s3://")) {
            int firstSlash = s3Url.indexOf('/', 5);
            return firstSlash != -1 ? s3Url.substring(firstSlash + 1) : "";
        }
        if (s3Url.startsWith("https://")) {
            int thirdSlash = s3Url.indexOf('/', 8);
            return thirdSlash != -1 ? s3Url.substring(thirdSlash + 1) : s3Url;
        }
        return s3Url;
    }
}
