package org.example.data.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.Tool
import okio.sink
import okio.source
import org.slf4j.LoggerFactory

/**
 * Сервис для работы с MCP (Model Context Protocol) сервером
 */
class McpClientService {
    private val logger = LoggerFactory.getLogger(McpClientService::class.java)
    private lateinit var client: Client
    private lateinit var transport: StdioClientTransport
    
    /**
     * Инициализация клиента с информацией о приложении
     */
    fun initialize() {
        client = Client(
            clientInfo = Implementation(
                name = "ai-advent-cli",
                version = "1.0.0"
            )
        )
        logger.info("MCP клиент инициализирован")
    }
    
    /**
     * Подключение к MCP-серверу через stdio
     * @param process - процесс MCP-сервера
     */
    suspend fun connect(process: java.lang.Process) {
        try {
            // StdioClientTransport принимает Source и Sink из okio
            transport = StdioClientTransport(
                input = process.inputStream.source(),
                output = process.outputStream.sink()
            )
            
            client.connect(transport)
            logger.info("Подключено к MCP-серверу через stdio")
        } catch (e: Exception) {
            logger.error("Ошибка при подключении к MCP-серверу: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Получение списка доступных инструментов
     * @return список инструментов или пустой список в случае ошибки
     */
    suspend fun listTools(): List<Tool> {
        return try {
            val response = client.listTools()
            val tools = response.tools ?: emptyList()
            logger.info("Получено инструментов от MCP-сервера: ${tools.size}")
            tools
        } catch (e: Exception) {
            logger.error("Ошибка при получении списка инструментов: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Закрытие соединения
     */
    suspend fun disconnect() {
        try {
            client.close()
            transport.close()
            logger.info("Соединение с MCP-сервером закрыто")
        } catch (e: Exception) {
            logger.error("Ошибка при закрытии соединения: ${e.message}", e)
        }
    }
}

