package org.example.dto

import kotlinx.serialization.Serializable

// Входящий запрос от клиента
@Serializable
data class ChatApiRequest(
    val message: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val disableSearch: Boolean? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val outputSchema: String? = null,
    val maxRounds: Int? = null,
    val sessionId: String? = null
)

// Ответ сервера клиенту
@Serializable
data class ChatApiResponse(
    val content: String,
    val model: String? = null,
    val isComplete: Boolean,
    val round: Int,
    val maxRounds: Int,
    val sessionId: String
)

// Формат ошибки
@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null
)

