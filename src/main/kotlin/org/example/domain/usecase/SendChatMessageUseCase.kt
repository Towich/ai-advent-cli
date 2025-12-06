package org.example.domain.usecase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.example.domain.exception.DomainException
import org.example.domain.model.ChatRequest
import org.example.domain.model.ChatResult
import org.example.domain.model.DialogSession
import org.example.domain.model.Message
import org.example.domain.repository.PerplexityRepository
import org.example.domain.repository.SessionRepository

/**
 * Use Case для отправки сообщения в чат
 */
class SendChatMessageUseCase(
    private val sessionRepository: SessionRepository,
    private val perplexityRepository: PerplexityRepository,
    private val defaultModel: String,
    private val defaultMaxTokens: Int
) {
    suspend fun execute(request: ChatRequest): Result<ChatResult> {
        // Валидация
        if (request.message.isBlank()) {
            return Result.failure(DomainException.EmptyMessageException())
        }
        
        if (request.maxRounds != null && request.maxRounds < 1) {
            return Result.failure(DomainException.InvalidMaxRoundsException())
        }
        
        // Получить или создать сессию
        val session = if (request.sessionId != null) {
            sessionRepository.getSession(request.sessionId)
                ?: return Result.failure(DomainException.SessionNotFoundException(request.sessionId))
        } else {
            // Создаем новую сессию, если указан maxRounds
            if (request.maxRounds != null && request.maxRounds > 1) {
                createNewSession(request)
            } else {
                null
            }
        }
        
        // Проверка состояния сессии
        if (session != null) {
            if (session.isComplete) {
                return Result.failure(DomainException.DialogCompletedException())
            }
            
            if (session.currentRound >= session.maxRounds) {
                session.isComplete = true
                return Result.failure(DomainException.MaxRoundsExceededException())
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
        
        // Отправить сообщение
        val result = perplexityRepository.sendMessage(messages, model, maxTokens, disableSearch)
        
        return result.map { (content, responseModel) ->
            // Валидируем JSON, если требуется
            if (request.outputFormat?.lowercase() == "json" && !isValidJson(content)) {
                throw Exception("Ответ от Perplexity API не является валидным JSON")
            }
            if (session != null) {
                // Обновить сессию
                session.addUserMessage(request.message)
                session.addAssistantMessage(content)
                session.incrementRound()
                session.updateLastActivity()
                
                val isComplete = session.currentRound >= session.maxRounds
                if (isComplete) {
                    session.isComplete = true
                }
                
                ChatResult(
                    content = content,
                    model = responseModel,
                    isComplete = isComplete,
                    round = session.currentRound,
                    maxRounds = session.maxRounds,
                    sessionId = session.sessionId
                )
            } else {
                // Режим одного раунда
                ChatResult(
                    content = content,
                    model = responseModel,
                    isComplete = true,
                    round = 1,
                    maxRounds = 1,
                    sessionId = ""
                )
            }
        }
    }
    
    private fun createNewSession(request: ChatRequest): DialogSession {
        val model = request.model ?: defaultModel
        val maxTokens = request.maxTokens ?: defaultMaxTokens
        val disableSearch = request.disableSearch ?: true
        val maxRounds = request.maxRounds ?: 1
        
        return sessionRepository.createSession(
            sessionId = java.util.UUID.randomUUID().toString(),
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
            val baseSystemPrompt = session.systemPrompt
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
            
            // Добавляем системное сообщение
            finalSystemPrompt?.let { prompt ->
                messages.add(Message(role = Message.ROLE_SYSTEM, content = prompt))
            }
            
            // Добавляем историю сообщений (кроме системных)
            session.messages.forEach { message ->
                if (message.role != Message.ROLE_SYSTEM) {
                    messages.add(message)
                }
            }
            
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
            parts.add("\nТекущий раунд: $currentRound из $maxRounds. Отвечай только одним вопросом в этом раунде")
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

