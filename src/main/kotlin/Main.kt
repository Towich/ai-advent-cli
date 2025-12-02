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
    
    routing {
        post("/api/perplexity/chat") {
            try {
                val request = call.receive<ChatApiRequest>()
                
                if (request.message.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Поле 'message' не может быть пустым", "EMPTY_MESSAGE")
                    )
                    return@post
                }
                
                val result = perplexityService.sendMessage(request)
                
                if (result.isSuccess) {
                    val (response, model) = result.getOrThrow()
                    call.respond(
                        HttpStatusCode.OK,
                        ChatApiResponse(
                            response = response,
                            model = model
                        )
                    )
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
    }
}
