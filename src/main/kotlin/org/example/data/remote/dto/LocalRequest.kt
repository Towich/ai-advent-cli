package org.example.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * DTO для запроса к Local API
 */
@Serializable
data class LocalRequest(
    val model: String,
    val max_tokens: Int = 256,
    val messages: List<LocalMessage>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null
)

@Serializable
data class LocalMessage(
    val role: String,
    val content: String
)

