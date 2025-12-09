package org.example.domain.model

/**
 * Результат отправки сообщения
 */
data class ChatResult(
    val content: String,
    val model: String,
    val isComplete: Boolean,
    val round: Int,
    val maxRounds: Int,
    val executionTimeMs: Long? = null,
    val usage: TokenUsage? = null
)

/**
 * Информация об использовании токенов
 */
data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cost: Double? = null
)


