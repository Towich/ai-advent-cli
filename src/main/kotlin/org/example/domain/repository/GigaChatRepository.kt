package org.example.domain.repository

import org.example.domain.model.Message

/**
 * Интерфейс репозитория для работы с GigaChat API
 */
interface GigaChatRepository {
    /**
     * Отправить сообщение в GigaChat API
     * 
     * @param messages список сообщений для отправки
     * @param model модель для использования
     * @param maxTokens максимальное количество токенов
     * @param disableSearch отключить поиск (игнорируется для GigaChat)
     * @param temperature температура для генерации (0 <= x < 2), null если не указана
     * @return результат с содержимым ответа и моделью
     */
    suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        temperature: Double? = null
    ): Result<Pair<String, String>>
    
    /**
     * Закрыть соединения
     */
    fun close()
}
