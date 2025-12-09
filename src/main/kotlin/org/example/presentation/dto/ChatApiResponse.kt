package org.example.presentation.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * DTO для ответа клиенту
 */
@Serializable
data class ChatApiResponse(
    val content: String,
    val model: String? = null,
    val isComplete: Boolean,
    val round: Int,
    val maxRounds: Int,
    val executionTimeMs: Long? = null,
    val usage: Usage? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val totalTokens: Int? = null,
    val cost: Double? = null
)


