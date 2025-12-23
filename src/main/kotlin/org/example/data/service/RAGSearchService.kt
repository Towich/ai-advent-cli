package org.example.data.service

import org.example.domain.model.DocumentChunk
import org.example.domain.repository.OllamaRepository
import org.slf4j.LoggerFactory

/**
 * Сервис для поиска релевантных чанков документов (RAG - Retrieval-Augmented Generation)
 */
class RAGSearchService(
    private val indexService: IndexService,
    private val ollamaRepository: OllamaRepository,
    private val embeddingModel: String = "nomic-embed-text",
    private val defaultTopK: Int = 5 // Количество наиболее релевантных чанков по умолчанию
) {
    private val logger = LoggerFactory.getLogger(RAGSearchService::class.java)
    
    /**
     * Результат поиска релевантных чанков
     */
    data class SearchResult(
        val chunks: List<DocumentChunk>,
        val query: String,
        val scores: List<Float>
    )
    
    /**
     * Ищет релевантные чанки для заданного вопроса
     * @param query вопрос пользователя
     * @param topK количество наиболее релевантных чанков для возврата
     * @return результат поиска с отсортированными чанками
     */
    suspend fun searchRelevantChunks(query: String, topK: Int = defaultTopK): Result<SearchResult> {
        return try {
            logger.info("Поиск релевантных чанков для запроса: $query (topK=$topK)")
            
            // Загружаем индекс
            val allChunks = indexService.loadIndex()
            if (allChunks.isFailure) {
                return Result.failure(allChunks.exceptionOrNull() ?: Exception("Ошибка загрузки индекса"))
            }
            
            val chunks = allChunks.getOrNull() ?: emptyList()
            if (chunks.isEmpty()) {
                logger.warn("Индекс пуст или не найден")
                return Result.success(SearchResult(emptyList(), query, emptyList()))
            }
            
            logger.info("Загружено ${chunks.size} чанков из индекса")
            
            // Генерируем эмбеддинг для запроса
            logger.info("Генерация эмбеддинга для запроса (длина: ${query.length} символов, модель: $embeddingModel)")
            val queryEmbeddingResult = ollamaRepository.generateEmbedding(query, embeddingModel)
            if (queryEmbeddingResult.isFailure) {
                val error = queryEmbeddingResult.exceptionOrNull()
                logger.error("Ошибка генерации эмбеддинга для запроса: ${error?.message}", error)
                return Result.failure(
                    error ?: Exception("Ошибка генерации эмбеддинга для запроса")
                )
            }
            
            val queryEmbedding = queryEmbeddingResult.getOrNull()
            if (queryEmbedding == null) {
                logger.error("Получен null эмбеддинг для запроса после успешного вызова generateEmbedding")
                return Result.failure(
                    Exception("Пустой эмбеддинг для запроса")
                )
            }
            
            if (queryEmbedding.isEmpty()) {
                logger.error("Получен пустой список эмбеддинга (размер: 0)")
                return Result.failure(
                    Exception("Пустой эмбеддинг для запроса (размер: 0)")
                )
            }
            
            logger.info("Эмбеддинг запроса сгенерирован успешно, размерность: ${queryEmbedding.size}")
            
            // Вычисляем косинусное сходство для каждого чанка
            val similarities = chunks.map { chunk ->
                val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
                Pair(chunk, similarity)
            }
            
            // Сортируем по убыванию сходства и берем topK
            val topChunks = similarities
                .sortedByDescending { it.second }
                .take(topK)
            
            val resultChunks = topChunks.map { it.first }
            val resultScores = topChunks.map { it.second }
            
            logger.info("Найдено ${resultChunks.size} релевантных чанков (топ-$topK)")
            resultChunks.forEachIndexed { index, chunk ->
                val score = resultScores[index]
                val scorePercent = (score * 100).toInt()
                logger.info("Чанк ${index + 1}: ${chunk.filePath} (сходство: ${String.format("%.3f", score)} / $scorePercent%)")
                logger.debug("  Содержимое (первые 100 символов): ${chunk.content.take(100)}...")
            }
            
            // Логируем статистику сходства
            if (resultScores.isNotEmpty()) {
                val avgScore = resultScores.average().toFloat()
                val maxScore = resultScores.maxOrNull() ?: 0f
                val minScore = resultScores.minOrNull() ?: 0f
                logger.info("Статистика сходства: мин=${String.format("%.3f", minScore)}, макс=${String.format("%.3f", maxScore)}, среднее=${String.format("%.3f", avgScore)}")
                
                // Предупреждение, если все значения низкие
                if (maxScore < 0.3f) {
                    logger.warn("⚠️ Все значения сходства < 0.3. Возможные причины:")
                    logger.warn("  - Разные модели эмбеддингов для индексации и поиска")
                    logger.warn("  - Запрос на другом языке, чем документы")
                    logger.warn("  - Плохая модель эмбеддингов")
                }
            }
            
            Result.success(SearchResult(resultChunks, query, resultScores))
        } catch (e: Exception) {
            logger.error("Ошибка при поиске релевантных чанков: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Вычисляет косинусное сходство между двумя векторами
     * @param vec1 первый вектор
     * @param vec2 второй вектор
     * @return косинусное сходство (от -1 до 1, где 1 - максимальное сходство)
     */
    private fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
        if (vec1.size != vec2.size) {
            throw IllegalArgumentException("Векторы должны иметь одинаковую размерность")
        }
        
        var dotProduct = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f
        
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        
        val denominator = kotlin.math.sqrt(norm1) * kotlin.math.sqrt(norm2)
        return if (denominator == 0.0f) 0.0f else dotProduct / denominator
    }
    
    /**
     * Форматирует найденные чанки в контекст для LLM
     * @param searchResult результат поиска
     * @return отформатированный контекст для включения в промпт
     */
    fun formatChunksAsContext(searchResult: SearchResult): String {
        if (searchResult.chunks.isEmpty()) {
            return "Релевантные документы не найдены."
        }
        
        return buildString {
            append("Релевантные фрагменты документов:\n\n")
            searchResult.chunks.forEachIndexed { index, chunk ->
                append("--- Фрагмент ${index + 1} (из файла: ${chunk.filePath}, сходство: ${String.format("%.3f", searchResult.scores[index])}) ---\n")
                append(chunk.content)
                append("\n\n")
            }
        }
    }
}
