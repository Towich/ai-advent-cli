package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа от Perplexity API
 */
@Serializable
data class PerplexityResponse(
    val choices: List<PerplexityChoice>,
    val model: String? = null
)

@Serializable
data class PerplexityChoice(
    val message: PerplexityMessage
)


