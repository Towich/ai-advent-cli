package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от GigaChat API
 */
@Serializable
data class GigaChatResponse(
    val choices: List<GigaChatChoice>,
    val model: String? = null,
    val usage: GigaChatUsage? = null
)

@Serializable
data class GigaChatChoice(
    val message: GigaChatMessage
)

@Serializable
data class GigaChatUsage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)
