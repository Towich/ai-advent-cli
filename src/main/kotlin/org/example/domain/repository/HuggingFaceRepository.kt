package org.example.domain.repository

import org.example.domain.model.Message
import org.example.domain.model.TokenUsage

/**
 * Интерфейс репозитория для работы с Hugging Face API
 */
interface HuggingFaceRepository {
    /**
     * Отправить сообщение в Hugging Face API
     * 
     * @param messages список сообщений для отправки
     * @param model модель для использования
     * @param maxTokens максимальное количество токенов
     * @param disableSearch отключить поиск (игнорируется для Hugging Face)
     * @param temperature температура для генерации (0 <= x < 2), null если не указана
     * @return результат с содержимым ответа, моделью и информацией об использовании токенов
     */
    suspend fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        disableSearch: Boolean,
        temperature: Double? = null
    ): Result<Triple<String, String, TokenUsage?>>
    
    /**
     * Закрыть соединения
     */
    fun close()
}
