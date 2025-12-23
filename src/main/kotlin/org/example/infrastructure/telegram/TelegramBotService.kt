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
import kotlinx.coroutines.launch
import org.example.application.ChatWithToolsService
import org.example.data.service.IndexService
import org.example.data.service.RAGSearchService
import org.example.domain.usecase.IndexDocumentsUseCase
import org.example.infrastructure.config.VendorDetector
import org.example.presentation.dto.ToolCallInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Сервис для работы с Telegram-ботом
 */
class TelegramBotService(
    private val botToken: String,
    private val chatWithToolsService: ChatWithToolsService,
    private val defaultVendor: String = "perplexity",
    private val defaultModel: String? = null,
    private val defaultMaxTokens: Int? = null,
    private val defaultMcpServerUrls: List<String> = listOf("http://localhost:8002/mcp"),
    private val defaultMaxToolIterations: Int = 10,
    private val indexDocumentsUseCase: IndexDocumentsUseCase? = null,
    private val indexService: IndexService? = null,
    private val ragSearchService: RAGSearchService? = null,
    private val githubRepoUrl: String = "https://github.com/Towich/life"
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
    
    /**
     * Хранилище настроек пользователей (chatId -> настройки)
     */
    private data class UserSettings(
        var vendor: String,
        var model: String?,
        var maxTokens: Int?
    )
    
    private val userSettings = ConcurrentHashMap<Long, UserSettings>()
    
    /**
     * Получает настройки пользователя или создает дефолтные
     */
    private fun getUserSettings(chatId: Long): UserSettings {
        return userSettings.getOrPut(chatId) {
            UserSettings(
                vendor = defaultVendor,
                model = defaultModel,
                maxTokens = defaultMaxTokens
            )
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
        logger.info("TelegramBotService.sendMessage: chatId=$chatId, textLength=${text.length}, parseMode=$parseMode")
        return try {
            val url = URLBuilder("https://api.telegram.org")
                .appendPathSegments("bot$botToken", "sendMessage")
                .build()

            logger.debug("Отправка запроса в Telegram API: $url")
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

            logger.info("Ответ от Telegram API: ok=${resp.ok}, errorCode=${resp.errorCode}, description=${resp.description}")

            if (!resp.ok) {
                val error = IllegalStateException(
                    "Telegram sendMessage failed: ${resp.errorCode ?: "N/A"} ${resp.description ?: "unknown error"}"
                )
                logger.error("Ошибка отправки в Telegram: ${error.message}")
                Result.failure(error)
            } else {
                logger.info("✅ Сообщение успешно отправлено в Telegram")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            logger.error("Исключение при отправке сообщения в Telegram: ${e.message}", e)
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

                    // Получаем настройки пользователя
                    val settings = getUserSettings(chatId)

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
                            vendor = settings.vendor,
                            model = settings.model,
                            maxTokens = settings.maxTokens,
                            mcpServerUrls = defaultMcpServerUrls,
                            maxToolIterations = defaultMaxToolIterations,
                            onToolCall = onToolCall
                        )
                    )

                    result.fold(
                        onSuccess = { chatResult ->
                            logger.info("=== TelegramBotService: получен успешный результат ===")
                            // Отправляем финальный результат без Markdown форматирования
                            val finalMessage = buildString {
                                append("✅ Результат:\n\n")
                                append(chatResult.content)
                                if (chatResult.toolCalls.isNotEmpty()) {
                                    append("\n\n")
                                    append("Использовано инструментов: ${chatResult.toolCalls.size}")
                                }
                            }

                            logger.info("Отправка финального сообщения в Telegram (chatId: $chatId, длина: ${finalMessage.length})")
                            val sendResult = sendMessage(chatId, finalMessage, parseMode = null)
                            sendResult.fold(
                                onSuccess = {
                                    logger.info("✅ Сообщение успешно отправлено в Telegram")
                                },
                                onFailure = { error ->
                                    logger.error("❌ Ошибка при отправке сообщения в Telegram: ${error.message}", error)
                                }
                            )
                            Result.success("Запрос обработан успешно")
                        },
                        onFailure = { error ->
                            val errorMessage = "❌ Ошибка: ${error.message ?: "Неизвестная ошибка"}"
                            sendMessage(chatId, errorMessage)
                            Result.failure(error)
                        }
                    )
                }

                command == "/vendor" -> {
                    val vendorArg = args?.trim()?.lowercase()
                    if (vendorArg.isNullOrBlank()) {
                        // Показываем текущий вендор
                        val settings = getUserSettings(chatId)
                        sendMessage(chatId, "Текущий вендор: ${settings.vendor}")
                        Result.success("Текущий вендор показан")
                    } else {
                        // Меняем вендор
                        val vendor = VendorDetector.parseVendor(vendorArg)
                        if (vendor == null) {
                            val validVendors = "perplexity, gigachat, huggingface"
                            sendMessage(chatId, "❌ Неизвестный вендор: $vendorArg\n\nДоступные вендоры: $validVendors")
                            Result.failure(IllegalArgumentException("Неизвестный вендор: $vendorArg"))
                        } else {
                            val settings = getUserSettings(chatId)
                            settings.vendor = vendorArg
                            sendMessage(chatId, "✅ Вендор изменен на: ${settings.vendor}")
                            Result.success("Вендор изменен")
                        }
                    }
                }
                
                command == "/model" -> {
                    val modelArg = args?.trim()
                    if (modelArg.isNullOrBlank()) {
                        // Показываем текущую модель
                        val settings = getUserSettings(chatId)
                        val modelText = settings.model ?: "не установлена (используется по умолчанию)"
                        sendMessage(chatId, "Текущая модель: $modelText")
                        Result.success("Текущая модель показана")
                    } else {
                        // Меняем модель
                        val settings = getUserSettings(chatId)
                        settings.model = modelArg
                        sendMessage(chatId, "✅ Модель изменена на: ${settings.model}")
                        Result.success("Модель изменена")
                    }
                }
                
                command == "/maxtokens" -> {
                    val maxTokensArg = args?.trim()
                    if (maxTokensArg.isNullOrBlank()) {
                        // Показываем текущее ограничение токенов
                        val settings = getUserSettings(chatId)
                        val maxTokensText = settings.maxTokens?.toString() ?: "не установлено (используется по умолчанию)"
                        sendMessage(chatId, "Текущее ограничение токенов: $maxTokensText")
                        Result.success("Текущее ограничение токенов показано")
                    } else {
                        // Меняем ограничение токенов
                        val maxTokensValue = maxTokensArg.toIntOrNull()
                        if (maxTokensValue == null || maxTokensValue < 1) {
                            sendMessage(chatId, "❌ Неверное значение. Ограничение токенов должно быть положительным числом.")
                            Result.failure(IllegalArgumentException("Неверное значение maxTokens: $maxTokensArg"))
                        } else {
                            val settings = getUserSettings(chatId)
                            settings.maxTokens = maxTokensValue
                            sendMessage(chatId, "✅ Ограничение токенов изменено на: ${settings.maxTokens}")
                            Result.success("Ограничение токенов изменено")
                        }
                    }
                }

                command == "/index" -> {
                    if (indexDocumentsUseCase == null || indexService == null) {
                        sendMessage(chatId, "❌ Сервис индексации не настроен")
                        return Result.failure(IllegalStateException("Сервис индексации не настроен"))
                    }

                    // Запускаем индексацию в отдельной корутине
                    CoroutineScope(SupervisorJob()).launch {
                        try {
                            sendMessage(chatId, "⏳ Начинаю индексацию документов...\n\nЭто может занять некоторое время.")
                            
                            val repoPath = "repos/life"
                            val result = indexDocumentsUseCase.execute(githubRepoUrl, repoPath)
                            
                            result.fold(
                                onSuccess = { indexResult ->
                                    val message = buildString {
                                        append("✅ Индексация завершена успешно!\n\n")
                                        append("📄 Документов: ${indexResult.totalDocuments}\n")
                                        append("📝 Чанков: ${indexResult.totalChunks}\n")
                                        append("🤖 Модель: ${indexResult.model}\n")
                                        append("💾 Путь к индексу: ${indexResult.indexPath}")
                                    }
                                    sendMessage(chatId, message)
                                },
                                onFailure = { error ->
                                    sendMessage(chatId, "❌ Ошибка при индексации: ${error.message}")
                                }
                            )
                        } catch (e: Exception) {
                            logger.error("Ошибка при индексации: ${e.message}", e)
                            sendMessage(chatId, "❌ Произошла ошибка: ${e.message}")
                        }
                    }
                    
                    Result.success("Индексация запущена")
                }

                command == "/indexinfo" -> {
                    if (indexService == null) {
                        sendMessage(chatId, "❌ Сервис индексации не настроен")
                        return Result.failure(IllegalStateException("Сервис индексации не настроен"))
                    }

                    val info = indexService.getIndexInfo()
                    if (info == null) {
                        sendMessage(chatId, "ℹ️ Индекс еще не создан. Используйте /index для создания индекса.")
                    } else {
                        val message = buildString {
                            append("ℹ️ *Информация об индексе*\n\n")
                            append("📄 Документов: ${info.totalDocuments}\n")
                            append("📝 Чанков: ${info.totalChunks}\n")
                            append("🤖 Модель: ${info.model}\n")
                            append("📅 Создан: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(info.createdAt))}")
                        }
                        sendMessage(chatId, message, parseMode = "Markdown")
                    }
                    Result.success("Информация об индексе отправлена")
                }

                command.startsWith("/rag", ignoreCase = true) -> {
                    val question = args?.trim() ?: return Result.failure(
                        IllegalArgumentException("Команда /rag требует вопрос. Использование: /rag <ваш вопрос> [topK=5]")
                    )

                    logger.info("=== RAG запрос от пользователя $chatId ===")
                    logger.info("Вопрос: $question")

                    if (ragSearchService == null) {
                        logger.error("RAG сервис не настроен для пользователя $chatId")
                        sendMessage(chatId, "❌ Сервис RAG не настроен")
                        return Result.failure(IllegalStateException("Сервис RAG не настроен"))
                    }

                    // Парсим параметры (поддержка формата: /rag вопрос topK=10)
                    var topK = 5
                    var actualQuestion = question
                    val topKRegex = "\\s+topK=(\\d+)\\s*$".toRegex()
                    val topKMatch = topKRegex.find(question)
                    if (topKMatch != null) {
                        topKMatch.groupValues.getOrNull(1)?.toIntOrNull()?.let {
                            topK = it.coerceIn(1, 20) // Ограничение от 1 до 20
                            logger.info("Параметр topK установлен: $topK")
                        }
                        // Убираем параметр из вопроса
                        actualQuestion = question.replace(topKRegex, "").trim()
                        logger.debug("Вопрос после удаления параметра: $actualQuestion")
                    }

                    // Отправляем сообщение о начале обработки
                    logger.info("Отправка уведомления о начале поиска документов")
                    sendMessage(chatId, "🔍 Ищу релевантные документы (topK=$topK)...")

                    // Ищем релевантные чанки
                    logger.info("Запуск поиска релевантных чанков (topK=$topK)")
                    val searchResult = ragSearchService.searchRelevantChunks(actualQuestion, topK = topK)
                    
                    searchResult.fold(
                        onSuccess = { result ->
                            logger.info("Поиск завершен успешно. Найдено чанков: ${result.chunks.size}")
                            
                            if (result.chunks.isEmpty()) {
                                logger.warn("Релевантные документы не найдены для вопроса: $actualQuestion")
                                sendMessage(chatId, "❌ Релевантные документы не найдены. Убедитесь, что индекс создан (/index).")
                                return@fold
                            }

                            logger.info("Форматирование контекста из ${result.chunks.size} найденных чанков")
                            
                            // Собираем список уникальных документов с количеством чанков
                            val documentsWithCounts = result.chunks
                                .groupBy { chunk ->
                                    // Извлекаем имя файла из пути
                                    val fileName = java.io.File(chunk.filePath).name
                                    // Убираем расширение .md для красоты
                                    fileName.removeSuffix(".md")
                                }
                                .map { (docName, chunks) ->
                                    docName to chunks.size
                                }
                                .sortedBy { it.first }
                            
                            // Отправляем сообщение со списком документов
                            if (documentsWithCounts.isNotEmpty()) {
                                val documentsMessage = buildString {
                                    append("📄 *Использованы документы:*\n\n")
                                    documentsWithCounts.forEachIndexed { index, (docName, chunkCount) ->
                                        val chunkText = if (chunkCount == 1) "фрагмент" else "фрагмента"
                                        append("${index + 1}. *$docName* ($chunkCount $chunkText)\n")
                                    }
                                    append("\n_Всего фрагментов: ${result.chunks.size}_")
                                }
                                logger.info("Отправка списка использованных документов (${documentsWithCounts.size} документов)")
                                sendMessage(chatId, documentsMessage, parseMode = "Markdown")
                            }
                            
                            // Форматируем контекст из найденных чанков
                            val context = ragSearchService.formatChunksAsContext(result)
                            logger.debug("Длина сформированного контекста: ${context.length} символов")
                            
                            // Формируем промпт с контекстом
                            val enhancedPrompt = buildString {
                                append(context)
                                append("\n\n---\n\n")
                                append("Вопрос пользователя: ")
                                append(actualQuestion)
                                append("\n\nОтветь на вопрос пользователя, используя информацию из предоставленных фрагментов документов. ")
                                append("Если информация в документах не содержит ответа на вопрос, скажи об этом честно.")
                            }

                            logger.info("Длина финального промпта: ${enhancedPrompt.length} символов")

                            // Получаем настройки пользователя
                            val settings = getUserSettings(chatId)
                            logger.info("Настройки пользователя: vendor=${settings.vendor}, model=${settings.model}, maxTokens=${settings.maxTokens}")

                            // Отправляем сообщение о начале генерации ответа
                            logger.info("Отправка уведомления о начале генерации ответа")
                            sendMessage(chatId, "🤖 Генерирую ответ на основе найденных документов...")

                            // Создаем колбэк для уведомлений о тулзах в реальном времени
                            val onToolCall: suspend (ToolCallInfo) -> Unit = { toolCall ->
                                logger.info("Колбэк onToolCall вызван для тула: ${toolCall.toolName}")
                                try {
                                    sendToolCallNotification(chatId, toolCall)
                                } catch (e: Exception) {
                                    logger.error("Ошибка при отправке уведомления о туле в колбэке: ${e.message}", e)
                                }
                            }

                            // Выполняем запрос к LLM с контекстом
                            logger.info("Выполнение запроса к LLM через ChatWithToolsService")
                            val llmResult = chatWithToolsService.execute(
                                ChatWithToolsService.Command(
                                    message = enhancedPrompt,
                                    vendor = settings.vendor,
                                    model = settings.model,
                                    maxTokens = settings.maxTokens,
                                    mcpServerUrls = defaultMcpServerUrls,
                                    maxToolIterations = defaultMaxToolIterations,
                                    onToolCall = onToolCall
                                )
                            )

                            llmResult.fold(
                                onSuccess = { chatResult ->
                                    logger.info("=== TelegramBotService RAG: получен успешный результат от LLM ===")
                                    logger.info("Длина ответа: ${chatResult.content.length} символов")
                                    logger.info("Использовано инструментов: ${chatResult.toolCalls.size}")
                                    
                                    // Отправляем финальный результат
                                    val finalMessage = buildString {
                                        append("✅ Ответ на основе документов:\n\n")
                                        append(chatResult.content)
                                        append("\n\n")
                                        append("📚 Использовано фрагментов: ${result.chunks.size}")
                                    }

                                    logger.info("Отправка финального сообщения в Telegram (chatId: $chatId, длина: ${finalMessage.length})")
                                    val sendResult = sendMessage(chatId, finalMessage, parseMode = null)
                                    sendResult.fold(
                                        onSuccess = {
                                            logger.info("✅ Сообщение успешно отправлено в Telegram")
                                        },
                                        onFailure = { error ->
                                            logger.error("❌ Ошибка при отправке сообщения в Telegram: ${error.message}", error)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    logger.error("Ошибка при генерации ответа от LLM: ${error.message}", error)
                                    logger.error("Тип ошибки: ${error.javaClass.simpleName}")
                                    val errorMessage = "❌ Ошибка при генерации ответа: ${error.message ?: "Неизвестная ошибка"}"
                                    sendMessage(chatId, errorMessage)
                                }
                            )
                        },
                        onFailure = { error ->
                            logger.error("=== Ошибка при поиске документов ===")
                            logger.error("Тип ошибки: ${error.javaClass.simpleName}")
                            logger.error("Сообщение ошибки: ${error.message}")
                            logger.error("Стек трейс:", error)
                            val errorMessage = "❌ Ошибка при поиске документов: ${error.message ?: "Неизвестная ошибка"}"
                            sendMessage(chatId, errorMessage)
                        }
                    )
                    
                    Result.success("RAG запрос обработан")
                }

                command == "/start" || command == "/help" -> {
                    val helpText = """
                        🤖 *AI Chat Bot с поддержкой инструментов*
                        
                        *Команды:*
                        /chat <сообщение> - Отправить запрос AI с использованием инструментов
                        /rag <вопрос> [topK=5] - Поиск релевантных документов и ответ на основе индекса (RAG)
                        /vendor - Показать текущий вендор
                        /vendor <название> - Изменить вендор (perplexity, gigachat, huggingface)
                        /model - Показать текущую модель
                        /model <название> - Изменить модель
                        /maxtokens - Показать текущее ограничение токенов
                        /maxtokens <число> - Изменить ограничение токенов
                        /index - Индексировать документы из GitHub репозитория
                        /indexinfo - Показать информацию об индексе
                        /help - Показать эту справку
                        
                        *Примеры:*
                        /chat Какая погода в Москве?
                        /rag Что я планировал на четвертый квартал?
                        /rag Какие проекты связаны с AI? topK=10
                        /vendor gigachat
                        /model GigaChat-2
                        /maxtokens 512
                        /index
                        
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

