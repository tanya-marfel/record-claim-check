/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck;

import com.fasterxml.jackson.annotation.JsonProperty;

public record S3ClaimCheckConfig(
        @JsonProperty(required = true) String bucketName,
        @JsonProperty(required = true) String region,
        @JsonProperty(defaultValue = "1048576") int thresholdBytes,
        @JsonProperty String roleArn,
        @JsonProperty String roleSessionName
) {
    public S3ClaimCheckConfig {
        if (thresholdBytes <= 0) {
            thresholdBytes = 1048576;
        }
    }
}
