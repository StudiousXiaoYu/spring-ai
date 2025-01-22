package org.springframework.ai.hunyuan.api.auth;

import org.springframework.ai.hunyuan.api.HunYuanConstants;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.MultiValueMap;

import java.io.IOException;

/**
 * <p>类的作用说明</p>
 *
 * @version 1.0
 * @since 2025/01/22 14:05:19
 */
public class HunYuanAuthenticationInterceptor  implements ClientHttpRequestInterceptor {

	private final HunYuanAuthApi hunyuanAuthApi;

	public HunYuanAuthenticationInterceptor(String secretId, String secretKey) {
		this.hunyuanAuthApi = new HunYuanAuthApi(secretId, secretKey);
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
		HttpHeaders headers = request.getHeaders();
		String service = HunYuanConstants.DEFAULT_SERVICE;
		String host = HunYuanConstants.DEFAULT_CHAT_HOST;
		// String region = "ap-guangzhou";
		String action = HunYuanConstants.DEFAULT_CHAT_ACTION;
		MultiValueMap<String, String> jsonContentHeaders = hunyuanAuthApi.getHttpHeadersConsumer(host, action, service,body);
		headers.addAll(jsonContentHeaders);
		return execution.execute(request, body);
	}
}
