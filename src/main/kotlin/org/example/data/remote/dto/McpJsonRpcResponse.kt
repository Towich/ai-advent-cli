package org.example.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * JSON-RPC 2.0 ответ от MCP сервера
 */
@Serializable
data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: McpToolsListResult? = null,
    val error: McpJsonRpcError? = null
)

/**
 * Результат запроса списка инструментов
 */
@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDto>
)

/**
 * DTO инструмента от MCP сервера
 */
@Serializable
data class McpToolDto(
    val name: String,
    val description: String? = null,
    val inputSchema: Map<String, kotlinx.serialization.json.JsonElement>? = null
)

/**
 * Ошибка JSON-RPC
 */
@Serializable
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null
)

