package org.example.data.transport

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory

/**
 * HTTP транспорт для MCP-клиента
 */
class HttpMcpTransport(
    private val serverUrl: String
) : McpTransport {
    private val logger = LoggerFactory.getLogger(HttpMcpTransport::class.java)
    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }

        engine {
            requestTimeout = 90000 // 90 секунд
        }
    }

    private var sessionId: String? = null
    private var isClosed = false

    override suspend fun sendRequest(requestBody: JsonObject): Result<JsonObject> {
        if (isClosed) {
            return Result.failure(Exception("Транспорт закрыт"))
        }

        return try {
            // JsonObject уже является JsonElement, используем toString() для преобразования в JSON строку
            val requestBodyJson = requestBody.toString()
            logger.debug("HTTP запрос к MCP-серверу: url=$serverUrl")
            logger.debug("Body запроса [MCP]:\n$requestBodyJson")

            val httpResponse = client.post(serverUrl) {
                header(HttpHeaders.ContentType, ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                sessionId?.let {
                    header("Mcp-Session-Id", it)
                }
                setBody(requestBodyJson)
            }

            logger.debug("HTTP Status: ${httpResponse.status.value} ${httpResponse.status.description}")

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
                return Result.failure(Exception("HTTP $statusCode: $errorMessage"))
            }

            // Проверяем Content-Type ответа
            val contentType = httpResponse.contentType()?.toString() ?: ""
            val responseBody = httpResponse.bodyAsText()
            logger.debug("Body ответа [MCP], Content-Type: $contentType\n$responseBody")

            // MCP-сервер может возвращать ответ в формате Server-Sent Events (SSE)
            // или как обычный JSON
            val jsonString = when {
                contentType.contains("text/event-stream") || responseBody.startsWith("event:") || responseBody.contains(
                    "data:"
                ) -> {
                    // Парсим SSE формат
                    val lines = responseBody.lines()
                    val dataLines = lines.filter { it.startsWith("data:") }
                    if (dataLines.isNotEmpty()) {
                        dataLines.last().substringAfter("data: ").trim()
                    } else {
                        responseBody
                    }
                }

                contentType.contains("application/json") -> {
                    responseBody
                }

                else -> {
                    if (responseBody.startsWith("{") || responseBody.startsWith("[")) {
                        responseBody
                    } else {
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

    override fun close() {
        if (!isClosed) {
            client.close()
            isClosed = true
            logger.debug("HTTP транспорт закрыт")
        }
    }

    override fun isOpen(): Boolean = !isClosed
}

