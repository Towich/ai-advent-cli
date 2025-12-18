package org.example.infrastructure.telegram

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.example.application.ChatWithToolsService
import org.example.presentation.dto.ToolCallInfo
import org.slf4j.LoggerFactory

/**
 * Сервис для работы с Telegram-ботом
 */
class TelegramBotService(
    private val botToken: String,
    private val chatWithToolsService: ChatWithToolsService,
    private val defaultVendor: String = "perplexity",
    private val defaultModel: String? = null,
    private val defaultMcpServerUrls: List<String> = listOf("http://localhost:8002/mcp"),
    private val defaultMaxToolIterations: Int = 10
) {
    private val logger = LoggerFactory.getLogger(TelegramBotService::class.java)
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
    }

    @Serializable
    private data class TelegramResponse(
        val ok: Boolean,
        val result: JsonElement? = null,
        val description: String? = null,
        @SerialName("error_code") val errorCode: Int? = null
    )

    @Serializable
    data class Update(
        @SerialName("update_id") val updateId: Long,
        val message: Message? = null
    )

    @Serializable
    data class MessageEntity(
        val offset: Int,
        val length: Int,
        val type: String
    )

    @Serializable
    data class Message(
        @SerialName("message_id") val messageId: Long,
        val from: User? = null,
        val chat: Chat,
        val text: String? = null,
        val date: Long,
        @SerialName("edit_date") val editDate: Long? = null,
        val entities: List<MessageEntity>? = null
    )

    @Serializable
    data class User(
        val id: Long,
        @SerialName("is_bot") val isBot: Boolean,
        @SerialName("first_name") val firstName: String,
        @SerialName("last_name") val lastName: String? = null,
        @SerialName("username") val username: String? = null,
        @SerialName("language_code") val languageCode: String? = null,
        @SerialName("is_premium") val isPremium: Boolean? = null
    )

    @Serializable
    data class Chat(
        val id: Long,
        val type: String,
        @SerialName("first_name") val firstName: String? = null,
        @SerialName("last_name") val lastName: String? = null,
        @SerialName("username") val username: String? = null,
        @SerialName("title") val title: String? = null
    )

    /**
     * Список служебных тулзов, которые не нужно отправлять пользователю
     */
    private val ignoredTools = setOf(
        "tools/list",
        "initialize",
        "notifications/initialized"
    )

    /**
     * Отправляет текстовое сообщение в Telegram
     */
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        disableWebPreview: Boolean = true
    ): Result<Unit> {
        return try {
            val url = URLBuilder("https://api.telegram.org")
                .appendPathSegments("bot$botToken", "sendMessage")
                .build()

            val resp: TelegramResponse = httpClient.post(url) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("chat_id", chatId.toString())
                            append("text", text)
                            if (!parseMode.isNullOrBlank()) append("parse_mode", parseMode)
                            append("disable_web_page_preview", disableWebPreview.toString())
                        }
                    )
                )
            }.body<TelegramResponse>()

            if (!resp.ok) {
                Result.failure(
                    IllegalStateException(
                        "Telegram sendMessage failed: ${resp.errorCode ?: "N/A"} ${resp.description ?: "unknown error"}"
                    )
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error("Ошибка при отправке сообщения в Telegram: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Отправляет сообщение о выполнении тула
     */
    suspend fun sendToolCallNotification(chatId: Long, toolCall: ToolCallInfo) {
        logger.info("Получен колбэк для тула: ${toolCall.toolName}, success=${toolCall.success}")
        
        // Пропускаем служебные тулзы
        if (ignoredTools.any { toolCall.toolName.contains(it, ignoreCase = true) }) {
            logger.debug("Пропущен служебный тул: ${toolCall.toolName}")
            return
        }

        // Упрощенный формат сообщения с action
        val action = toolCall.arguments["action"] ?: ""
        val serverInfo = toolCall.serverUrl ?: defaultMcpServerUrls.firstOrNull() ?: "неизвестный сервер"
        val message = if (action.isNotEmpty()) {
            "🔧 Использую инструмент: ${toolCall.toolName} (action=$action)... (MCP=$serverInfo)"
        } else {
            "🔧 Использую инструмент: ${toolCall.toolName}... (MCP=$serverInfo)"
        }

        logger.info("Отправляю уведомление о туле ${toolCall.toolName} в чат $chatId")
        sendMessage(chatId, message, parseMode = null).fold(
            onSuccess = { 
                logger.info("✅ Уведомление о туле успешно отправлено: ${toolCall.toolName}")
            },
            onFailure = { error ->
                logger.error("❌ Не удалось отправить уведомление о туле ${toolCall.toolName}: ${error.message}", error)
            }
        )
    }

    /**
     * Обрабатывает команду от пользователя
     */
    suspend fun handleCommand(chatId: Long, command: String, args: String?): Result<String> {
        return try {
            when {
                command.startsWith("/chat", ignoreCase = true) -> {
                    val message = args?.trim() ?: return Result.failure(
                        IllegalArgumentException("Команда /chat требует сообщение. Использование: /chat <ваше сообщение>")
                    )

                    // Отправляем сообщение о начале обработки
                    sendMessage(chatId, "⏳ Обрабатываю запрос...")

                    // Создаем колбэк для уведомлений о тулзах в реальном времени
                    val onToolCall: suspend (ToolCallInfo) -> Unit = { toolCall ->
                        logger.info("Колбэк onToolCall вызван для тула: ${toolCall.toolName}")
                        try {
                            sendToolCallNotification(chatId, toolCall)
                        } catch (e: Exception) {
                            logger.error("Ошибка при отправке уведомления о туле в колбэке: ${e.message}", e)
                        }
                    }

                    // Выполняем запрос с отслеживанием тулзов
                    val result = chatWithToolsService.execute(
                        ChatWithToolsService.Command(
                            message = message,
                            vendor = defaultVendor,
                            model = defaultModel,
                            mcpServerUrls = defaultMcpServerUrls,
                            maxToolIterations = defaultMaxToolIterations,
                            onToolCall = onToolCall
                        )
                    )

                    result.fold(
                        onSuccess = { chatResult ->
                            // Отправляем финальный результат
                            val finalMessage = buildString {
                                append("✅ *Результат:*\n\n")
                                append(chatResult.content)
                                if (chatResult.toolCalls.isNotEmpty()) {
                                    append("\n\n")
                                    append("_Использовано инструментов: ${chatResult.toolCalls.size}_")
                                }
                            }

                            sendMessage(chatId, finalMessage, parseMode = "Markdown")
                            Result.success("Запрос обработан успешно")
                        },
                        onFailure = { error ->
                            val errorMessage = "❌ Ошибка: ${error.message ?: "Неизвестная ошибка"}"
                            sendMessage(chatId, errorMessage)
                            Result.failure(error)
                        }
                    )
                }

                command == "/start" || command == "/help" -> {
                    val helpText = """
                        🤖 *AI Chat Bot с поддержкой инструментов*
                        
                        *Команды:*
                        /chat <сообщение> - Отправить запрос AI с использованием инструментов
                        /help - Показать эту справку
                        
                        *Пример:*
                        /chat Какая погода в Москве?
                        
                        Бот будет автоматически использовать доступные инструменты для выполнения вашего запроса.
                    """.trimIndent()
                    sendMessage(chatId, helpText, parseMode = "Markdown")
                    Result.success("Справка отправлена")
                }

                else -> {
                    sendMessage(
                        chatId,
                        "❓ Неизвестная команда. Используйте /help для справки."
                    )
                    Result.failure(IllegalArgumentException("Неизвестная команда: $command"))
                }
            }
        } catch (e: Exception) {
            logger.error("Ошибка при обработке команды: ${e.message}", e)
            sendMessage(chatId, "❌ Произошла ошибка: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Обрабатывает обновление от Telegram
     */
    suspend fun handleUpdate(update: Update) {
        val message = update.message ?: return
        val chatId = message.chat.id
        val text = message.text ?: return

        logger.info("Получено сообщение от пользователя ${message.from?.id}: $text")

        // Парсим команду
        when {
            text.startsWith("/") -> {
                val parts = text.split(" ", limit = 2)
                val command = parts[0]
                val args = parts.getOrNull(1)
                handleCommand(chatId, command, args)
            }
            else -> {
                // Если сообщение не команда, обрабатываем как /chat
                handleCommand(chatId, "/chat", text)
            }
        }
    }

    /**
     * Получает обновления от Telegram API
     */
    suspend fun getUpdates(lastUpdateId: Long = 0): Result<List<Update>> {
        return try {
            val url = URLBuilder("https://api.telegram.org")
                .appendPathSegments("bot$botToken", "getUpdates")
                .apply {
                    parameters.append("offset", (lastUpdateId + 1).toString())
                    parameters.append("timeout", "30")
                }
                .build()

            val resp: TelegramResponse = httpClient.get(url).body<TelegramResponse>()

            if (!resp.ok) {
                Result.failure(
                    IllegalStateException(
                        "Telegram getUpdates failed: ${resp.errorCode ?: "N/A"} ${resp.description ?: "unknown error"}"
                    )
                )
            } else {
                val updates = try {
                    // В ответе getUpdates поле "result" содержит массив обновлений напрямую
                    when (val result = resp.result) {
                        is kotlinx.serialization.json.JsonArray -> {
                            // result уже является массивом обновлений
                            result.mapNotNull { element ->
                                try {
                                    jsonParser.decodeFromJsonElement(Update.serializer(), element)
                                } catch (e: Exception) {
                                    logger.warn("Не удалось распарсить обновление: ${e.message}")
                                    null
                                }
                            }
                        }
                        is kotlinx.serialization.json.JsonObject -> {
                            // Если result - объект, пытаемся получить массив из поля "result"
                            result["result"]?.let { resultArray ->
                                if (resultArray is kotlinx.serialization.json.JsonArray) {
                                    resultArray.mapNotNull { element ->
                                        try {
                                            jsonParser.decodeFromJsonElement(Update.serializer(), element)
                                        } catch (e: Exception) {
                                            logger.warn("Не удалось распарсить обновление: ${e.message}")
                                            null
                                        }
                                    }
                                } else {
                                    emptyList()
                                }
                            } ?: emptyList()
                        }
                        else -> {
                            logger.debug("Неожиданный тип result: ${result?.javaClass?.simpleName}")
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Ошибка при парсинге обновлений: ${e.message}", e)
                    emptyList()
                }

                Result.success(updates)
            }
        } catch (e: Exception) {
            logger.error("Ошибка при получении обновлений: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Запускает бота в режиме long polling
     */
    fun startLongPolling(scope: CoroutineScope) {
        scope.launch {
            var lastUpdateId = 0L

            logger.info("Telegram бот запущен в режиме long polling")

            while (isActive) {
                try {
                    val updatesResult = getUpdates(lastUpdateId)
                    updatesResult.fold(
                        onSuccess = { updates ->
                            updates.forEach { update ->
                                lastUpdateId = update.updateId
                                handleUpdate(update)
                            }
                        },
                        onFailure = { error ->
                            logger.error("Ошибка при получении обновлений: ${error.message}", error)
                            delay(5000) // Ждем 5 секунд перед повтором
                        }
                    )
                } catch (e: Exception) {
                    logger.error("Исключение в цикле long polling: ${e.message}", e)
                    delay(5000)
                }
            }
        }
    }

    fun close() {
        httpClient.close()
    }
}

