package org.example.presentation.middleware

import io.ktor.http.*
import org.example.domain.exception.DomainException
import org.example.presentation.dto.ErrorResponse

/**
 * Обработчик ошибок для преобразования доменных исключений в HTTP ответы
 */
object ErrorHandler {
    fun handleError(error: Throwable): Pair<HttpStatusCode, ErrorResponse> {
        return when (error) {
            is DomainException -> {
                val statusCode = when (error) {
                    is DomainException.EmptyMessageException,
                    is DomainException.InvalidMaxRoundsException -> HttpStatusCode.BadRequest
                    is DomainException.DialogCompletedException,
                    is DomainException.MaxRoundsExceededException -> HttpStatusCode.BadRequest
                }
                Pair(statusCode, ErrorResponse(error.message ?: "Ошибка домена", error.code))
            }
            else -> {
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
                Pair(statusCode, ErrorResponse(
                    error = error.message ?: "Неизвестная ошибка",
                    code = errorCode
                ))
            }
        }
    }
}

