package org.example.domain.usecase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.domain.exception.DomainException
import org.example.domain.model.ChatRequest
import org.example.domain.model.ChatResult
import org.example.domain.model.DialogSession
import org.example.domain.model.Message
import org.example.domain.repository.GigaChatRepository
import org.example.domain.repository.HuggingFaceRepository
import org.example.domain.repository.PerplexityRepository
import org.example.domain.repository.SessionRepository
import org.example.domain.service.MessageCharacterCounter
import org.example.infrastructure.config.Vendor
import org.example.infrastructure.config.VendorDetector
import org.slf4j.LoggerFactory

/**
 * Use Case для отправки сообщения в чат
 */
class SendChatMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val perplexityRepository: PerplexityRepository,
    private val gigaChatRepository: GigaChatRepository,
    private val huggingFaceRepository: HuggingFaceRepository,
    private val compressDialogHistoryUseCase: CompressDialogHistoryUseCase,
    private val defaultModel: String,
    private val defaultMaxTokens: Int
) {
    private val logger = LoggerFactory.getLogger(SendChatMessageUseCase::class.java)
    suspend fun execute(request: ChatRequest): Result<ChatResult> {
        // Валидация
        if (request.message.isBlank()) {
            return Result.failure(DomainException.EmptyMessageException())
        }
        
        if (request.maxRounds != null && request.maxRounds < 1) {
            return Result.failure(DomainException.InvalidMaxRoundsException())
        }
        
        // Валидация параметров компрессии
        if (request.compressionMessagesThreshold != null && request.compressionTokensThreshold != null) {
            return Result.failure(DomainException.BothCompressionThresholdsException())
        }
        
        // Получить текущую сессию или создать новую, если указан maxRounds > 1
        val session = if (request.maxRounds != null && request.maxRounds > 1) {
            val existingSession = sessionRepository.getSession()
            if (existingSession != null && !existingSession.isComplete) {
                logger.debug("Используется существующая сессия: round=${existingSession.currentRound}/${existingSession.maxRounds}")
                existingSession
            } else {
                logger.info("Создается новая сессия диалога: maxRounds=${request.maxRounds}")
                createNewSession(request)
            }
        } else {
            null
        }
        
        // Переменная для отслеживания компрессии
        var wasCompressed = false
        
        // Проверка состояния сессии
        if (session != null) {
            if (session.isComplete) {
                return Result.failure(DomainException.DialogCompletedException())
            }
            
            if (session.currentRound >= session.maxRounds) {
                session.isComplete = true
                return Result.failure(DomainException.MaxRoundsExceededException())
            }
            
            // Проверяем и сжимаем историю, если нужно
            val compressionMessagesThreshold = request.compressionMessagesThreshold
            val compressionTokensThreshold = request.compressionTokensThreshold
            
            if (compressionMessagesThreshold != null || compressionTokensThreshold != null) {
                // Определяем vendor и model из запроса для компрессии (используем ту же логику, что и для основного запроса)
                val vendor = VendorDetector.parseVendor(request.vendor)
                    ?: throw IllegalArgumentException("Неизвестный vendor: ${request.vendor}")
                val modelForCompression = request.model ?: session.model
                
                val shouldCompress = if (compressionMessagesThreshold != null) {
                    compressDialogHistoryUseCase.shouldCompressByMessages(session, compressionMessagesThreshold)
                } else {
                    compressDialogHistoryUseCase.shouldCompressByTokens(session, compressionTokensThreshold!!)
                }
                
                if (shouldCompress) {
                    val compressResult = if (compressionMessagesThreshold != null) {
                        compressDialogHistoryUseCase.compressByMessages(
                            session = session,
                            vendor = vendor,
                            model = modelForCompression,
                            compressionMessagesThreshold = compressionMessagesThreshold
                        )
                    } else {
                        compressDialogHistoryUseCase.compressByTokens(
                            session = session,
                            vendor = vendor,
                            model = modelForCompression,
                            compressionTokensThreshold = compressionTokensThreshold!!
                        )
                    }
                    
                    compressResult.fold(
                        onSuccess = { 
                            wasCompressed = true
                            // Сохранить сессию после компрессии
                            sessionRepository.updateSession(session)
                        },
                        onFailure = { error ->
                            // Логируем ошибку, но продолжаем выполнение
                            // Логирование будет добавлено в CompressDialogHistoryUseCase
                        }
                    )
                }
            }
        }
        
        // Определить, является ли это последним раундом
        val isLastRound = session?.let { (it.currentRound + 1) >= it.maxRounds } ?: false
        
        // Построить сообщения для отправки
        val messages = buildMessages(request, session, isLastRound)
        
        // Получить параметры модели
        val model = session?.model ?: (request.model ?: defaultModel)
        val maxTokens = session?.maxTokens ?: (request.maxTokens ?: defaultMaxTokens)
        val disableSearch = session?.disableSearch ?: (request.disableSearch ?: true)
        val temperature = request.temperature
        
        // Определить вендора из запроса
        val vendor = VendorDetector.parseVendor(request.vendor)
            ?: throw IllegalArgumentException("Неизвестный vendor: ${request.vendor}")
        
        // Измерить время выполнения запроса к нейросети
        val startTime = System.currentTimeMillis()
        
        // Отправить сообщение в соответствующий репозиторий
        val result = when (vendor) {
            Vendor.PERPLEXITY -> perplexityRepository.sendMessage(messages, model, maxTokens, disableSearch, temperature)
            Vendor.GIGACHAT -> gigaChatRepository.sendMessage(messages, model, maxTokens, disableSearch, temperature)
            Vendor.HUGGINGFACE -> huggingFaceRepository.sendMessage(messages, model, maxTokens, disableSearch, temperature)
        }
        
        val executionTimeMs = System.currentTimeMillis() - startTime
        
        return result.map { (content, responseModel, usage) ->
            // Валидируем JSON, если требуется
            if (request.outputFormat?.lowercase() == "json" && !isValidJson(content)) {
                val vendorName = when (vendor) {
                    Vendor.PERPLEXITY -> "Perplexity"
                    Vendor.GIGACHAT -> "GigaChat"
                    Vendor.HUGGINGFACE -> "Hugging Face"
                }
                throw Exception("Ответ от $vendorName API не является валидным JSON")
            }
            if (session != null) {
                // Обновить сессию
                session.addUserMessage(request.message)
                session.addAssistantMessage(content)
                session.incrementRound()
                session.updateLastActivity()
                
                // Накопить totalTokens из usage
                usage?.totalTokens?.let { tokens ->
                    session.accumulatedTotalTokens += tokens
                }
                
                val isComplete = session.currentRound >= session.maxRounds
                if (isComplete) {
                    session.isComplete = true
                }
                
                // Сохранить сессию в файл
                sessionRepository.updateSession(session)
                
                // Подсчитываем количество символов во всех сообщениях (включая системное, пользователя и ассистента)
                val totalCharactersCount = MessageCharacterCounter.countTotalCharacters(session.messages)
                
                ChatResult(
                    content = content,
                    model = responseModel,
                    isComplete = isComplete,
                    round = session.currentRound,
                    maxRounds = session.maxRounds,
                    executionTimeMs = executionTimeMs,
                    usage = usage,
                    totalCharactersCount = totalCharactersCount,
                    wasCompressed = wasCompressed.takeIf { it }
                )
            } else {
                // Режим одного раунда - подсчитываем символы из сообщений, которые были отправлены
                val totalCharactersCount = MessageCharacterCounter.countTotalCharacters(messages)
                
                ChatResult(
                    content = content,
                    model = responseModel,
                    isComplete = true,
                    round = 1,
                    maxRounds = 1,
                    executionTimeMs = executionTimeMs,
                    usage = usage,
                    totalCharactersCount = totalCharactersCount,
                    wasCompressed = wasCompressed.takeIf { it }
                )
            }
        }
    }
    
    private fun createNewSession(request: ChatRequest): DialogSession {
        val model = request.model ?: defaultModel
        val maxTokens = request.maxTokens ?: defaultMaxTokens
        val disableSearch = request.disableSearch ?: true
        val maxRounds = request.maxRounds ?: 1
        
        return sessionRepository.createOrResetSession(
            systemPrompt = request.systemPrompt,
            model = model,
            maxTokens = maxTokens,
            disableSearch = disableSearch,
            maxRounds = maxRounds,
            initialUserMessage = request.message
        )
    }
    
    private fun buildMessages(
        request: ChatRequest,
        session: DialogSession?,
        isLastRound: Boolean
    ): List<Message> {
        val messages = mutableListOf<Message>()
        
        if (session != null) {
            // Работаем с сессией
            val baseSystemPrompt = request.systemPrompt ?: session.systemPrompt
            val nextRound = session.currentRound + 1
            
            val systemPrompt = buildSystemPromptWithRoundContext(
                baseSystemPrompt = baseSystemPrompt,
                isLastRound = isLastRound,
                initialUserMessage = session.initialUserMessage,
                currentRound = nextRound,
                maxRounds = session.maxRounds
            )
            
            // Добавляем формат вывода, если указан
            val finalSystemPrompt = if (request.outputFormat != null) {
                val parts = mutableListOf<String>()
                systemPrompt?.let { parts.add(it) }
                
                request.outputFormat?.let { format ->
                    parts.add(buildFormatSystemPrompt(format))
                    
                    if (format.lowercase() == "json" && request.outputSchema != null) {
                        parts.add("Схема JSON: ${request.outputSchema}")
                    }
                }
                
                if (parts.isNotEmpty()) {
                    parts.joinToString("\n\n")
                } else {
                    null
                }
            } else {
                systemPrompt
            }
            
            // Собираем все системные промпты в один
            val allSystemPrompts = mutableListOf<String>()
            
            // Добавляем основной системный промпт (с информацией о раунде)
            finalSystemPrompt?.let { allSystemPrompts.add(it) }
            
            // Добавляем сжатые системные сообщения (с маркером [COMPRESSED_HISTORY])
            session.messages.forEach { message ->
                if (message.role == Message.ROLE_SYSTEM && message.content.startsWith("[COMPRESSED_HISTORY]")) {
                    allSystemPrompts.add(message.content)
                }
            }
            
            // Объединяем все системные промпты в один
            if (allSystemPrompts.isNotEmpty()) {
                val combinedSystemPrompt = allSystemPrompts.joinToString("\n\n")
                messages.add(Message(role = Message.ROLE_SYSTEM, content = combinedSystemPrompt))
            }
            
            // Добавляем историю сообщений (только user и assistant, исключая системные)
            // Важно: после системных сообщений должно идти user, а не assistant
            val historyMessages = session.messages.filter { it.role != Message.ROLE_SYSTEM }
            
            // Если первое сообщение в истории - assistant, пропускаем его
            // (это может произойти, если последнее сообщение перед сжатием было от assistant)
            val messagesToAdd = if (historyMessages.isNotEmpty() && historyMessages.first().role == Message.ROLE_ASSISTANT) {
                historyMessages.drop(1) // Пропускаем первое assistant сообщение
            } else {
                historyMessages
            }
            
            messages.addAll(messagesToAdd)
            
            // Добавляем новое пользовательское сообщение
            messages.add(Message(role = Message.ROLE_USER, content = request.message))
        } else {
            // Работаем без сессии
            val baseSystemPrompt = buildSystemPrompt(request)
            
            baseSystemPrompt?.let { prompt ->
                messages.add(Message(role = Message.ROLE_SYSTEM, content = prompt))
            }
            
            messages.add(Message(role = Message.ROLE_USER, content = request.message))
        }
        
        return messages
    }
    
    private fun buildSystemPrompt(request: ChatRequest): String? {
        val parts = mutableListOf<String>()
        
        request.systemPrompt?.let { parts.add(it) }
        
        request.outputFormat?.let { format ->
            parts.add(buildFormatSystemPrompt(format))
            
            if (format.lowercase() == "json" && request.outputSchema != null) {
                parts.add("Схема JSON: ${request.outputSchema}")
            }
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }
    
    private fun buildSystemPromptWithRoundContext(
        baseSystemPrompt: String?,
        isLastRound: Boolean,
        initialUserMessage: String?,
        currentRound: Int,
        maxRounds: Int
    ): String? {
        val parts = mutableListOf<String>()
        
        if (baseSystemPrompt != null) {
            parts.add(baseSystemPrompt)
        }
        
        if (isLastRound) {
            val lastRoundInstruction = buildString {
                append("\n\nВАЖНО: Это последний раунд диалога (раунд $currentRound из $maxRounds).")
                if (initialUserMessage != null) {
                    append("\nПервоначальный запрос пользователя был: \"$initialUserMessage\"")
                }
                append("\n\nТвоя задача:")
                append("\n1. Собрать всю информацию, полученную в предыдущих раундах диалога")
                append("\n2. Проанализировать все ответы пользователя на твои вопросы")
                append("\n3. Дать полный, исчерпывающий и структурированный ответ на первоначальный запрос пользователя")
                append("\n4. Не задавай новых вопросов - это финальный ответ")
                append("\n\nОтвет должен быть максимально полным и полезным, учитывая всю собранную информацию.")
            }
            parts.add(lastRoundInstruction)
        } else {
            parts.add("\nТекущий раунд: $currentRound из $maxRounds.")
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString("\n\n")
        } else {
            null
        }
    }
    
    private fun buildFormatSystemPrompt(format: String): String {
        return """
            Ты — ассистент, который отвечает исключительно в формате $format без какой-либо дополнительной информации, пояснений или текста. 
            Каждый ответ должен быть валидным $format-объектом, содержащим только необходимую структурированную информацию по запросу. 
            Никакого свободного текста, описаний, комментариев или объяснений не добавляй.
            Также не добавляй служебных символов, по типу "```json"
            Не используй знаки переноса строк ('\n') и не переноси текст на д, пиши всё сплошным текстом
        """.trimIndent()
    }
    
    private fun isValidJson(content: String): Boolean {
        return try {
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            json.decodeFromString<JsonObject>(content.trim())
            true
        } catch (e: Exception) {
            false
        }
    }
}

