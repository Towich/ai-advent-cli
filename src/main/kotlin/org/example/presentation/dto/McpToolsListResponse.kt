package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * Ответ API со списком инструментов MCP
 */
@Serializable
data class McpToolsListResponse(
    val tools: List<McpToolResponse>,
    val count: Int
)

/**
 * Инструмент MCP для API ответа
 */
@Serializable
data class McpToolResponse(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, String>? = null
)


