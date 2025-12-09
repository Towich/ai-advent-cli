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
    val executionTimeMs: Long? = null
)


