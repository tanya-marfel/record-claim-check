/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck;

import io.kroxylicious.kafka.common.compress.Compression;
import io.kroxylicious.kafka.common.message.FetchResponseData;
import io.kroxylicious.kafka.common.message.ProduceRequestData;
import io.kroxylicious.kafka.common.message.RequestHeaderData;
import io.kroxylicious.kafka.common.message.ResponseHeaderData;
import io.kroxylicious.kafka.common.protocol.ApiKeys;
import io.kroxylicious.kafka.common.protocol.ApiMessage;
import io.kroxylicious.kafka.common.record.TimestampType;
import io.kroxylicious.kafka.common.record.internal.MemoryRecords;
import io.kroxylicious.kafka.common.record.internal.MemoryRecordsBuilder;
import io.kroxylicious.kafka.common.record.internal.Record;
import io.kroxylicious.kafka.common.record.internal.RecordBatch;
import io.kroxylicious.kafka.common.record.internal.Records;
import io.kroxylicious.proxy.filter.FilterContext;
import io.kroxylicious.proxy.filter.RequestFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilter;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

public class S3ClaimCheckFilter implements RequestFilter, ResponseFilter {

    private static final Logger LOG = LoggerFactory.getLogger(S3ClaimCheckFilter.class);

    private final S3Service s3Service;
    private final S3ClaimCheckConfig config;

    public S3ClaimCheckFilter(S3ClaimCheckConfig config) {
        LOG.info("Initializing S3ClaimCheckFilter with config: bucketName={}, region={}, thresholdBytes={}, roleArn={}",
                config.bucketName(), config.region(), config.thresholdBytes(), config.roleArn());
        try {
            this.config = config;
            this.s3Service = new S3Service(config);
            LOG.info("S3ClaimCheckFilter initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to initialize S3ClaimCheckFilter", e);
            throw new RuntimeException("Failed to initialize S3ClaimCheckFilter", e);
        }
    }

    @Override
    public CompletionStage<RequestFilterResult> onRequest(
            ApiKeys apiKey,
            short apiVersion,
            RequestHeaderData header,
            ApiMessage request,
            FilterContext context) {

        LOG.debug("Received request: apiKey={}, correlationId={}", apiKey, header.correlationId());

        if (apiKey == ApiKeys.PRODUCE) {
            return handleProduceRequest(header, (ProduceRequestData) request, context);
        }

        return context.forwardRequest(header, request);
    }

    private CompletionStage<RequestFilterResult> handleProduceRequest(
            RequestHeaderData header,
            ProduceRequestData request,
            FilterContext context) {

        LOG.debug("Handling produce request with {} topics", request.topicData().size());

        for (var topicData : request.topicData()) {
            String topic = topicData.name();
            LOG.debug("Processing topic: {}, partitions: {}", topic, topicData.partitionData().size());

            for (var partitionData : topicData.partitionData()) {
                Records records = (Records) partitionData.records();
                if (records == null) {
                    LOG.debug("No records in partition {}", partitionData.index());
                    continue;
                }

                MemoryRecordsBuilder builder = MemoryRecords.builder(
                        ByteBuffer.allocate(1024 * 1024),
                        Compression.NONE,
                        TimestampType.CREATE_TIME,
                        0L
                );

                boolean modified = false;
                int recordCount = 0;

                for (RecordBatch batch : records.batches()) {
                    for (Record record : batch) {
                        recordCount++;
                        byte[] value = getBytes(record.value());
                        int valueSize = value != null ? value.length : 0;

                        LOG.debug("Record {}: size={} bytes, threshold={} bytes",
                                recordCount, valueSize, config.thresholdBytes());

                        if (value != null && value.length > config.thresholdBytes()) {
                            LOG.info("Uploading large message ({} bytes) to S3 for topic: {}", value.length, topic);
                            String s3Url = s3Service.upload(value, topic);

                            LOG.info("Uploaded large message ({} bytes) to S3: {}",
                                    value.length, s3Url);

                            builder.append(
                                    record.timestamp(),
                                    getBytes(record.key()),
                                    s3Url.getBytes(StandardCharsets.UTF_8)
                            );
                            modified = true;
                        } else {
                            builder.append(
                                    record.timestamp(),
                                    getBytes(record.key()),
                                    value
                            );
                        }
                    }
                }

                if (modified) {
                    partitionData.setRecords(builder.build());
                }
            }
        }

        return context.forwardRequest(header, request);
    }

    @Override
    public CompletionStage<ResponseFilterResult> onResponse(
            ApiKeys apiKey,
            short apiVersion,
            ResponseHeaderData header,
            ApiMessage response,
            FilterContext context) {

        LOG.debug("Received response: apiKey={}, correlationId={}", apiKey, header.correlationId());

        if (apiKey == ApiKeys.FETCH) {
            return handleFetchResponse(header, (FetchResponseData) response, context);
        }

        return context.forwardResponse(header, response);
    }

    private CompletionStage<ResponseFilterResult> handleFetchResponse(
            ResponseHeaderData header,
            FetchResponseData response,
            FilterContext context) {

        LOG.debug("Handling fetch response with {} topics", response.responses().size());

        try {
            for (var topicResponse : response.responses()) {
                LOG.debug("Processing fetch response for topic: {}, partitions: {}",
                        topicResponse.topic(), topicResponse.partitions().size());

                for (var partitionResponse : topicResponse.partitions()) {
                    Records records = (Records) partitionResponse.records();
                    if (records == null) {
                        LOG.debug("No records in partition response");
                        continue;
                    }

                    boolean modified = false;
                    int recordCount = 0;
                    MemoryRecordsBuilder builder = null;

                    for (RecordBatch batch : records.batches()) {
                        if (builder == null) {
                            builder = MemoryRecords.builder(
                                    ByteBuffer.allocate(10 * 1024 * 1024),
                                    batch.magic(),
                                    Compression.of(batch.compressionType()).build(),
                                    batch.timestampType(),
                                    batch.baseOffset()
                            );
                        }

                        for (Record record : batch) {
                            recordCount++;
                            byte[] value = getBytes(record.value());

                            if (value != null && isS3Reference(value)) {
                                String s3Url = new String(value, StandardCharsets.UTF_8);

                                LOG.info("Downloading message from S3: {}", s3Url);
                                byte[] actualValue = s3Service.download(s3Url);
                                LOG.info("Downloaded {} bytes from S3", actualValue.length);

                                builder.append(
                                        record.timestamp(),
                                        getBytes(record.key()),
                                        actualValue
                                );
                                modified = true;
                            } else {
                                builder.append(
                                        record.timestamp(),
                                        getBytes(record.key()),
                                        value
                                );
                            }
                        }
                    }

                    LOG.debug("Processed {} records, modified={}", recordCount, modified);

                    if (modified && builder != null) {
                        partitionResponse.setRecords(builder.build());
                        LOG.info("Replaced records in partition response");
                    }
                }
            }

            LOG.debug("Forwarding fetch response");
            return context.forwardResponse(header, response);
        } catch (Exception e) {
            LOG.error("Error handling fetch response", e);
            return context.forwardResponse(header, response);
        }
    }

    private boolean isS3Reference(byte[] value) {
        if (value == null || value.length < 5) {
            return false;
        }
        String str = new String(value, StandardCharsets.UTF_8);
        return str.startsWith("s3://") || str.startsWith("https://");
    }

    private byte[] getBytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }
}
