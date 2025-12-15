package org.example.presentation.dto

import kotlinx.serialization.Serializable

/**
 * Запрос на получение списка инструментов MCP
 */
@Serializable
data class McpListToolsRequest(
    val mcpServerUrl: String
)

