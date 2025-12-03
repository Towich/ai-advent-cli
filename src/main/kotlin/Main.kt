package org.example

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.example.dto.ChatApiRequest
import org.example.dto.ChatApiResponse
import org.example.dto.ErrorResponse

fun main() {
    embeddedServer(Netty, port = Config.serverPort, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        })
    }
    
    install(CallLogging)
    
    val perplexityService = PerplexityService()
    val sessionManager = SessionManager()
    
    routing {
        post("/api/perplexity/chat") {
            try {
                val request = call.receive<ChatApiRequest>()
                
                // Валидация
                if (request.message.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Поле 'message' не может быть пустым", "EMPTY_MESSAGE")
                    )
                    return@post
                }
                
                if (request.maxRounds != null && request.maxRounds < 1) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Поле 'maxRounds' должно быть >= 1", "INVALID_MAX_ROUNDS")
                    )
                    return@post
                }
                
                // Получить или создать сессию
                val session = if (request.sessionId != null) {
                    sessionManager.getSession(request.sessionId)
                        ?: run {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Сессия не найдена", "SESSION_NOT_FOUND")
                            )
                            return@post
                        }
                } else {
                    // Создаем новую сессию, если указан maxRounds
                    if (request.maxRounds != null && request.maxRounds > 1) {
                        sessionManager.createSession(request)
                    } else {
                        // Режим одного раунда (обратная совместимость)
                        null
                    }
                }
                
                // Если есть сессия, проверяем её состояние
                if (session != null) {
                    // Проверка, не завершен ли диалог
                    if (session.isComplete) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Диалог завершен", "DIALOG_COMPLETED")
                        )
                        return@post
                    }
                    
                    // Проверка лимита раундов
                    if (session.currentRound >= session.maxRounds) {
                        session.isComplete = true
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Превышен лимит раундов", "MAX_ROUNDS_EXCEEDED")
                        )
                        return@post
                    }
                }
                
                // Определить, является ли это последним раундом
                // currentRound - это количество завершенных раундов, следующий будет currentRound + 1
                val isLastRound = session?.let { (it.currentRound + 1) >= it.maxRounds } ?: false
                
                // Отправить сообщение
                val result = perplexityService.sendMessage(request, session, isLastRound)
                
                if (result.isSuccess) {
                    val (content, model) = result.getOrThrow()
                    
                    if (session != null) {
                        // Обновить сессию
                        session.addUserMessage(request.message)
                        session.addAssistantMessage(content)
                        session.incrementRound() // Увеличиваем раунд после успешного ответа
                        session.updateLastActivity()
                        
                        val isComplete = session.currentRound >= session.maxRounds
                        if (isComplete) {
                            session.isComplete = true
                        }
                        
                        // Вернуть ответ с информацией о сессии
                        call.respond(
                            HttpStatusCode.OK,
                            ChatApiResponse(
                                content = content,
                                model = model,
                                isComplete = isComplete,
                                round = session.currentRound, // Текущий раунд после инкремента
                                maxRounds = session.maxRounds,
                                sessionId = session.sessionId
                            )
                        )
                    } else {
                        // Режим одного раунда (обратная совместимость)
                        call.respond(
                            HttpStatusCode.OK,
                            ChatApiResponse(
                                content = content,
                                model = model,
                                isComplete = true,
                                round = 1,
                                maxRounds = 1,
                                sessionId = ""
                            )
                        )
                    }
                } else {
                    val error = result.exceptionOrNull() ?: Exception("Неизвестная ошибка")
                    val (errorCode, statusCode) = when {
                        error.message?.contains("401") == true -> 
                            Pair("UNAUTHORIZED", HttpStatusCode.Unauthorized)
                        error.message?.contains("429") == true -> 
                            Pair("RATE_LIMIT_EXCEEDED", HttpStatusCode.TooManyRequests)
                        error.message?.contains("500") == true -> 
                            Pair("PERPLEXITY_SERVER_ERROR", HttpStatusCode.BadGateway)
                        error.message?.contains("не является валидным JSON") == true -> 
                            Pair("INVALID_JSON_RESPONSE", HttpStatusCode.BadGateway)
                        else -> 
                            Pair("PERPLEXITY_API_ERROR", HttpStatusCode.BadGateway)
                    }
                    
                    call.respond(
                        statusCode,
                        ErrorResponse(
                            error = error.message ?: "Неизвестная ошибка",
                            code = errorCode
                        )
                    )
                }
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
    
    environment.monitor.subscribe(ApplicationStopped) {
        perplexityService.close()
        sessionManager.shutdown()
    }
}
