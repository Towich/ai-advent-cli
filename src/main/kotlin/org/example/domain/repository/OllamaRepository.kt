package org.example.domain.repository

/**
 * Репозиторий для работы с Ollama API для генерации эмбеддингов
 */
interface OllamaRepository {
    /**
     * Генерирует эмбеддинг для одного текста
     * @param text текст для генерации эмбеддинга
     * @param model модель для генерации эмбеддингов (по умолчанию nomic-embed-text)
     * @return вектор эмбеддинга или ошибка
     */
    suspend fun generateEmbedding(text: String, model: String = "nomic-embed-text"): Result<List<Float>>

    /**
     * Генерирует эмбеддинги для батча текстов
     * @param texts список текстов для генерации эмбеддингов
     * @param model модель для генерации эмбеддингов (по умолчанию nomic-embed-text)
     * @return список векторов эмбеддингов или ошибка
     */
    suspend fun generateEmbeddings(texts: List<String>, model: String = "nomic-embed-text"): Result<List<List<Float>>>

    /**
     * Закрывает соединение
     */
    fun close()
}

