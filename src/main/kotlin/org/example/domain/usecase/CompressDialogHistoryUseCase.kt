package org.example.domain.usecase

import org.example.domain.model.DialogSession
import org.example.domain.model.Message
import org.example.domain.repository.GigaChatRepository
import org.example.domain.repository.HuggingFaceRepository
import org.example.domain.repository.PerplexityRepository
import org.example.infrastructure.config.Vendor
import org.example.infrastructure.config.VendorDetector

/**
 * Use Case для сжатия истории диалога
 * Создает summary из старых сообщений и заменяет их на сжатое сообщение
 */
class CompressDialogHistoryUseCase(
    private val perplexityRepository: PerplexityRepository,
    private val gigaChatRepository: GigaChatRepository,
    private val huggingFaceRepository: HuggingFaceRepository
) {
    companion object {
        private const val DEFAULT_COMPRESSION_THRESHOLD = 10 // Значение по умолчанию
    }
    
    /**
     * Проверяет, нужно ли сжимать историю диалога
     * 
     * @param session сессия диалога
     * @param compressionThreshold порог количества сообщений для сжатия (по умолчанию 10)
     * @return true, если количество сообщений user и assistant >= compressionThreshold
     */
    fun shouldCompress(session: DialogSession, compressionThreshold: Int = DEFAULT_COMPRESSION_THRESHOLD): Boolean {
        // Считаем только user и assistant сообщения (не системные и не сжатые)
        val userAndAssistantMessages = session.messages.filter { 
            it.role == Message.ROLE_USER || it.role == Message.ROLE_ASSISTANT
        }
        
        return userAndAssistantMessages.size >= compressionThreshold
    }
    
    /**
     * Сжимает историю диалога, создавая summary из первых compressionThreshold сообщений
     * 
     * @param session сессия диалога
     * @param compressionThreshold порог количества сообщений для сжатия (по умолчанию 10)
     * @return Result с успешным результатом или ошибкой
     */
    suspend fun compress(session: DialogSession, compressionThreshold: Int = DEFAULT_COMPRESSION_THRESHOLD): Result<Unit> {
        // Берем только user и assistant сообщения (не системные и не сжатые)
        val userAndAssistantMessages = session.messages.filter { 
            (it.role == Message.ROLE_USER || it.role == Message.ROLE_ASSISTANT) &&
            !it.content.startsWith("[COMPRESSED_HISTORY]")
        }
        
        if (userAndAssistantMessages.size < compressionThreshold) {
            return Result.success(Unit)
        }
        
        // Берем первые compressionThreshold сообщений для сжатия
        var messagesToCompress = userAndAssistantMessages.take(compressionThreshold)
        
        if (messagesToCompress.isEmpty()) {
            return Result.success(Unit)
        }
        
        // Если последнее сообщение в списке для сжатия - это user, 
        // и есть следующий assistant ответ, включаем его тоже
        // (чтобы не терять контекст последнего ответа ассистента)
        if (messagesToCompress.isNotEmpty() && 
            messagesToCompress.last().role == Message.ROLE_USER &&
            userAndAssistantMessages.size > compressionThreshold &&
            userAndAssistantMessages[compressionThreshold].role == Message.ROLE_ASSISTANT) {
            messagesToCompress = userAndAssistantMessages.take(compressionThreshold + 1)
        }
        
        // Создаем промпт для summary
        val summaryPrompt = buildSummaryPrompt(messagesToCompress)
        
        // Определяем vendor и модель из сессии
        val vendor = VendorDetector.detectVendorFromModel(session.model)
            ?: Vendor.PERPLEXITY // По умолчанию используем Perplexity
        
        // Строим сообщения для запроса summary
        val messagesForSummary = buildMessagesForSummary(session, summaryPrompt)
        
        // Отправляем запрос на создание summary
        val result = when (vendor) {
            Vendor.PERPLEXITY -> perplexityRepository.sendMessage(
                messages = messagesForSummary,
                model = session.model,
                maxTokens = session.maxTokens,
                disableSearch = true, // Для summary отключаем поиск
                temperature = null
            )
            Vendor.GIGACHAT -> gigaChatRepository.sendMessage(
                messages = messagesForSummary,
                model = session.model,
                maxTokens = session.maxTokens,
                disableSearch = true,
                temperature = null
            )
            Vendor.HUGGINGFACE -> huggingFaceRepository.sendMessage(
                messages = messagesForSummary,
                model = session.model,
                maxTokens = session.maxTokens,
                disableSearch = true,
                temperature = null
            )
        }
        
        return result.map { (summaryContent, _, _) ->
            // Находим индексы сообщений для удаления
            val indicesToRemove = mutableListOf<Int>()
            val messagesToRemoveSet = messagesToCompress.toSet()
            
            session.messages.forEachIndexed { index, message ->
                if (message in messagesToRemoveSet) {
                    indicesToRemove.add(index)
                }
            }
            
            // Удаляем сообщения в обратном порядке, чтобы индексы не сдвигались
            indicesToRemove.reversed().forEach { index ->
                session.messages.removeAt(index)
            }
            
            // Добавляем summary как системное сообщение с особым маркером
            val summaryMessage = Message(
                role = Message.ROLE_SYSTEM,
                content = "[COMPRESSED_HISTORY] $summaryContent"
            )
            
            // Вставляем summary после системного промпта, если он есть
            // Ищем последний системный промпт (не сжатый)
            val lastSystemPromptIndex = session.messages.indexOfLast { 
                it.role == Message.ROLE_SYSTEM && !it.content.startsWith("[COMPRESSED_HISTORY]")
            }
            
            if (lastSystemPromptIndex >= 0) {
                // Вставляем после последнего системного промпта
                session.messages.add(lastSystemPromptIndex + 1, summaryMessage)
            } else {
                // Если системного промпта нет, вставляем в начало
                session.messages.add(0, summaryMessage)
            }
        }
    }
    
    private fun buildSummaryPrompt(messages: List<Message>): String {
        val conversationText = messages.joinToString("\n\n") { message ->
            when (message.role) {
                Message.ROLE_USER -> "Пользователь: ${message.content}"
                Message.ROLE_ASSISTANT -> "Ассистент: ${message.content}"
                else -> "${message.role}: ${message.content}"
            }
        }
        
        return """
            Создай краткое резюме следующего диалога, сохраняя ключевую информацию и контекст.
            Резюме должно быть лаконичным, но содержать все важные детали для продолжения диалога.
            
            Диалог:
            $conversationText
            
            Резюме:
        """.trimIndent()
    }
    
    private fun buildMessagesForSummary(session: DialogSession, summaryPrompt: String): List<Message> {
        // При компрессии не отправляем системный промпт, только промпт для создания summary
        return listOf(Message(role = Message.ROLE_USER, content = summaryPrompt))
    }
}
