package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * Запрос на вызов инструмента MCP
 */
@Serializable
data class McpCallToolRequest(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap()
)
