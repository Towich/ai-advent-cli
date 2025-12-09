package org.example.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * DTO для запроса к GigaChat API
 */
@Serializable
data class GigaChatRequest(
    val model: String,
    val max_tokens: Int? = null,
    val messages: List<GigaChatMessage>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null
)

@Serializable
data class GigaChatMessage(
    val role: String,
    val content: String
)
