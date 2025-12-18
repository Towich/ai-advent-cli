package org.example.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.data.remote.dto.McpJsonRpcRequest
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

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }

        engine {
            requestTimeout = 90000 // 90 секунд
        }
    }

    private var requestIdCounter = 1
    private var isInitialized = false
    private var sessionId: String? = null

    /**
     * Выполняет JSON-RPC запрос к MCP-серверу
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

            val requestBodyJson = jsonSerializer.encodeToString(request)
            logger.debug("Запрос к MCP-серверу: метод=$method, url=$serverUrl")
            logger.debug("Body запроса [MCP]:\n$requestBodyJson")

            // Формируем заголовки запроса
            val requestHeaders = mutableMapOf<String, String>()
            requestHeaders["Content-Type"] = "application/json"
            requestHeaders["Accept"] = "application/json, text/event-stream"
            sessionId?.let {
                requestHeaders["Mcp-Session-Id"] = it
            }

            logger.debug("Заголовки запроса [MCP]:")
            requestHeaders.forEach { (key, value) ->
                logger.debug("  $key: $value")
            }

            val httpResponse = client.post(serverUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                // Включаем Mcp-Session-Id, если он был получен при инициализации
                sessionId?.let {
                    header("Mcp-Session-Id", it)
                }
                setBody(requestBodyJson)
            }

            // Логируем заголовки ответа
            logger.debug("Заголовки ответа [MCP]:")
            logger.debug("  HTTP Status: ${httpResponse.status.value} ${httpResponse.status.description}")
            httpResponse.headers.forEach { name, values ->
                values.forEach { value ->
                    logger.debug("  $name: $value")
                }
            }

            // Сохраняем Mcp-Session-Id из заголовков ответа (если есть)
            val receivedSessionId = httpResponse.headers["Mcp-Session-Id"]
            if (receivedSessionId != null && sessionId == null) {
                sessionId = receivedSessionId
                logger.debug("Получен Mcp-Session-Id: $sessionId")
            }

            val statusCode = httpResponse.status.value
            if (statusCode !in 200..299) {
                val errorBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    "Не удалось прочитать тело ответа: ${e.message}"
                }
                val errorMessage = "Ошибка при запросе к MCP-серверу (HTTP $statusCode): $errorBody"
                logger.error("Ошибка ответа от MCP-сервера: HTTP $statusCode - $errorMessage")

                // Если получили 404 с session ID, значит сессия истекла - нужно переинициализировать
                if (statusCode == 404 && sessionId != null) {
                    logger.warn("Сессия истекла (HTTP 404), сбрасываем состояние инициализации")
                    isInitialized = false
                    sessionId = null
                }

                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }

            // Проверяем Content-Type ответа
            val contentType = httpResponse.contentType()?.toString() ?: ""
            val responseBody = httpResponse.bodyAsText()
            logger.debug("Body ответа [MCP], Content-Type: $contentType\n$responseBody")

            // MCP-сервер может возвращать ответ в формате Server-Sent Events (SSE)
            // или как обычный JSON, в зависимости от Content-Type
            val jsonString = when {
                contentType.contains("text/event-stream") || responseBody.startsWith("event:") || responseBody.contains(
                    "data:"
                ) -> {
                    // Парсим SSE формат
                    val lines = responseBody.lines()
                    // Ищем все строки с data: и объединяем их (может быть несколько событий)
                    val dataLines = lines.filter { it.startsWith("data:") }
                    if (dataLines.isNotEmpty()) {
                        // Берем последнюю строку data: (обычно это финальный ответ)
                        // Или объединяем все, если нужно
                        dataLines.last().substringAfter("data: ").trim()
                    } else {
                        responseBody
                    }
                }

                contentType.contains("application/json") -> {
                    // Обычный JSON ответ
                    responseBody
                }

                else -> {
                    // Пытаемся определить формат по содержимому
                    if (responseBody.startsWith("{") || responseBody.startsWith("[")) {
                        responseBody
                    } else {
                        // Пытаемся извлечь JSON из SSE формата
                        val lines = responseBody.lines()
                        val dataLine = lines.find { it.startsWith("data:") }
                        dataLine?.substringAfter("data: ")?.trim() ?: responseBody
                    }
                }
            }

            // Парсим JSON-RPC ответ
            val jsonResponse = jsonSerializer.parseToJsonElement(jsonString).jsonObject

            // Проверяем наличие ошибки
            if (jsonResponse.containsKey("error")) {
                val error = jsonResponse["error"]?.jsonObject
                val errorCode = error?.get("code")?.jsonPrimitive?.intOrNull ?: -1
                val errorMessage =
                    error?.get("message")?.jsonPrimitive?.content ?: "Неизвестная ошибка"
                logger.error("Ошибка от MCP-сервера: code=$errorCode, message=$errorMessage")
                return Result.failure(Exception("MCP Error ($errorCode): $errorMessage"))
            }

            // Извлекаем результат
            val result = jsonResponse["result"]?.jsonObject
                ?: return Result.failure(Exception("Отсутствует поле 'result' в ответе MCP-сервера"))

            Result.success(result)
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("timeout") == true || e.message?.contains("Timeout") == true ->
                    "Таймаут при запросе к MCP-серверу. Возможно, сервер недоступен или перегружен"

                e.message?.contains("Connection") == true ->
                    "Не удалось подключиться к MCP-серверу. Проверьте URL: $serverUrl"

                else -> "Ошибка при запросе к MCP-серверу: ${e.message ?: e.javaClass.simpleName}"
            }
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
                        val notificationJson =
                            jsonSerializer.encodeToString(initializedNotification)

                        logger.info("Отправка уведомления initialized")
                        logger.debug("URL: $serverUrl")
                        logger.debug("Body запроса уведомления initialized:\n$notificationJson")

                        // Формируем заголовки запроса уведомления
                        val notificationRequestHeaders = mutableMapOf<String, String>()
                        notificationRequestHeaders["Content-Type"] = "application/json"
                        notificationRequestHeaders["Accept"] = "application/json, text/event-stream"
                        sessionId?.let {
                            notificationRequestHeaders["Mcp-Session-Id"] = it
                        }

                        logger.debug("Заголовки запроса уведомления initialized:")
                        notificationRequestHeaders.forEach { (key, value) ->
                            logger.debug("  $key: $value")
                        }

                        // Отправляем уведомление и ожидаем ответ
                        // Согласно спецификации MCP, уведомления должны возвращать HTTP 202 Accepted
                        // См. https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#sending-messages-to-the-server
                        try {
                            val notificationResponse = client.post(serverUrl) {
                                header(HttpHeaders.ContentType, ContentType.Application.Json)
                                header(HttpHeaders.Accept, "application/json, text/event-stream")
                                // Включаем Mcp-Session-Id, если он был получен
                                sessionId?.let {
                                    header("Mcp-Session-Id", it)
                                }
                                setBody(notificationJson)
                            }

                            // Логируем заголовки ответа на уведомление
                            logger.debug("Заголовки ответа на уведомление initialized:")
                            logger.debug("  HTTP Status: ${notificationResponse.status.value} ${notificationResponse.status.description}")
                            notificationResponse.headers.forEach { name, values ->
                                values.forEach { value ->
                                    logger.debug("  $name: $value")
                                }
                            }

                            val statusCode = notificationResponse.status.value
                            val responseBody = try {
                                notificationResponse.bodyAsText()
                            } catch (e: Exception) {
                                "Не удалось прочитать тело ответа: ${e.message}"
                            }

                            logger.info("Ответ на уведомление initialized: HTTP $statusCode")
                            logger.info("Тело ответа на уведомление initialized:\n$responseBody")

                            // Проверяем, что получили 202 Accepted (для уведомлений это ожидаемый статус)
                            if (statusCode == 202) {
                                logger.info("Уведомление initialized успешно принято (HTTP 202 Accepted)")
                            } else if (statusCode in 200..299) {
                                logger.info("Уведомление initialized принято со статусом: HTTP $statusCode")
                            } else {
                                logger.warn("Уведомление initialized получило неожиданный статус: HTTP $statusCode")
                            }
                        } catch (e: Exception) {
                            logger.error(
                                "Ошибка при отправке уведомления initialized: ${e.message}",
                                e
                            )
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
        client.close()
    }
}

