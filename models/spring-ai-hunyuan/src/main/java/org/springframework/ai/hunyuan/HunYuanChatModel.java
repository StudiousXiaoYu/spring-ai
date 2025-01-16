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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.hunyuan.api.HunYuanApi;
import org.springframework.ai.hunyuan.api.HunYuanApi.*;
import org.springframework.ai.hunyuan.api.HunYuanApi.ChatCompletionMessage.*;
import org.springframework.ai.hunyuan.api.HunYuanApi.ChatCompletion.*;
import org.springframework.ai.hunyuan.metadata.HunYuanUsage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.AbstractToolCallSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.model.function.FunctionCallingOptions;

import org.springframework.ai.hunyuan.api.HunYuanConstants;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * HunyuanChatModel is a {@link ChatModel} implementation that uses the Hunyuan
 *
 * @author Geng Rong
 * @author Alexandros Pappas
 */
public class HunYuanChatModel extends AbstractToolCallSupport implements ChatModel, StreamingChatModel {

	private static final Logger logger = LoggerFactory.getLogger(HunYuanChatModel.class);

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	/**
	 * The default options used for the chat completion requests.
	 */
	private final HunYuanChatOptions defaultOptions;

	/**
	 * Low-level access to the Hunyuan API.
	 */
	private final HunYuanApi moonshotApi;

	private final RetryTemplate retryTemplate;

	/**
	 * Observation registry used for instrumentation.
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * Conventions to use for generating observations.
	 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	/**
	 * Initializes a new instance of the HunyuanChatModel.
	 * @param moonshotApi The Hunyuan instance to be used for interacting with the
	 * Hunyuan Chat API.
	 */
	public HunYuanChatModel(HunYuanApi moonshotApi) {
		this(moonshotApi, HunYuanChatOptions.builder().model(HunYuanApi.DEFAULT_CHAT_MODEL).build());
	}

