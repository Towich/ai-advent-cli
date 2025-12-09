package org.example.presentation.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.domain.model.ChatRequest
import org.example.domain.usecase.SendChatMessageUseCase
import org.example.presentation.dto.ChatApiRequest
import org.example.presentation.dto.ChatApiResponse
import org.example.presentation.dto.ErrorResponse
import org.example.presentation.middleware.ErrorHandler
import org.example.presentation.validation.RequestValidator

/**
 * Контроллер для обработки запросов чата
 */
class ChatController(
    private val sendChatMessageUseCase: SendChatMessageUseCase
) {
    fun configureRoutes(routing: Routing) {
        routing {
            post("/api/chat") {
                handleChatRequest(call)
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
                model = request.model,
                maxTokens = request.maxTokens,
                disableSearch = request.disableSearch,
                systemPrompt = request.systemPrompt,
                outputFormat = request.outputFormat,
                outputSchema = request.outputSchema,
                maxRounds = request.maxRounds,
                temperature = request.temperature
            )
            
            // Выполняем use case
            val result = sendChatMessageUseCase.execute(chatRequest)
            
            result.fold(
                onSuccess = { chatResult ->
                    call.respond(
                        HttpStatusCode.OK,
                        ChatApiResponse(
                            content = chatResult.content,
                            model = chatResult.model,
                            isComplete = chatResult.isComplete,
                            round = chatResult.round,
                            maxRounds = chatResult.maxRounds
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
}


