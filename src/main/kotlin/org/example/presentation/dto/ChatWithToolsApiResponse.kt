package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * DTO для ответа клиенту с информацией о вызовах тулзов
 */
@Serializable
data class ChatWithToolsApiResponse(
    val content: String,
    val model: String? = null,
    val executionTimeMs: Long? = null,
    val usage: Usage? = null,
    val toolCalls: List<ToolCallInfo>? = null,
    val totalToolIterations: Int = 0
)

/**
 * Информация о вызове тула
 */
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val arguments: Map<String, String>,
    val result: String? = null,
    val success: Boolean,
    val serverUrl: String? = null // URL MCP-сервера, на котором был вызван инструмент
)

