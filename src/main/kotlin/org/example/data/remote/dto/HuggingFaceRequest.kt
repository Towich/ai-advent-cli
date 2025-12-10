package org.example.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * DTO для запроса к Hugging Face API
 */
@Serializable
data class HuggingFaceRequest(
    val model: String,
    val messages: List<HuggingFaceMessage>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val max_tokens: Int? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null
)

@Serializable
data class HuggingFaceMessage(
    val role: String,
    val content: String
)
