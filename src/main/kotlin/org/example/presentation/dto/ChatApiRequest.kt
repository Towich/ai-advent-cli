package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для входящего запроса от клиента
 */
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
    val temperature: Double? = null
)


