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

package org.springframework.ai.hunyuan;

import org.springframework.ai.hunyuan.api.HunYuanApi;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * @author Geng Rong
 */
@SpringBootConfiguration
public class HunYuanTestConfiguration {

	@Bean
	public HunYuanApi moonshotApi() {
		var apiKey = System.getenv("MOONSHOT_API_KEY");
		if (!StringUtils.hasText(apiKey)) {
			throw new IllegalArgumentException(
					"Missing MOONSHOT_API_KEY environment variable. Please set it to your Moonshot API key.");
		}
		return new HunYuanApi(apiKey,apiKey);
	}

	@Bean
	public HunYuanChatModel moonshotChatModel(HunYuanApi moonshotApi) {
		return new HunYuanChatModel(moonshotApi);
	}

	public void tst() {
	}

}
