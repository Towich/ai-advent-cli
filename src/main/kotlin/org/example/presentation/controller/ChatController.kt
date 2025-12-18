package org.example.presentation.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.application.ChatWithToolsService
import org.example.domain.model.ChatRequest
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.domain.usecase.SendMultiChatMessageUseCase
import org.example.domain.usecase.SendChatMessageWithToolsUseCase
import org.example.presentation.dto.ChatApiRequest
import org.example.presentation.dto.ChatApiResponse
import org.example.presentation.dto.ChatWithToolsApiRequest
import org.example.presentation.dto.ChatWithToolsApiResponse
import org.example.presentation.dto.ErrorResponse
import org.example.presentation.dto.MultiChatApiRequest
import org.example.presentation.dto.MultiChatApiResponse
import org.example.presentation.dto.ModelResponse
import org.example.presentation.middleware.ErrorHandler
import org.example.presentation.validation.RequestValidator
import org.slf4j.LoggerFactory

/**
 * Контроллер для обработки запросов чата
 */
class ChatController(
    private val sendChatMessageUseCase: SendChatMessageUseCase,
    private val sendMultiChatMessageUseCase: SendMultiChatMessageUseCase,
    private val sendChatMessageWithToolsUseCase: SendChatMessageWithToolsUseCase
) {
    private val logger = LoggerFactory.getLogger(ChatController::class.java)
    private val chatWithToolsService = ChatWithToolsService(sendChatMessageWithToolsUseCase)
    fun configureRoutes(routing: Routing) {
        routing {
            post("/api/chat") {
                handleChatRequest(call)
            }
            post("/api/chat/multi") {
                handleMultiChatRequest(call)
            }
            post("/api/chat/tools") {
                handleChatWithToolsRequest(call)
            }
        }
    }
    
    private suspend fun handleChatRequest(call: ApplicationCall) {
        try {
            val request = call.receive<ChatApiRequest>()
            
            // Логируем запрос от юзера
            logger.info("Запрос от юзера: vendor=${request.vendor}, model=${request.model ?: "default"}, messageLength=${request.message.length}, maxRounds=${request.maxRounds ?: 1}")
            
            // Валидация
            RequestValidator.validate(request).getOrElse { error ->
                logger.warn("Ошибка валидации запроса: ${error.message}")
                val (statusCode, errorResponse) = ErrorHandler.handleError(error)
                call.respond(statusCode, errorResponse)
                return
            }
            
            // Конвертируем DTO в domain модель
            val chatRequest = ChatRequest(
                message = request.message,
                vendor = request.vendor,
                model = request.model,
                maxTokens = request.maxTokens,
                disableSearch = request.disableSearch,
                systemPrompt = request.systemPrompt,
                outputFormat = request.outputFormat,
                outputSchema = request.outputSchema,
                maxRounds = request.maxRounds,
                temperature = request.temperature,
                compressionMessagesThreshold = request.compressionMessagesThreshold,
                compressionTokensThreshold = request.compressionTokensThreshold
            )
            
            // Выполняем use case
            val result = sendChatMessageUseCase.execute(chatRequest)
            
            result.fold(
                onSuccess = { chatResult ->
                    // Логируем ответ юзеру
                    val usageInfo = chatResult.usage?.let { 
                        "promptTokens=${it.promptTokens}, completionTokens=${it.completionTokens}, totalTokens=${it.totalTokens}, cost=$${it.cost?.let { String.format("%.6f", it) } ?: "N/A"}"
                    } ?: "N/A"
                    logger.info("Ответ юзеру: модель=${chatResult.model}, длина=${chatResult.content.length}, round=${chatResult.round}/${chatResult.maxRounds}, executionTime=${chatResult.executionTimeMs}ms, $usageInfo${chatResult.wasCompressed?.let { ", была компрессия истории" } ?: ""}")
                    
                    val usage = chatResult.usage?.let {
                        org.example.presentation.dto.Usage(
                            prompt_tokens = it.promptTokens,
                            completion_tokens = it.completionTokens,
                            totalTokens = it.totalTokens,
                            cost = it.cost
                        )
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        ChatApiResponse(
                            content = chatResult.content,
                            model = chatResult.model,
                            isComplete = chatResult.isComplete,
                            round = chatResult.round,
                            maxRounds = chatResult.maxRounds,
                            executionTimeMs = chatResult.executionTimeMs,
                            usage = usage,
                            totalCharactersCount = chatResult.totalCharactersCount,
                            wasCompressed = chatResult.wasCompressed
                        )
                    )
                },
                onFailure = { error ->
                    logger.error("Ошибка при обработке запроса: ${error.message}", error)
                    val (statusCode, errorResponse) = ErrorHandler.handleError(error)
                    call.respond(statusCode, errorResponse)
                }
            )
        } catch (e: Exception) {
            logger.error("Необработанное исключение при обработке запроса: ${e.message}", e)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Ошибка при обработке запроса: ${e.message}",
                    code = "INVALID_REQUEST"
                )
            )
        }
    }

    private suspend fun handleMultiChatRequest(call: ApplicationCall) {
        try {
            val request = call.receive<MultiChatApiRequest>()

            // Логируем запрос от юзера
            val modelsInfo = request.models.joinToString(", ") { "${it.vendor}:${it.model}" }
            logger.info("Запрос от юзера [multi]: models=[$modelsInfo], messageLength=${request.message.length}")

            // Валидация базовых полей
            if (request.message.isBlank()) {
                logger.warn("Ошибка валидации: сообщение пустое")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "Сообщение не может быть пустым",
                        code = "INVALID_REQUEST"
                    )
                )
                return
            }

            if (request.models.isEmpty()) {
                logger.warn("Ошибка валидации: не указаны модели")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "Необходимо указать хотя бы одну модель",
                        code = "INVALID_REQUEST"
                    )
                )
                return
            }

            // Конвертируем модели в список пар (vendor, model)
            val models = request.models.map { it.vendor to it.model }

            // Измеряем общее время выполнения
            val startTime = System.currentTimeMillis()

            // Выполняем use case
            val results = sendMultiChatMessageUseCase.execute(
                message = request.message,
                models = models,
                maxTokens = request.maxTokens,
                disableSearch = request.disableSearch,
                systemPrompt = request.systemPrompt,
                outputFormat = request.outputFormat,
                outputSchema = request.outputSchema,
                temperature = request.temperature
            )

            val totalExecutionTimeMs = System.currentTimeMillis() - startTime

            // Конвертируем результаты в DTO
            val modelResponses = results.map { result ->
                val usage = result.usage?.let {
                    org.example.presentation.dto.Usage(
                        prompt_tokens = it.promptTokens,
                        completion_tokens = it.completionTokens,
                        totalTokens = it.totalTokens,
                        cost = it.cost
                    )
                }

                ModelResponse(
                    vendor = result.vendor,
                    model = result.model,
                    content = result.content ?: "",
                    executionTimeMs = result.executionTimeMs,
                    usage = usage,
                    success = result.success,
                    error = result.error
                )
            }

            // Объединяем ответы
            val combinedContent = combineResponses(modelResponses)

            // Суммируем использование токенов
            val totalUsage = calculateTotalUsage(modelResponses)

            // Логируем ответ юзеру
            val successCount = modelResponses.count { it.success }
            val totalUsageInfo = totalUsage?.let { 
                "promptTokens=${it.prompt_tokens}, completionTokens=${it.completion_tokens}, totalTokens=${it.totalTokens}, cost=$${it.cost?.let { String.format("%.6f", it) } ?: "N/A"}"
            } ?: "N/A"
            logger.info("Ответ юзеру [multi]: успешно=$successCount/${modelResponses.size}, totalExecutionTime=${totalExecutionTimeMs}ms, $totalUsageInfo")

            call.respond(
                HttpStatusCode.OK,
                MultiChatApiResponse(
                    combinedContent = combinedContent,
                    responses = modelResponses,
                    totalExecutionTimeMs = totalExecutionTimeMs,
                    totalUsage = totalUsage
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Ошибка валидации [multi]: ${e.message}", e)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Ошибка валидации: ${e.message}",
                    code = "VALIDATION_ERROR"
                )
            )
        } catch (e: Exception) {
            logger.error("Необработанное исключение при обработке запроса [multi]: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = "Ошибка при обработке запроса: ${e.message}",
                    code = "INTERNAL_ERROR"
                )
            )
        }
    }

    /**
     * Объединяет ответы от всех моделей в один текст
     */
    private fun combineResponses(responses: List<ModelResponse>): String {
        val successfulResponses = responses.filter { it.success && it.content.isNotBlank() }
        
        if (successfulResponses.isEmpty()) {
            return "Все запросы завершились с ошибками"
        }

        return successfulResponses.joinToString("\n\n---\n\n") { response ->
            buildString {
                append("**${response.vendor} (${response.model}):**\n\n")
                append(response.content)
            }
        }
    }

    /**
     * Суммирует использование токенов от всех моделей
     */
    private fun calculateTotalUsage(responses: List<ModelResponse>): org.example.presentation.dto.Usage? {
        val successfulUsages = responses
            .filter { it.success && it.usage != null }
            .mapNotNull { it.usage }

        if (successfulUsages.isEmpty()) {
            return null
        }

        val totalPromptTokens = successfulUsages.sumOf { it.prompt_tokens ?: 0 }
        val totalCompletionTokens = successfulUsages.sumOf { it.completion_tokens ?: 0 }
        val totalTokens = successfulUsages.sumOf { it.totalTokens ?: 0 }
        val totalCost = successfulUsages.sumOf { it.cost ?: 0.0 }

        return org.example.presentation.dto.Usage(
            prompt_tokens = totalPromptTokens.takeIf { it > 0 },
            completion_tokens = totalCompletionTokens.takeIf { it > 0 },
            totalTokens = totalTokens.takeIf { it > 0 },
            cost = totalCost.takeIf { it > 0.0 }
        )
    }

    private suspend fun handleChatWithToolsRequest(call: ApplicationCall) {
        try {
            val request = call.receive<ChatWithToolsApiRequest>()

            // Логируем запрос от юзера
            logger.info("Запрос от юзера [tools]: vendor=${request.vendor}, model=${request.model ?: "default"}, messageLength=${request.message.length}, mcpServerUrls=${request.mcpServerUrls.joinToString(", ")}, maxToolIterations=${request.maxToolIterations ?: 10}")

            val result = chatWithToolsService.execute(
                ChatWithToolsService.Command(
                    message = request.message,
                    vendor = request.vendor,
                    model = request.model,
                    maxTokens = request.maxTokens,
                    disableSearch = request.disableSearch,
                    systemPrompt = request.systemPrompt,
                    outputFormat = request.outputFormat,
                    outputSchema = request.outputSchema,
                    temperature = request.temperature,
                    mcpServerUrls = request.mcpServerUrls,
                    maxToolIterations = request.maxToolIterations
                )
            )

            result.fold(
                onSuccess = { chatResult ->
                    // Логируем ответ юзеру
                    val usageInfo = chatResult.usage?.let {
                        "promptTokens=${it.promptTokens}, completionTokens=${it.completionTokens}, totalTokens=${it.totalTokens}, cost=$${it.cost?.let { String.format("%.6f", it) } ?: "N/A"}"
                    } ?: "N/A"
                    logger.info("Ответ юзеру [tools]: модель=${chatResult.model}, длина=${chatResult.content.length}, toolIterations=${chatResult.totalToolIterations}, toolCalls=${chatResult.toolCalls.size}, executionTime=${chatResult.executionTimeMs}ms, $usageInfo")

                    val usage = chatResult.usage?.let {
                        org.example.presentation.dto.Usage(
                            prompt_tokens = it.promptTokens,
                            completion_tokens = it.completionTokens,
                            totalTokens = it.totalTokens,
                            cost = it.cost
                        )
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        ChatWithToolsApiResponse(
                            content = chatResult.content,
                            model = chatResult.model,
                            executionTimeMs = chatResult.executionTimeMs,
                            usage = usage,
                            toolCalls = chatResult.toolCalls,
                            totalToolIterations = chatResult.totalToolIterations
                        )
                    )
                },
                onFailure = { error ->
                    if (error is IllegalArgumentException) {
                        logger.warn("Ошибка валидации [tools]: ${error.message}")
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                error = error.message ?: "Ошибка валидации",
                                code = "INVALID_REQUEST"
                            )
                        )
                        return@fold
                    }
                    logger.error("Ошибка при обработке запроса [tools]: ${error.message}", error)
                    val (statusCode, errorResponse) = ErrorHandler.handleError(error)
                    call.respond(statusCode, errorResponse)
                }
            )
        } catch (e: Exception) {
            logger.error("Необработанное исключение при обработке запроса [tools]: ${e.message}", e)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Ошибка при обработке запроса: ${e.message}",
                    code = "INVALID_REQUEST"
                )
            )
        }
    }
}