	/**
	 * Initializes a new instance of the HunyuanChatModel.
	 * @param moonshotApi The Hunyuan instance to be used for interacting with the
	 * Hunyuan Chat API.
	 * @param options The HunyuanChatOptions to configure the chat client.
	 */
	public HunYuanChatModel(HunYuanApi moonshotApi, HunYuanChatOptions options) {
		this(moonshotApi, options, null, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Initializes a new instance of the HunyuanChatModel.
	 * @param moonshotApi The Hunyuan instance to be used for interacting with the
	 * Hunyuan Chat API.
	 * @param options The HunyuanChatOptions to configure the chat client.
	 * @param functionCallbackResolver The function callback resolver to resolve the
	 * function by its name.
	 * @param retryTemplate The retry template.
	 */
	public HunYuanChatModel(HunYuanApi moonshotApi, HunYuanChatOptions options,
							FunctionCallbackResolver functionCallbackResolver, RetryTemplate retryTemplate) {
		this(moonshotApi, options, functionCallbackResolver, List.of(), retryTemplate, ObservationRegistry.NOOP);
	}

	/**
	 * Initializes a new instance of the HunyuanChatModel.
	 * @param moonshotApi The Hunyuan instance to be used for interacting with the
	 * Hunyuan Chat API.
	 * @param options The HunyuanChatOptions to configure the chat client.
	 * @param functionCallbackResolver resolves the function by its name.
	 * @param toolFunctionCallbacks The tool function callbacks.
	 * @param retryTemplate The retry template.
	 * @param observationRegistry The ObservationRegistry used for instrumentation.
	 */
	public HunYuanChatModel(HunYuanApi moonshotApi, HunYuanChatOptions options,
							FunctionCallbackResolver functionCallbackResolver, List<FunctionCallback> toolFunctionCallbacks,
							RetryTemplate retryTemplate, ObservationRegistry observationRegistry) {
		super(functionCallbackResolver, options, toolFunctionCallbacks);
		Assert.notNull(moonshotApi, "HunyuanApi must not be null");
		Assert.notNull(options, "Options must not be null");
		Assert.notNull(retryTemplate, "RetryTemplate must not be null");
		Assert.isTrue(CollectionUtils.isEmpty(options.getFunctionCallbacks()),
				"The default function callbacks must be set via the toolFunctionCallbacks constructor parameter");
		Assert.notNull(observationRegistry, "ObservationRegistry must not be null");
		this.moonshotApi = moonshotApi;
		this.defaultOptions = options;
		this.retryTemplate = retryTemplate;
		this.observationRegistry = observationRegistry;
	}

	private static Generation buildGeneration(Choice choice, Map<String, Object> metadata) {
		List<AssistantMessage.ToolCall> toolCalls = choice.message().toolCalls() == null ? List.of()
				: choice.message()
					.toolCalls()
					.stream()
					.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(), "function",
							toolCall.function().name(), toolCall.function().arguments()))
					.toList();

		var assistantMessage = new AssistantMessage(choice.message().content(), metadata, toolCalls);
		String finishReason = (choice.finishReason() != null ? choice.finishReason().name() : "");
		var generationMetadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
		return new Generation(assistantMessage, generationMetadata);
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		ChatCompletionRequest request = createRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(HunYuanConstants.PROVIDER_NAME)
			.requestOptions(buildRequestOptions(request))
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ResponseEntity<ChatCompletionResponse> completionEntity = this.retryTemplate
					.execute(ctx -> this.moonshotApi.chatCompletionEntity(request));

				var chatCompletion = completionEntity.getBody().response();

				if (chatCompletion == null) {
					logger.warn("No chat completion returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Choice> choices = chatCompletion.choices();
				if (choices == null) {
					logger.warn("No choices returned for prompt: {}", prompt);
					return new ChatResponse(List.of());
				}

				List<Generation> generations = choices.stream().map(choice -> {
			// @formatter:off
					Map<String, Object> metadata = Map.of(
							"id", chatCompletion.id(),
							"role", choice.message().role() != null ? choice.message().role().name() : "",
							"finishReason", choice.finishReason() != null ? choice.finishReason().name() : ""
					);
					// @formatter:on
					return buildGeneration(choice, metadata);
				}).toList();

				ChatResponse chatResponse = new ChatResponse(generations, from(request,completionEntity.getBody().response()));

				observationContext.setResponse(chatResponse);

				return chatResponse;
			});

		if (!isProxyToolCalls(prompt, this.defaultOptions)
				&& isToolCall(response, Set.of(HunYuanApi.ChatCompletionFinishReason.TOOL_CALLS.name(),
						HunYuanApi.ChatCompletionFinishReason.STOP.name()))) {
			var toolCallConversation = handleToolCalls(prompt, response);
			// Recursively call the call method with the tool call message
			// conversation that contains the call responses.
			return this.call(new Prompt(toolCallConversation, prompt.getOptions()));
		}
		return response;
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return this.defaultOptions.copy();
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.deferContextual(contextView -> {
			ChatCompletionRequest request = createRequest(prompt, true);

			Flux<ChatCompletionChunk> completionChunks = this.retryTemplate
				.execute(ctx -> this.moonshotApi.chatCompletionStream(request));

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(HunYuanConstants.PROVIDER_NAME)
				.requestOptions(buildRequestOptions(request))
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);

			observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

			// Convert the ChatCompletionChunk into a ChatCompletion to be able to reuse
			// the function call handling logic.
			Flux<ChatResponse> chatResponse = completionChunks.map(this::chunkToChatCompletion)
				.switchMap(chatCompletion -> Mono.just(chatCompletion).map(chatCompletion2 -> {
					try {
						String id = chatCompletion2.id();

						List<Generation> generations = chatCompletion2.choices().stream().map(choice -> {
							if (choice.message().role() != null) {
								roleMap.putIfAbsent(id, choice.message().role().name());
							}

				// @formatter:off
							Map<String, Object> metadata = Map.of(
								"id", chatCompletion2.id(),
								"role", roleMap.getOrDefault(id, ""),
								"finishReason", choice.finishReason() != null ? choice.finishReason().name() : ""
							);
							// @formatter:on
							return buildGeneration(choice, metadata);
						}).toList();

						return new ChatResponse(generations, from(request, chatCompletion2));
					}
					catch (Exception e) {
						logger.error("Error processing chat completion", e);
						return new ChatResponse(List.of());
					}

				}));

			Flux<ChatResponse> flux = chatResponse.flatMap(response -> {
				if (!isProxyToolCalls(prompt, this.defaultOptions) && isToolCall(response,
						Set.of(ChatCompletionFinishReason.TOOL_CALLS.name(), ChatCompletionFinishReason.STOP.name()))) {
					var toolCallConversation = handleToolCalls(prompt, response);
					// Recursively call the stream method with the tool call message
					// conversation that contains the call responses.
					return this.stream(new Prompt(toolCallConversation, prompt.getOptions()));
				}
				return Flux.just(response);
			})
				.doOnError(observation::error)
				.doFinally(signalType -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

			return new MessageAggregator().aggregate(flux, observationContext::setResponse);
		});
	}

