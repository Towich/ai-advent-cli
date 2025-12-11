package org.example.domain.usecase

import org.example.domain.model.DialogSession
import org.example.domain.model.Message
import org.example.domain.repository.GigaChatRepository
import org.example.domain.repository.HuggingFaceRepository
import org.example.domain.repository.PerplexityRepository
import org.example.infrastructure.config.Vendor
import org.slf4j.LoggerFactory

/**
 * Use Case для сжатия истории диалога
 * Создает summary из старых сообщений и заменяет их на сжатое сообщение
 */
class CompressDialogHistoryUseCase(
    private val perplexityRepository: PerplexityRepository,
    private val gigaChatRepository: GigaChatRepository,
    private val huggingFaceRepository: HuggingFaceRepository
) {
    private val logger = LoggerFactory.getLogger(CompressDialogHistoryUseCase::class.java)
    
    companion object {
        private const val DEFAULT_COMPRESSION_THRESHOLD = 10 // Значение по умолчанию
    }
    
    /**
     * Проверяет, нужно ли сжимать историю диалога по количеству сообщений
     * 
     * @param session сессия диалога
     * @param compressionMessagesThreshold порог количества сообщений для сжатия
     * @return true, если количество сообщений user и assistant >= compressionMessagesThreshold
     */
    fun shouldCompressByMessages(session: DialogSession, compressionMessagesThreshold: Int): Boolean {
        // Считаем только user и assistant сообщения (не системные и не сжатые)
        val userAndAssistantMessages = session.messages.filter { 
            it.role == Message.ROLE_USER || it.role == Message.ROLE_ASSISTANT
        }
        
        return userAndAssistantMessages.size >= compressionMessagesThreshold
    }
    
    /**
     * Проверяет, нужно ли сжимать историю диалога по количеству токенов
     * 
     * @param session сессия диалога
     * @param compressionTokensThreshold порог количества токенов для сжатия
     * @return true, если накопленное количество токенов >= compressionTokensThreshold
     */
    fun shouldCompressByTokens(session: DialogSession, compressionTokensThreshold: Int): Boolean {
        return session.accumulatedTotalTokens >= compressionTokensThreshold
    }
    
    /**
     * Сжимает историю диалога по количеству сообщений, создавая summary из первых compressionMessagesThreshold сообщений
     * 
     * @param session сессия диалога
     * @param vendor вендор для компрессии (из запроса)
     * @param model модель для компрессии (из запроса)
     * @param compressionMessagesThreshold порог количества сообщений для сжатия
     * @return Result с успешным результатом или ошибкой
     */
    suspend fun compressByMessages(
        session: DialogSession, 
        vendor: Vendor,
        model: String,
        compressionMessagesThreshold: Int
    ): Result<Unit> {
        // Берем только user и assistant сообщения (не системные и не сжатые)
        val userAndAssistantMessages = session.messages.filter { 
            (it.role == Message.ROLE_USER || it.role == Message.ROLE_ASSISTANT) &&
            !it.content.startsWith("[COMPRESSED_HISTORY]")
        }
        
        if (userAndAssistantMessages.size < compressionMessagesThreshold) {
            return Result.success(Unit)
        }
        
        // Берем первые compressionMessagesThreshold сообщений для сжатия
        var messagesToCompress = userAndAssistantMessages.take(compressionMessagesThreshold)
        
        if (messagesToCompress.isEmpty()) {
            return Result.success(Unit)
        }
        
        // Если последнее сообщение в списке для сжатия - это user, 
        // и есть следующий assistant ответ, включаем его тоже
        // (чтобы не терять контекст последнего ответа ассистента)
        if (messagesToCompress.isNotEmpty() && 
            messagesToCompress.last().role == Message.ROLE_USER &&
            userAndAssistantMessages.size > compressionMessagesThreshold &&
            userAndAssistantMessages[compressionMessagesThreshold].role == Message.ROLE_ASSISTANT) {
            messagesToCompress = userAndAssistantMessages.take(compressionMessagesThreshold + 1)
        }
        
        logger.info("Компрессия истории диалога: сжимаем ${messagesToCompress.size} сообщений, vendor=$vendor, модель=$model")
        
        // Создаем промпт для summary
        val summaryPrompt = buildSummaryPrompt(messagesToCompress)
        
        // Строим сообщения для запроса summary
        val messagesForSummary = buildMessagesForSummary(session, summaryPrompt)
        
        // Отправляем запрос на создание summary используя vendor и model из запроса
        val result = when (vendor) {
            Vendor.PERPLEXITY -> perplexityRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
                maxTokens = session.maxTokens,
                disableSearch = true, // Для summary отключаем поиск
                temperature = null
            )
            Vendor.GIGACHAT -> gigaChatRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
                maxTokens = session.maxTokens,
                disableSearch = true,
                temperature = null
            )
            Vendor.HUGGINGFACE -> huggingFaceRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
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
            
            // Ищем системный промпт
            val lastSystemPromptIndex = session.messages.indexOfFirst {
                it.role == Message.ROLE_SYSTEM
            }
            
            if (lastSystemPromptIndex >= 0) {
                // Объединяем summary с существующим системным промптом
                val existingSystemPrompt = session.messages[lastSystemPromptIndex]
                val updatedContent = "${existingSystemPrompt.content}\n\n[COMPRESSED_HISTORY] $summaryContent"
                session.messages[lastSystemPromptIndex] = existingSystemPrompt.copy(content = updatedContent)
            } else {
                // Если системного промпта нет, добавляем summary как новое сообщение
                val summaryMessage = Message(
                    role = Message.ROLE_SYSTEM,
                    content = "[COMPRESSED_HISTORY] $summaryContent"
                )
                session.messages.add(0, summaryMessage)
            }
            
            logger.info("Компрессия истории диалога завершена: удалено ${indicesToRemove.size} сообщений, создан summary длиной ${summaryContent.length}")
        }.onFailure { error ->
            logger.error("Ошибка компрессии истории диалога: ${error.message}", error)
        }
    }
    
    /**
     * Сжимает историю диалога по количеству токенов, создавая summary из сообщений до достижения порога
     * 
     * @param session сессия диалога
     * @param vendor вендор для компрессии (из запроса)
     * @param model модель для компрессии (из запроса)
     * @param compressionTokensThreshold порог количества токенов для сжатия
     * @return Result с успешным результатом или ошибкой
     */
    suspend fun compressByTokens(
        session: DialogSession, 
        vendor: Vendor,
        model: String,
        compressionTokensThreshold: Int
    ): Result<Unit> {
        // Для компрессии по токенам используем ту же логику, что и по сообщениям
        // Берем только user и assistant сообщения (не системные и не сжатые)
        val userAndAssistantMessages = session.messages.filter { 
            (it.role == Message.ROLE_USER || it.role == Message.ROLE_ASSISTANT) &&
            !it.content.startsWith("[COMPRESSED_HISTORY]")
        }
        
        if (userAndAssistantMessages.isEmpty()) {
            return Result.success(Unit)
        }
        
        // Берем сообщения для сжатия (примерно половину, чтобы не сжимать все сразу)
        // Можно взять все сообщения кроме последних двух (user + assistant)
        val messagesToCompress = if (userAndAssistantMessages.size > 2) {
            userAndAssistantMessages.dropLast(2)
        } else {
            return Result.success(Unit) // Не сжимаем, если слишком мало сообщений
        }
        
        if (messagesToCompress.isEmpty()) {
            return Result.success(Unit)
        }
        
        logger.info("Компрессия истории диалога по токенам: сжимаем ${messagesToCompress.size} сообщений, vendor=$vendor, модель=$model, accumulatedTokens=${session.accumulatedTotalTokens}")
        
        // Создаем промпт для summary
        val summaryPrompt = buildSummaryPrompt(messagesToCompress)
        
        // Строим сообщения для запроса summary
        val messagesForSummary = buildMessagesForSummary(session, summaryPrompt)
        
        // Отправляем запрос на создание summary используя vendor и model из запроса
        val result = when (vendor) {
            Vendor.PERPLEXITY -> perplexityRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
                maxTokens = session.maxTokens,
                disableSearch = true, // Для summary отключаем поиск
                temperature = null
            )
            Vendor.GIGACHAT -> gigaChatRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
                maxTokens = session.maxTokens,
                disableSearch = true,
                temperature = null
            )
            Vendor.HUGGINGFACE -> huggingFaceRepository.sendMessage(
                messages = messagesForSummary,
                model = model,
                maxTokens = session.maxTokens,
                disableSearch = true,
                temperature = null
            )
        }
        
        return result.map { (summaryContent, _, usage) ->
            // Вычитаем токены, потраченные на компрессию, из накопленных токенов
            // Также вычитаем приблизительное количество токенов, которое было в сжатых сообщениях
            // Для простоты вычитаем порог компрессии, так как мы сжали сообщения, которые привели к превышению порога
            usage?.totalTokens?.let { compressionTokens ->
                // Вычитаем токены, потраченные на компрессию
                session.accumulatedTotalTokens = (session.accumulatedTotalTokens - compressionTokens).coerceAtLeast(0)
                // Также вычитаем приблизительное количество токенов сжатых сообщений (примерно порог)
                // Это не идеально точно, но позволяет избежать бесконечной компрессии
                session.accumulatedTotalTokens = (session.accumulatedTotalTokens - compressionTokensThreshold / 2).coerceAtLeast(0)
            }
            
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
            
            // Ищем системный промпт
            val lastSystemPromptIndex = session.messages.indexOfFirst {
                it.role == Message.ROLE_SYSTEM
            }
            
            if (lastSystemPromptIndex >= 0) {
                // Объединяем summary с существующим системным промптом
                val existingSystemPrompt = session.messages[lastSystemPromptIndex]
                val updatedContent = "${existingSystemPrompt.content}\n\n[COMPRESSED_HISTORY] $summaryContent"
                session.messages[lastSystemPromptIndex] = existingSystemPrompt.copy(content = updatedContent)
            } else {
                // Если системного промпта нет, добавляем summary как новое сообщение
                val summaryMessage = Message(
                    role = Message.ROLE_SYSTEM,
                    content = "[COMPRESSED_HISTORY] $summaryContent"
                )
                session.messages.add(0, summaryMessage)
            }
            
            logger.info("Компрессия истории диалога по токенам завершена: удалено ${indicesToRemove.size} сообщений, создан summary длиной ${summaryContent.length}")
        }.onFailure { error ->
            logger.error("Ошибка компрессии истории диалога по токенам: ${error.message}", error)
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
