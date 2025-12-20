package org.example.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.data.remote.dto.McpJsonRpcRequest
import org.example.data.transport.McpTransport
import org.example.data.transport.McpTransportFactory
import org.example.domain.model.McpTool
import org.example.domain.repository.McpRepository
import org.slf4j.LoggerFactory

/**
 * Реализация репозитория для работы с MCP (Model Context Protocol) сервером
 */
class McpRepositoryImpl(
    private val serverUrl: String
) : McpRepository {
    private val logger = LoggerFactory.getLogger(McpRepositoryImpl::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true // Сериализуем значения по умолчанию (например, jsonrpc: "2.0")
    }

    private val transport: McpTransport = McpTransportFactory.create(serverUrl)

    private var requestIdCounter = 1
    private var isInitialized = false

    /**
     * Выполняет JSON-RPC запрос к MCP-серверу через транспорт
     */
    private suspend fun executeRequest(
        method: String,
        params: JsonObject? = null
    ): Result<JsonObject> {
        return try {
            val request = McpJsonRpcRequest.create(
                method = method,
                id = requestIdCounter++,
                params = params
            )

            // Конвертируем запрос в JsonObject для транспорта
            val requestBody = buildJsonObject {
                put("jsonrpc", request.jsonrpc)
                put("id", request.id)
                put("method", request.method)
                if (request.params != null && request.params.entries.isNotEmpty()) {
                    put("params", request.params)
                }
            }

            val requestBodyJson = jsonSerializer.encodeToString(requestBody)
            logger.debug("Запрос к MCP-серверу: метод=$method, config=$serverUrl")
            logger.debug("Body запроса [MCP]:\n$requestBodyJson")

            // Отправляем запрос через транспорт
            val result = transport.sendRequest(requestBody)

            result.fold(
                onSuccess = { resultObject ->
                    Result.success(resultObject)
                },
                onFailure = { error ->
                    logger.error("Ошибка от транспорта: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            val errorMessage = "Ошибка при запросе к MCP-серверу: ${e.message ?: e.javaClass.simpleName}"
            logger.error(
                "Ошибка запроса к MCP-серверу: ${e.javaClass.simpleName} - $errorMessage",
                e
            )
            Result.failure(Exception(errorMessage))
        }
    }

    override suspend fun initialize(): Result<Unit> {
        if (isInitialized) {
            return Result.success(Unit)
        }

        return try {
            // Создаем параметры инициализации согласно спецификации MCP 2025-03-26
            val initParams = buildJsonObject {
                put("protocolVersion", "2025-03-26")
                put("capabilities", buildJsonObject {
                    put("roots", buildJsonObject {
                        put("listChanged", false)
                    })
                    put("sampling", buildJsonObject {})
                })
                put("clientInfo", buildJsonObject {
                    put("name", "ai-advent-cli")
                    put("version", "1.0.0")
                })
            }

            logger.info("Инициализация MCP-сервера")
            val result = executeRequest("initialize", initParams)

            result.fold(
                onSuccess = {
                    // После успешной инициализации нужно отправить уведомление initialized
                    // согласно спецификации MCP: https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle
                    try {
                        val initializedNotification = McpJsonRpcRequest.create(
                            method = "notifications/initialized",
                            id = requestIdCounter++
                        )

                        // Конвертируем уведомление в JsonObject для транспорта
                        val notificationBody = buildJsonObject {
                            put("jsonrpc", initializedNotification.jsonrpc)
                            put("id", initializedNotification.id)
                            put("method", initializedNotification.method)
                        }

                        logger.info("Отправка уведомления initialized")
                        logger.debug("Config: $serverUrl")
                        val notificationJson = jsonSerializer.encodeToString(notificationBody)
                        logger.debug("Body запроса уведомления initialized:\n$notificationJson")

                        // Отправляем уведомление через транспорт
                        // Для уведомлений (notifications) не ожидаем ответа, но отправляем через транспорт
                        try {
                            val notificationResult = transport.sendRequest(notificationBody)
                            notificationResult.fold(
                                onSuccess = {
                                    logger.info("Уведомление initialized успешно отправлено")
                                },
                                onFailure = { error ->
                                    logger.warn("Ошибка при отправке уведомления initialized: ${error.message}")
                                    // Не критично, продолжаем работу
                                }
                            )
                        } catch (e: Exception) {
                            logger.warn("Исключение при отправке уведомления initialized: ${e.message}")
                            // Не критично, продолжаем работу
                        }

                        isInitialized = true
                        logger.info("MCP-сервер успешно инициализирован и уведомлен")
                        Result.success(Unit)
                    } catch (e: Exception) {
                        logger.warn("Не удалось отправить уведомление initialized: ${e.message}")
                        // Все равно помечаем как инициализированный, так как initialize прошел успешно
                        isInitialized = true
                        Result.success(Unit)
                    }
                },
                onFailure = { error ->
                    logger.error("Ошибка инициализации MCP-сервера: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            logger.error("Исключение при инициализации MCP-сервера: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun listTools(): Result<List<McpTool>> {
        // Инициализируем сервер, если еще не инициализирован
        if (!isInitialized) {
            val initResult = initialize()
            if (initResult.isFailure) {
                return Result.failure(
                    initResult.exceptionOrNull()
                        ?: Exception("Не удалось инициализировать MCP-сервер")
                )
            }
        }

        return try {
            logger.info("Запрос списка инструментов MCP")
            val result = executeRequest("tools/list")

            result.fold(
                onSuccess = { resultObject ->
                    val tools = resultObject["tools"]?.jsonArray
                        ?: return Result.failure(Exception("Отсутствует поле 'tools' в результате"))

                    // Конвертируем DTO в domain модели
                    val mcpTools = tools.map { toolElement ->
                        val toolObject = toolElement.jsonObject
                        val name = toolObject["name"]?.jsonPrimitive?.content
                            ?: return@map null
                        val description = toolObject["description"]?.jsonPrimitive?.content
                        val inputSchema = toolObject["inputSchema"]?.jsonObject?.let { schema ->
                            schema.entries.associate { it.key to it.value }
                        }

                        McpTool(
                            name = name,
                            description = description,
                            inputSchema = inputSchema,
                            serverUrl = serverUrl
                        )
                    }.filterNotNull()

                    logger.info("Получено инструментов от MCP-сервера: ${mcpTools.size}")
                    Result.success(mcpTools)
                },
                onFailure = { error ->
                    logger.error("Ошибка при получении списка инструментов: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            logger.error("Исключение при получении списка инструментов: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, String>
    ): Result<String> {
        // Инициализируем сервер, если еще не инициализирован
        if (!isInitialized) {
            val initResult = initialize()
            if (initResult.isFailure) {
                return Result.failure(
                    initResult.exceptionOrNull()
                        ?: Exception("Не удалось инициализировать MCP-сервер")
                )
            }
        }

        return try {
            logger.info("Вызов инструмента MCP: $toolName с аргументами: $arguments")

            // Создаем параметры для вызова инструмента
            val callParams = buildJsonObject {
                put("name", toolName)
                put("arguments", buildJsonObject {
                    arguments.forEach { (key, value) ->
                        put(key, value)
                    }
                })
            }

            val result = executeRequest("tools/call", callParams)

            result.fold(
                onSuccess = { resultObject ->
                    // Извлекаем результат выполнения инструмента
                    // Результат может быть в разных форматах, но обычно это объект с полем content
                    val content = resultObject["content"]?.let { contentElement ->
                        when {
                            contentElement is JsonPrimitive -> contentElement.content
                            contentElement is JsonArray -> {
                                // Если content - массив, ищем текстовое содержимое
                                contentElement.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                                    ?: contentElement.toString()
                            }

                            contentElement is JsonObject -> {
                                // Если content - объект, ищем поле text
                                contentElement["text"]?.jsonPrimitive?.content
                                    ?: contentElement.toString()
                            }

                            else -> contentElement.toString()
                        }
                    } ?: resultObject.toString()

                    logger.info(
                        "Инструмент $toolName выполнен успешно, результат: ${
                            content.take(
                                100
                            )
                        }..."
                    )
                    Result.success(content)
                },
                onFailure = { error ->
                    logger.error("Ошибка при вызове инструмента $toolName: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            logger.error("Исключение при вызове инструмента $toolName: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun close() {
        transport.close()
    }
}

