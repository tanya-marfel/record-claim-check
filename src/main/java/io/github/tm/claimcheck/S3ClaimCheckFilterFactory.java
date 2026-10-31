/*
 * Copyright 2026 Tanya Marfel
 *
 * Licensed under the Apache Software License version 2.0, available at
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.tm.claimcheck;

import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.plugin.Plugin;

@Plugin(configType = S3ClaimCheckConfig.class)
public class S3ClaimCheckFilterFactory implements FilterFactory<S3ClaimCheckConfig, S3ClaimCheckConfig> {

    @Override
    public S3ClaimCheckConfig initialize(FilterFactoryContext context, S3ClaimCheckConfig config) {
        return config;
    }

    @Override
    public S3ClaimCheckFilter createFilter(FilterFactoryContext context, S3ClaimCheckConfig config) {
        return new S3ClaimCheckFilter(config);
    }
}
