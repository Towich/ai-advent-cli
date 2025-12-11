package org.example.presentation.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.domain.model.ChatRequest
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.domain.usecase.SendMultiChatMessageUseCase
import org.example.presentation.dto.ChatApiRequest
import org.example.presentation.dto.ChatApiResponse
import org.example.presentation.dto.ErrorResponse
import org.example.presentation.dto.MultiChatApiRequest
import org.example.presentation.dto.MultiChatApiResponse
import org.example.presentation.dto.ModelResponse
import org.example.presentation.middleware.ErrorHandler
import org.example.presentation.validation.RequestValidator

/**
 * Контроллер для обработки запросов чата
 */
class ChatController(
    private val sendChatMessageUseCase: SendChatMessageUseCase,
    private val sendMultiChatMessageUseCase: SendMultiChatMessageUseCase
) {
    fun configureRoutes(routing: Routing) {
        routing {
            post("/api/chat") {
                handleChatRequest(call)
            }
            post("/api/chat/multi") {
                handleMultiChatRequest(call)
            }
        }
    }
    
    private suspend fun handleChatRequest(call: ApplicationCall) {
        try {
            val request = call.receive<ChatApiRequest>()
            
            // Валидация
            RequestValidator.validate(request).getOrElse { error ->
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
                compressionThreshold = request.compressionThreshold
            )
            
            // Выполняем use case
            val result = sendChatMessageUseCase.execute(chatRequest)
            
            result.fold(
                onSuccess = { chatResult ->
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
                    val (statusCode, errorResponse) = ErrorHandler.handleError(error)
                    call.respond(statusCode, errorResponse)
                }
            )
        } catch (e: Exception) {
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

            // Валидация базовых полей
            if (request.message.isBlank()) {
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
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = "Ошибка валидации: ${e.message}",
                    code = "VALIDATION_ERROR"
                )
            )
        } catch (e: Exception) {
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
}


