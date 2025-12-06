package org.example.domain.repository

import org.example.domain.model.DialogSession
import org.example.domain.model.Message

/**
 * Интерфейс репозитория для работы с Perplexity API
 */
interface PerplexityRepository {
    /**
     * Отправить сообщение в Perplexity API
     * 
     * @param messages список сообщений для отправки
     * @param model модель для использования
     * @param maxTokens максимальное количество токенов
     * @param disableSearch отключить поиск
     * @return результат с содержимым ответа и моделью
     */
    suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean
    ): Result<Pair<String, String>>
    
    /**
     * Закрыть соединения
     */
    fun close()
}


