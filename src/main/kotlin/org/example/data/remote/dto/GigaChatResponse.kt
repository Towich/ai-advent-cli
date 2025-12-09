package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от GigaChat API
 */
@Serializable
data class GigaChatResponse(
    val choices: List<GigaChatChoice>,
    val model: String? = null
)

@Serializable
data class GigaChatChoice(
    val message: GigaChatMessage
)
