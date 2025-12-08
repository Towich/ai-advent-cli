package org.example.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * DTO для запроса к Perplexity API
 */
@Serializable
data class PerplexityRequest(
    val model: String,
    val max_tokens: Int = 256,
    val disable_search: Boolean = true,
    val messages: List<PerplexityMessage>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null
)

@Serializable
data class PerplexityMessage(
    val role: String,
    val content: String
)


