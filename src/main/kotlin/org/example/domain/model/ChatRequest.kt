package org.example.domain.model

/**
 * Domain модель запроса на отправку сообщения
 */
data class ChatRequest(
    val message: String,
    val model: String?,
    val maxTokens: Int?,
    val disableSearch: Boolean?,
    val systemPrompt: String?,
    val outputFormat: String?,
    val outputSchema: String?,
    val maxRounds: Int?
)