	private ChatResponseMetadata from(ChatCompletionRequest request, ChatCompletion result) {
		Assert.notNull(result, "Hunyuan ChatCompletionResult must not be null");
		return ChatResponseMetadata.builder()
			.id(result.id() != null ? result.id() : "")
			.usage(result.usage() != null ? HunYuanUsage.from(result.usage()) : new EmptyUsage())
			.model(request.model() != null ? request.model() : "")
			.keyValue("created", result.created() != null ? result.created() : 0L)
			.build();
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private ChatCompletion chunkToChatCompletion(ChatCompletionChunk chunk) {
		List<ChatCompletion.Choice> choices = chunk.choices().stream().map(cc -> {
			ChatCompletionMessage delta = cc.delta();
			if (delta == null) {
				delta = new ChatCompletionMessage("", ChatCompletionMessage.Role.assistant);
			}
			return new ChatCompletion.Choice(cc.index(), delta, cc.finishReason(),null);
		}).toList();

		return new ChatCompletion(chunk.id(), null, chunk.created(), chunk.model(), choices, null, null, null, null, null, null);
	}

	/**
	 * Accessible for testing.
	 */
	public HunYuanApi.ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
				Object content = message.getText();
				return List.of(new ChatCompletionMessage(content,
						ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
						return new ToolCall(toolCall.id(), toolCall.type(),null, function);
					}).toList();
				}
				return List.of(new ChatCompletionMessage(assistantMessage.getText(),
						ChatCompletionMessage.Role.assistant, null, null, toolCalls));
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;

				toolMessage.getResponses()
					.forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));

				return toolMessage.getResponses()
					.stream()
					.map(tr -> new ChatCompletionMessage(tr.responseData(), ChatCompletionMessage.Role.tool, null,
							tr.id(), null))
					.toList();
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
			}
		}).flatMap(List::stream).toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

		Set<String> enabledToolsToUse = new HashSet<>();

		if (prompt.getOptions() != null) {
			HunYuanChatOptions updatedRuntimeOptions;

			if (prompt.getOptions() instanceof FunctionCallingOptions functionCallingOptions) {
				updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(functionCallingOptions,
						FunctionCallingOptions.class, HunYuanChatOptions.class);
			}
			else {
				updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
						HunYuanChatOptions.class);
			}
			enabledToolsToUse.addAll(this.runtimeFunctionCallbackConfigurations(updatedRuntimeOptions));

			request = ModelOptionsUtils.merge(updatedRuntimeOptions, request, ChatCompletionRequest.class);
		}

		if (!CollectionUtils.isEmpty(this.defaultOptions.getFunctions())) {
			enabledToolsToUse.addAll(this.defaultOptions.getFunctions());
		}

		request = ModelOptionsUtils.merge(request, this.defaultOptions, ChatCompletionRequest.class);

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(enabledToolsToUse)) {

			request = ModelOptionsUtils.merge(
					HunYuanChatOptions.builder().tools(this.getFunctionTools(enabledToolsToUse)).build(), request,
					ChatCompletionRequest.class);
		}

		return request;
	}

	private ChatOptions buildRequestOptions(HunYuanApi.ChatCompletionRequest request) {
		return ChatOptions.builder()
			.model(request.model())
//			.frequencyPenalty(request.frequencyPenalty())
//			.maxTokens(request.maxTokens())
//			.presencePenalty(request.presencePenalty())
			.stopSequences(request.stop())
			.temperature(request.temperature())
			.topP(request.topP())
			.build();
	}

	private List<FunctionTool> getFunctionTools(Set<String> functionNames) {
		return this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			var function = new FunctionTool.Function(functionCallback.getDescription(), functionCallback.getName(),
					functionCallback.getInputTypeSchema());
			return new FunctionTool(function);
		}).toList();
	}

	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		this.observationConvention = observationConvention;
	}

}
