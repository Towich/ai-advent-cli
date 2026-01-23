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
import kotlinx.serialization.json.jsonPrimitive
import org.example.application.ChatWithToolsService
import org.example.data.repository.McpRepositoryImpl
import org.example.domain.model.McpTool
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
    
    /**
     * Менеджер истории диалогов
     */
    private val chatHistoryManager = ChatHistoryManager()
    
    /**
     * Хранилище настроек пользователей для сохранения между перезапусками
     */
    private val settingsStorage = UserSettingsStorage()
    
    /**
     * Хранилище активных диалогов (chatId -> true/false)
     * true означает, что пользователь находится в режиме диалога
     */
    private val activeDialogs = ConcurrentHashMap<Long, Boolean>()
    
    /**
     * Хранилище настроек пользователей (chatId -> настройки)
     */
    private data class UserSettings(
        var vendor: String,
        var model: String?,
        var maxTokens: Int?,
        var temperature: Double?,
        var systemPrompt: String?,
        var includeToolsInSystemPrompt: Boolean = true // По умолчанию инструменты включены
    )
    
    private val userSettings = ConcurrentHashMap<Long, UserSettings>()
    
    /**
     * Список доступных вендоров
     */
    private val availableVendors = listOf(
        "perplexity",
        "gigachat",
        "huggingface",
        "local"
    )
    
    /**
     * Список доступных моделей по вендорам
     */
    private val availableModels = mapOf(
        "perplexity" to listOf(
            "sonar",
            "sonar-pro",
            "sonar-reasoning",
            "sonar-reasoning-pro",
            "sonar-deep-research"
        ),
        "gigachat" to listOf(
            "GigaChat-2",
            "GigaChat-Pro",
            "GigaChat-Max"
        ),
        "huggingface" to listOf(
            "meta-llama/Llama-3.1-8B-Instruct",
            "meta-llama/Llama-3.1-70B-Instruct",
            "mistralai/Mistral-7B-Instruct-v0.2",
            "mistralai/Mixtral-8x7B-Instruct-v0.1",
            "google/gemma-7b-it",
            "Qwen/Qwen2.5-7B-Instruct"
        ),
        "local" to listOf(
            "qwen2.5",
            "llama3.1",
            "mistral"
        )
    )
    
    init {
        // Загружаем сохраненные настройки пользователей при старте
        loadSavedSettings()
    }
    
    /**
     * Загружает сохраненные настройки пользователей из файла
     */
    private fun loadSavedSettings() {
        try {
            val savedSettings = settingsStorage.loadAllSettings()
            savedSettings.forEach { (chatId, settingsData) ->
                userSettings[chatId] = UserSettings(
                    vendor = settingsData.vendor,
                    model = settingsData.model,
                    maxTokens = settingsData.maxTokens,
                    temperature = settingsData.temperature,
                    systemPrompt = settingsData.systemPrompt,
                    includeToolsInSystemPrompt = settingsData.includeToolsInSystemPrompt ?: true
                )
            }
            logger.info("Загружены сохраненные настройки для ${savedSettings.size} пользователей")
        } catch (e: Exception) {
            logger.error("Ошибка при загрузке сохраненных настроек: ${e.message}", e)
        }
    }
    
    /**
     * Сохраняет настройки пользователя в файл
     */
    private fun saveUserSettings(chatId: Long, settings: UserSettings) {
        try {
            val settingsData = UserSettingsStorage.UserSettingsData(
                vendor = settings.vendor,
                model = settings.model,
                maxTokens = settings.maxTokens,
                temperature = settings.temperature,
                systemPrompt = settings.systemPrompt,
                includeToolsInSystemPrompt = settings.includeToolsInSystemPrompt
            )
            settingsStorage.saveUserSettings(chatId, settingsData)
        } catch (e: Exception) {
            logger.error("Ошибка при сохранении настроек пользователя: ${e.message}", e)
        }
    }
    
    /**
     * Получает настройки пользователя или создает дефолтные
     */
    private fun getUserSettings(chatId: Long): UserSettings {
        return userSettings.getOrPut(chatId) {
            // Сначала пытаемся загрузить из сохраненных настроек
            val savedSettings = settingsStorage.loadUserSettings(chatId)
            if (savedSettings != null) {
                UserSettings(
                    vendor = savedSettings.vendor,
                    model = savedSettings.model,
                    maxTokens = savedSettings.maxTokens,
                    temperature = savedSettings.temperature,
                    systemPrompt = savedSettings.systemPrompt,
                    includeToolsInSystemPrompt = savedSettings.includeToolsInSystemPrompt ?: true
                )
            } else {
                UserSettings(
                    vendor = defaultVendor,
                    model = defaultModel,
                    maxTokens = defaultMaxTokens,
                    temperature = null,
                    systemPrompt = null,
                    includeToolsInSystemPrompt = true
                )
            }
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
     * Извлекает список источников (названия файлов) из результатов инструмента search_documents
     */
    private fun extractSourcesFromToolCalls(toolCalls: List<ToolCallInfo>): List<String> {
        val sources = mutableSetOf<String>()
        
        // Ищем все вызовы инструмента search_documents
        val searchDocumentsCalls = toolCalls.filter { 
            it.toolName.contains("search_documents", ignoreCase = true) && it.success
        }
        
        if (searchDocumentsCalls.isEmpty()) {
            return emptyList()
        }
        
        // Парсим результаты каждого вызова
        searchDocumentsCalls.forEach { toolCall ->
            val result = toolCall.result ?: return@forEach
            
            try {
                // Пытаемся распарсить результат как JSON
                val jsonElement = jsonParser.parseToJsonElement(result)
                
                when {
                    // Если результат - массив объектов
                    jsonElement is kotlinx.serialization.json.JsonArray -> {
                        jsonElement.forEach { item ->
                            try {
                                val obj = item.jsonObject
                                extractSourceFromJsonObject(obj, sources)
                            } catch (e: Exception) {
                                logger.debug("Не удалось обработать элемент массива: ${e.message}")
                            }
                        }
                    }
                    // Если результат - объект
                    jsonElement is kotlinx.serialization.json.JsonObject -> {
                        // Проверяем, есть ли массив results
                        val resultsArray = jsonElement["results"]?.jsonArray
                        if (resultsArray != null) {
                            resultsArray.forEach { item ->
                                try {
                                    val obj = item.jsonObject
                                    extractSourceFromJsonObject(obj, sources)
                                } catch (e: Exception) {
                                    logger.debug("Не удалось обработать элемент results: ${e.message}")
                                }
                            }
                        } else {
                            // Пытаемся извлечь источник из самого объекта
                            extractSourceFromJsonObject(jsonElement, sources)
                        }
                    }
                }
            } catch (e: Exception) {
                // Если не удалось распарсить как JSON, пытаемся найти названия файлов в тексте
                logger.debug("Не удалось распарсить результат search_documents как JSON: ${e.message}")
                extractSourcesFromText(result, sources)
            }
        }
        
        return sources.sorted()
    }
    
    /**
     * Извлекает источник из JSON объекта
     */
    private fun extractSourceFromJsonObject(jsonObj: kotlinx.serialization.json.JsonObject, sources: MutableSet<String>) {
        // Проверяем различные возможные поля с названием файла
        val possibleFields = listOf("source", "file", "filename", "path", "filepath", "document", "name")
        
        possibleFields.forEach { fieldName ->
            try {
                jsonObj[fieldName]?.jsonPrimitive?.content?.let { source ->
                    if (source.isNotBlank()) {
                        sources.add(source)
                    }
                }
            } catch (e: Exception) {
                // Пропускаем, если поле не является примитивом
                logger.debug("Поле $fieldName не является примитивом: ${e.message}")
            }
        }
    }
    
    /**
     * Извлекает источники из текста (если результат не JSON)
     */
    private fun extractSourcesFromText(text: String, sources: MutableSet<String>) {
        // Ищем паттерны типа "file: filename.md" или "source: path/to/file.md"
        val patterns = listOf(
            Regex("""(?:file|source|filename|path|document)[:\s]+([^\s\n,]+\.(?:md|txt|pdf|docx?))""", RegexOption.IGNORE_CASE),
            Regex("""(?:from|in)\s+([^\s\n,]+\.(?:md|txt|pdf|docx?))""", RegexOption.IGNORE_CASE),
            Regex("""([^\s\n,]+\.(?:md|txt|pdf|docx?))""", RegexOption.IGNORE_CASE)
        )
        
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val source = match.groupValues.getOrNull(1) ?: match.value
                if (source.isNotBlank() && !source.startsWith("http")) {
                    sources.add(source.trim())
                }
            }
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

        // Упрощенный формат сообщения с action и query
        val action = toolCall.arguments["action"] ?: ""
        val query = toolCall.arguments["query"] ?: ""
        val serverInfo = toolCall.serverUrl ?: defaultMcpServerUrls.firstOrNull() ?: "неизвестный сервер"
        
        val message = buildString {
            append("🔧 Использую инструмент: ${toolCall.toolName}")
            if (action.isNotEmpty()) {
                append(" (action=$action)")
            }
            if (query.isNotEmpty()) {
                append(" (query=$query)")
            }
            append("... (MCP=$serverInfo)")
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

                    // Активируем режим диалога
                    activeDialogs[chatId] = true
                    
                    // Загружаем историю диалога (без последнего сообщения, если оно уже есть)
                    val historyMessages = chatHistoryManager.getMessages(chatId)
                    logger.info("Загружена история для chatId=$chatId: ${historyMessages.size} сообщений")
                    historyMessages.forEachIndexed { index, msg ->
                        logger.debug("История[$index]: role=${msg.role}, content=${msg.content.take(100)}...")
                    }
                    
                    // Сохраняем сообщение пользователя в историю
                    chatHistoryManager.addMessage(chatId, "user", message)
                    logger.info("Сообщение пользователя сохранено в историю")

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

                    // Выполняем запрос с отслеживанием тулзов и историей диалога
                    val result = chatWithToolsService.execute(
                        ChatWithToolsService.Command(
                            message = message,
                            vendor = settings.vendor,
                            model = settings.model,
                            maxTokens = settings.maxTokens,
                            temperature = settings.temperature,
                            systemPrompt = settings.systemPrompt,
                            includeToolsInSystemPrompt = settings.includeToolsInSystemPrompt,
                            mcpServerUrls = defaultMcpServerUrls,
                            maxToolIterations = defaultMaxToolIterations,
                            onToolCall = onToolCall,
                            historyMessages = historyMessages // Передаем историю диалога
                        )
                    )

                    result.fold(
                        onSuccess = { chatResult ->
                            logger.info("=== TelegramBotService: получен успешный результат ===")
                            
                            // Сохраняем ответ ассистента в историю с информацией о токенах
                            chatHistoryManager.addMessage(
                                chatId, 
                                "assistant", 
                                chatResult.content,
                                chatResult.usage
                            )
                            
                            // Извлекаем источники из результатов search_documents
                            val sources = extractSourcesFromToolCalls(chatResult.toolCalls)
                            
                            // Формируем информацию о токенах
                            val tokenInfo = buildString {
                                chatResult.usage?.let { usage ->
                                    append("\n\n")
                                    append("📊 Использовано токенов:\n")
                                    usage.promptTokens?.let { append("• Промпт: $it\n") }
                                    usage.completionTokens?.let { append("• Ответ: $it\n") }
                                    usage.totalTokens?.let { append("• Всего: $it\n") }
                                    usage.cost?.let { append("• Стоимость: $$it\n") }
                                }
                            }
                            
                            // Отправляем финальный результат без Markdown форматирования
                            val finalMessage = buildString {
                                append("✅ Результат:\n\n")
                                append(chatResult.content)
                                if (chatResult.toolCalls.isNotEmpty()) {
                                    append("\n\n")
                                    append("Использовано инструментов: ${chatResult.toolCalls.size}")
                                }
                                // Добавляем список источников, если они были найдены
                                if (sources.isNotEmpty()) {
                                    append("\n\n")
                                    append("📄 Источники:\n")
                                    sources.forEach { source ->
                                        append("• $source\n")
                                    }
                                }
                                // Добавляем информацию о токенах
                                append(tokenInfo)
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
                
                command == "/end" -> {
                    // Архивируем текущий диалог
                    val archivedFileName = chatHistoryManager.archiveDialog(chatId)
                    
                    // Завершаем диалог
                    activeDialogs.remove(chatId)
                    
                    val message = if (archivedFileName != null) {
                        "✅ Диалог завершен и сохранен в файл: $archivedFileName"
                    } else {
                        "✅ Диалог завершен."
                    }
                    sendMessage(chatId, message)
                    Result.success("Диалог завершен")
                }

                command == "/vendor" -> {
                    val vendorArg = args?.trim()?.lowercase()
                    if (vendorArg.isNullOrBlank()) {
                        // Показываем текущий вендор и список доступных
                        val settings = getUserSettings(chatId)
                        val vendorList = availableVendors.joinToString("\n• ", "• ")
                        val message = buildString {
                            append("Текущий вендор: ${settings.vendor}\n\n")
                            append("📋 Доступные вендоры:\n")
                            append(vendorList)
                        }
                        sendMessage(chatId, message)
                        Result.success("Текущий вендор и список показаны")
                    } else {
                        // Меняем вендор
                        val vendor = VendorDetector.parseVendor(vendorArg)
                        if (vendor == null) {
                            val vendorList = availableVendors.joinToString("\n• ", "• ")
                            sendMessage(chatId, "❌ Неизвестный вендор: $vendorArg\n\n📋 Доступные вендоры:\n$vendorList")
                            Result.failure(IllegalArgumentException("Неизвестный вендор: $vendorArg"))
                        } else {
                            val settings = getUserSettings(chatId)
                            settings.vendor = vendorArg
                            // Сбрасываем модель при смене вендора (чтобы пользователь выбрал подходящую)
                            settings.model = null
                            saveUserSettings(chatId, settings)
                            sendMessage(chatId, "✅ Вендор изменен на: ${settings.vendor}\n\n💡 Модель сброшена. Используйте /model для выбора модели.")
                            Result.success("Вендор изменен")
                        }
                    }
                }
                
                command == "/model" -> {
                    val modelArg = args?.trim()
                    if (modelArg.isNullOrBlank()) {
                        // Показываем текущую модель и список доступных для текущего вендора
                        val settings = getUserSettings(chatId)
                        val currentVendor = settings.vendor
                        val modelsForVendor = availableModels[currentVendor] ?: emptyList()
                        
                        val message = buildString {
                            val modelText = settings.model ?: "не установлена (используется по умолчанию)"
                            append("Текущая модель: $modelText\n")
                            append("Текущий вендор: $currentVendor\n\n")
                            
                            if (modelsForVendor.isNotEmpty()) {
                                append("📋 Доступные модели для $currentVendor:\n")
                                append(modelsForVendor.joinToString("\n• ", "• "))
                            } else {
                                append("📋 Для вендора $currentVendor можно указать любую модель")
                            }
                        }
                        sendMessage(chatId, message)
                        Result.success("Текущая модель и список показаны")
                    } else {
                        // Меняем модель
                        val settings = getUserSettings(chatId)
                        settings.model = modelArg
                        saveUserSettings(chatId, settings)
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
                            saveUserSettings(chatId, settings)
                            sendMessage(chatId, "✅ Ограничение токенов изменено на: ${settings.maxTokens}")
                            Result.success("Ограничение токенов изменено")
                        }
                    }
                }
                
                command == "/temperature" -> {
                    val temperatureArg = args?.trim()
                    if (temperatureArg.isNullOrBlank()) {
                        // Показываем текущую температуру
                        val settings = getUserSettings(chatId)
                        val temperatureText = settings.temperature?.toString() ?: "не установлена (используется по умолчанию)"
                        sendMessage(chatId, "Текущая температура: $temperatureText\n\n💡 Диапазон: 0.0 - 2.0\n• Низкие значения (0.0-0.5) - более детерминированные ответы\n• Высокие значения (1.0-2.0) - более креативные ответы")
                        Result.success("Текущая температура показана")
                    } else {
                        // Меняем температуру
                        val temperatureValue = temperatureArg.toDoubleOrNull()
                        if (temperatureValue == null) {
                            sendMessage(chatId, "❌ Неверное значение. Температура должна быть числом.")
                            Result.failure(IllegalArgumentException("Неверное значение temperature: $temperatureArg"))
                        } else if (temperatureValue < 0 || temperatureValue >= 2) {
                            sendMessage(chatId, "❌ Неверное значение. Температура должна быть в диапазоне: 0.0 <= temperature < 2.0")
                            Result.failure(IllegalArgumentException("Температура вне диапазона: $temperatureArg"))
                        } else {
                            val settings = getUserSettings(chatId)
                            settings.temperature = temperatureValue
                            saveUserSettings(chatId, settings)
                            sendMessage(chatId, "✅ Температура изменена на: ${settings.temperature}")
                            Result.success("Температура изменена")
                        }
                    }
                }

                command == "/systemprompt" || command == "/prompt" -> {
                    val promptArg = args?.trim()
                    if (promptArg.isNullOrBlank() || promptArg.lowercase() == "show") {
                        // Показываем текущий базовый системный промпт
                        val settings = getUserSettings(chatId)
                        val promptText = settings.systemPrompt ?: "не установлен (используется по умолчанию)"
                        sendMessage(chatId, "Текущий базовый системный промпт:\n\n$promptText\n\n💡 Используйте /systemprompt full для просмотра полного промпта с инструментами")
                        Result.success("Текущий системный промпт показан")
                    } else if (promptArg.lowercase() == "full") {
                        // Показываем полный промпт с инструментами
                        val settings = getUserSettings(chatId)
                        sendMessage(chatId, "⏳ Получаю список инструментов и формирую полный промпт...")
                        
                        val fullPrompt = getFullSystemPrompt(settings.systemPrompt, settings.includeToolsInSystemPrompt)
                        fullPrompt.fold(
                            onSuccess = { prompt ->
                                // Разбиваем длинный промпт на части, если он слишком большой для Telegram
                                val maxLength = 4000 // Telegram ограничение на длину сообщения
                                if (prompt.length > maxLength) {
                                    val parts = prompt.chunked(maxLength - 100)
                                    parts.forEachIndexed { index, part ->
                                        val partMessage = if (parts.size > 1) {
                                            "📋 Полный системный промпт (часть ${index + 1}/${parts.size}):\n\n$part"
                                        } else {
                                            "📋 Полный системный промпт:\n\n$part"
                                        }
                                        sendMessage(chatId, partMessage)
                                    }
                                } else {
                                    sendMessage(chatId, "📋 Полный системный промпт:\n\n$prompt")
                                }
                                Result.success("Полный системный промпт показан")
                            },
                            onFailure = { error ->
                                sendMessage(chatId, "❌ Ошибка при получении полного промпта: ${error.message}")
                                Result.failure(error)
                            }
                        )
                    } else if (promptArg.lowercase() == "clear" || promptArg.lowercase() == "reset") {
                        // Сбрасываем системный промпт
                        val settings = getUserSettings(chatId)
                        settings.systemPrompt = null
                        saveUserSettings(chatId, settings)
                        sendMessage(chatId, "✅ Системный промпт сброшен (будет использоваться по умолчанию)")
                        Result.success("Системный промпт сброшен")
                    } else {
                        // Устанавливаем новый системный промпт
                        val settings = getUserSettings(chatId)
                        settings.systemPrompt = promptArg
                        saveUserSettings(chatId, settings)
                        sendMessage(chatId, "✅ Системный промпт установлен:\n\n$promptArg")
                        Result.success("Системный промпт установлен")
                    }
                }

                command == "/tools" -> {
                    val toolsArg = args?.trim()?.lowercase()
                    if (toolsArg.isNullOrBlank()) {
                        // Показываем текущую настройку
                        val settings = getUserSettings(chatId)
                        val status = if (settings.includeToolsInSystemPrompt) "включено" else "выключено"
                        val message = buildString {
                            append("Текущая настройка добавления инструментов в системный промпт: $status\n\n")
                            append("💡 Когда включено: в системный промпт добавляется описание всех доступных инструментов и правила их использования.\n")
                            append("💡 Когда выключено: в системный промпт добавляется только ваш базовый промпт (если установлен).\n\n")
                            append("Используйте:\n")
                            append("/tools on - включить добавление инструментов\n")
                            append("/tools off - выключить добавление инструментов")
                        }
                        sendMessage(chatId, message)
                        Result.success("Текущая настройка показана")
                    } else {
                        // Меняем настройку
                        val settings = getUserSettings(chatId)
                        when (toolsArg) {
                            "on", "enable", "true", "1", "вкл", "включить" -> {
                                settings.includeToolsInSystemPrompt = true
                                saveUserSettings(chatId, settings)
                                sendMessage(chatId, "✅ Добавление инструментов в системный промпт включено")
                                Result.success("Настройка изменена")
                            }
                            "off", "disable", "false", "0", "выкл", "выключить" -> {
                                settings.includeToolsInSystemPrompt = false
                                saveUserSettings(chatId, settings)
                                sendMessage(chatId, "✅ Добавление инструментов в системный промпт выключено")
                                Result.success("Настройка изменена")
                            }
                            else -> {
                                sendMessage(chatId, "❌ Неверное значение. Используйте: /tools on или /tools off")
                                Result.failure(IllegalArgumentException("Неверное значение: $toolsArg"))
                            }
                        }
                    }
                }

                command == "/start" || command == "/help" -> {
                    val helpText = """
                        🤖 *AI Chat Bot с поддержкой инструментов*
                        
                        *Команды:*
                        /chat <сообщение> - Начать диалог или отправить сообщение в активном диалоге
                        /end - Завершить текущий диалог
                        /vendor - Показать текущий вендор
                        /vendor <название> - Изменить вендор (perplexity, gigachat, huggingface)
                        /model - Показать текущую модель
                        /model <название> - Изменить модель
                        /maxtokens - Показать текущее ограничение токенов
                        /maxtokens <число> - Изменить ограничение токенов
                        /temperature - Показать текущую температуру
                        /temperature <число> - Изменить температуру (0.0 - 2.0)
                        /systemprompt - Показать текущий базовый системный промпт
                        /systemprompt full - Показать полный системный промпт с инструментами
                        /systemprompt <текст> - Установить новый системный промпт
                        /systemprompt clear - Сбросить системный промпт
                        /tools - Показать настройку добавления инструментов в системный промпт
                        /tools on - Включить добавление инструментов в системный промпт
                        /tools off - Выключить добавление инструментов в системный промпт
                        /help - Показать эту справку
                        
                        *Режим диалога:*
                        После команды /chat вы входите в режим диалога. Все ваши сообщения будут сохраняться в истории. Для завершения диалога используйте команду /end.
                        
                        *Примеры:*
                        /chat Какая погода в Москве?
                        /vendor gigachat
                        /model GigaChat-2
                        /maxtokens 512
                        /temperature 0.7
                        /systemprompt Ты полезный ассистент
                        /tools off
                        /systemprompt full
                        /end
                        
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
                // Если сообщение не команда, проверяем, активен ли диалог
                if (activeDialogs[chatId] == true) {
                    // Пользователь в режиме диалога - обрабатываем как /chat
                    handleCommand(chatId, "/chat", text)
                } else {
                    // Диалог не активен - предлагаем начать диалог
                    sendMessage(chatId, "💬 Для начала диалога используйте команду /chat <ваше сообщение>")
                }
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

    /**
     * Получает полный системный промпт с инструментами
     */
    private suspend fun getFullSystemPrompt(baseSystemPrompt: String?, includeTools: Boolean = true): Result<String> {
        return try {
            // Создаем MCP репозитории для каждого сервера
            val mcpRepositories = mutableMapOf<String, McpRepositoryImpl>()
            defaultMcpServerUrls.forEach { serverUrl ->
                try {
                    val repository = McpRepositoryImpl(serverUrl = serverUrl)
                    mcpRepositories[serverUrl] = repository
                    logger.info("Создан MCP репозиторий для сервера: $serverUrl")
                } catch (e: Exception) {
                    logger.error("Ошибка при создании репозитория для сервера $serverUrl: ${e.message}", e)
                }
            }
            
            if (mcpRepositories.isEmpty()) {
                return Result.failure(Exception("Не удалось подключиться ни к одному MCP серверу"))
            }
            
            // Получаем список тулзов от всех серверов
            val allTools = mutableListOf<McpTool>()
            mcpRepositories.forEach { (serverUrl, repository) ->
                try {
                    val toolsResult = repository.listTools()
                    toolsResult.fold(
                        onSuccess = { tools ->
                            val toolsWithServer = tools.map { tool ->
                                tool.copy(serverUrl = serverUrl)
                            }
                            allTools.addAll(toolsWithServer)
                            logger.info("Получено ${tools.size} тулзов от MCP-сервера: $serverUrl")
                        },
                        onFailure = { error ->
                            logger.error("Ошибка при получении списка тулзов от сервера $serverUrl: ${error.message}")
                        }
                    )
                } catch (e: Exception) {
                    logger.error("Исключение при получении тулзов от сервера $serverUrl: ${e.message}", e)
                }
            }
            
            // Формируем полный системный промпт
            val fullPrompt = buildFullSystemPrompt(allTools, baseSystemPrompt, includeTools)
            Result.success(fullPrompt)
        } catch (e: Exception) {
            logger.error("Ошибка при получении полного системного промпта: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Формирует полный системный промпт с инструментами
     */
    private fun buildFullSystemPrompt(tools: List<McpTool>, baseSystemPrompt: String?, includeTools: Boolean = true): String {
        val parts = mutableListOf<String>()
        
        // Базовый системный промпт
        baseSystemPrompt?.let { parts.add(it) }
        
        // Описание тулзов (добавляем только если includeTools = true)
        if (includeTools) {
            val toolsDescription = buildString {
            append("You are an AI agent that can use tools through MCP (Model Context Protocol).\n")
            append("You can chain multiple tool calls in a conversation to accomplish complex tasks.\n\n")
            append("Available tools:\n")
            tools.forEachIndexed { index, tool ->
                append("${index + 1}) ${tool.name}")
                tool.description?.let { append(": $it") }
                tool.serverUrl?.let { append(" [MCP: $it]") }
                append("\n")
                
                // Описываем схему аргументов
                tool.inputSchema?.let { schema ->
                    append("   Arguments: ")
                    val argsDescription = extractArgumentsDescription(schema)
                    append(argsDescription)
                    append("\n")
                }
            }
            append("\n")
            append("IMPORTANT RULES:\n")
            append("1. You can call tools multiple times in a chain. After a tool is executed, you will receive its result and can call another tool if needed.\n")
            append("2. You can use different tools in sequence to accomplish your goal.\n")
            append("3. Continue using tools until you have all the information needed to provide a final answer.\n")
            append("4. Only stop using tools when you have enough information to give a complete answer to the user.\n\n")
            append("RESPONSE FORMAT:\n")
            append("If you need to call a tool, respond with JSON ONLY in this format:\n")
            append("{\"tool\": \"<tool_name>\", \"args\": { ... }}\n")
            append("\n")
            append("If you have enough information and want to provide the final answer (no more tools needed), respond with JSON ONLY in this format:\n")
            append("{\"final\": \"<your final answer>\"}\n")
            append("\n")
            append("CRITICAL: Your response must be valid JSON. Do not include any text before or after the JSON.")
            }
            parts.add(toolsDescription)
        }
        
        return parts.joinToString("\n\n")
    }
    
    /**
     * Извлекает описание аргументов из JSON Schema
     */
    private fun extractArgumentsDescription(schema: Map<String, JsonElement>): String {
        return try {
            // Пытаемся найти properties в схеме (стандартный формат JSON Schema)
            val properties = schema["properties"]?.jsonObject
            if (properties != null) {
                // Извлекаем свойства и их типы
                val argsList = properties.entries.mapNotNull { (propName, propValue) ->
                    val propObj = propValue.jsonObject ?: return@mapNotNull null
                    val type = propObj["type"]?.jsonPrimitive?.content ?: "any"
                    val title = propObj["title"]?.jsonPrimitive?.content
                    val description = propObj["description"]?.jsonPrimitive?.content
                    
                    val argDesc = buildString {
                        append(propName)
                        append(": ")
                        append(type)
                        title?.let { append(" ($it)") }
                        description?.let { append(" - $it") }
                    }
                    argDesc
                }
                
                // Проверяем required поля
                val required = schema["required"]?.jsonArray?.mapNotNull { element ->
                    try {
                        element.jsonPrimitive.content
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
                
                if (argsList.isEmpty()) {
                    "нет аргументов"
                } else {
                    val requiredStr = if (required.isNotEmpty()) {
                        " (обязательные: ${required.joinToString(", ")})"
                    } else {
                        ""
                    }
                    argsList.joinToString(", ") + requiredStr
                }
            } else {
                // Если нет properties, пытаемся описать схему проще
                val type = schema["type"]?.jsonPrimitive?.content
                if (type != null) {
                    "type: $type"
                } else {
                    "см. схему выше"
                }
            }
        } catch (e: Exception) {
            logger.debug("Ошибка при извлечении описания аргументов: ${e.message}")
            "см. схему выше"
        }
    }

    fun close() {
        httpClient.close()
    }
}

