/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.autoconfigure.hunyuan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parent properties for HunYuan.
 *
 * @author Guo Junyu
 */
@ConfigurationProperties(HunYuanCommonProperties.CONFIG_PREFIX)
public class HunYuanCommonProperties extends HunYuanParentProperties {

	public static final String CONFIG_PREFIX = "spring.ai.hunyuan";

	public static final String DEFAULT_BASE_URL = "https://hunyuan.tencentcloudapi.com";

	public HunYuanCommonProperties() {
		super.setBaseUrl(DEFAULT_BASE_URL);
	}

}
