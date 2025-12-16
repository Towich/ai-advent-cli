package org.example.data.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.sink
import okio.source
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

/**
 * Обертка над MCP клиентом для работы с MCP сервером
 */
class McpClient(
    private val transportType: TransportType = TransportType.STDIO,
    private val serverUrl: String? = null
) {
    private val logger = LoggerFactory.getLogger(McpClient::class.java)
    
    private val client = Client(
        clientInfo = Implementation(
            name = "ai-advent-cli",
            version = "1.0.0"
        )
    )
    
    private var transport: Any? = null
    
    enum class TransportType {
        STDIO,
        SSE
    }
    
    /**
     * Подключиться к MCP серверу
     */
    suspend fun connect() {
        try {
            transport = when (transportType) {
                TransportType.STDIO -> {
                    // Используем стандартные потоки по умолчанию (System.in/out)
                    StdioClientTransport(
                        input = System.`in`.source(),
                        output = System.out.sink()
                    )
                }
                TransportType.SSE -> {
                    if (serverUrl == null) {
                        throw IllegalStateException("serverUrl обязателен для SSE транспорта")
                    }
                    val httpClient = HttpClient(CIO)
                    SseClientTransport(
                        urlString = serverUrl,
                        client = httpClient
                    )
                }
            }
            
            when (val t = transport) {
                is StdioClientTransport -> client.connect(t)
                is SseClientTransport -> client.connect(t)
                else -> throw IllegalStateException("Неподдерживаемый тип транспорта")
            }
            
            logger.info("MCP клиент успешно подключен (транспорт: $transportType)")
        } catch (e: Exception) {
            logger.error("Ошибка подключения MCP клиента: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Получить список доступных инструментов
     */
    suspend fun listTools(): List<Tool> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val result = client.listTools() as? List<Tool> ?: emptyList()
            result
        } catch (e: Exception) {
            logger.error("Ошибка получения списка инструментов: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Вызвать инструмент
     */
    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): Result<Any?> {
        return try {
            val jsonArgs = buildJsonObject {
                arguments.forEach { (key, value) ->
                    when (value) {
                        null -> {} // Пропускаем null значения
                        is String -> put(key, value)
                        is Number -> put(key, value)
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            }
            val request = CallToolRequest(
                name = name,
                arguments = jsonArgs
            )
            val result = client.callTool(request)
            Result.success(result)
        } catch (e: Exception) {
            logger.error("Ошибка вызова инструмента $name: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Отключиться от MCP сервера
     */
    suspend fun disconnect() {
        try {
            // Закрываем транспорт, если он есть
            when (val t = transport) {
                is StdioClientTransport -> {
                    // Stdio транспорт обычно не требует явного закрытия
                }
                is SseClientTransport -> {
                    // SSE транспорт может требовать закрытия HTTP клиента
                }
            }
            logger.info("MCP клиент отключен")
        } catch (e: Exception) {
            logger.error("Ошибка отключения MCP клиента: ${e.message}", e)
        }
    }
    
    /**
     * Закрыть клиент (синхронный метод для удобства)
     */
    fun close() {
        runBlocking {
            disconnect()
        }
    }
}
