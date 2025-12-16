package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для входящего запроса от клиента с поддержкой MCP-тулзов
 */
@Serializable
data class ChatWithToolsApiRequest(
    val message: String,
    val vendor: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    val disableSearch: Boolean? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val outputSchema: String? = null,
    val temperature: Double? = null,
    val mcpServerUrl: String,
    val maxToolIterations: Int? = 10 // Максимальное количество итераций вызова тулзов
)

