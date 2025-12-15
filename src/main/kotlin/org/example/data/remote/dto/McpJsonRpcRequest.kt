package org.example.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * JSON-RPC 2.0 запрос для MCP протокола
 */
@Serializable
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject? = null
) {
    companion object {
        /**
         * Создает запрос с пустым объектом params вместо null
         */
        fun create(method: String, id: Int, params: JsonObject? = null): McpJsonRpcRequest {
            return McpJsonRpcRequest(
                jsonrpc = "2.0",
                id = id,
                method = method,
                params = params ?: JsonObject(emptyMap())
            )
        }
    }
}

