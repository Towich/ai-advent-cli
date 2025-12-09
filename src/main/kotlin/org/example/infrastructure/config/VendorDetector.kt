package org.example.infrastructure.config

import org.example.infrastructure.config.AppConfig.GIGACHAT_MODELS
import org.example.infrastructure.config.AppConfig.PERPLEXITY_MODELS

/**
 * Утилита для определения вендора по модели
 */
enum class Vendor {
    PERPLEXITY,
    GIGACHAT
}

object VendorDetector {
    
    /**
     * Определяет вендора по имени модели
     * 
     * @param model имя модели
     * @return вендор (PERPLEXITY или GIGACHAT)
     */
    fun detectVendor(model: String): Vendor {
        val modelLower = model.lowercase()
        return when {
            PERPLEXITY_MODELS.any { modelLower.contains(it) } -> Vendor.PERPLEXITY
            GIGACHAT_MODELS.any { modelLower.contains(it.lowercase()) } -> Vendor.GIGACHAT
            else -> Vendor.PERPLEXITY // По умолчанию для обратной совместимости
        }
    }
}
