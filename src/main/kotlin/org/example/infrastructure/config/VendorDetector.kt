package org.example.infrastructure.config

/**
 * Утилита для определения вендора по модели
 */
enum class Vendor {
    PERPLEXITY,
    GIGACHAT,
    HUGGINGFACE
}

object VendorDetector {

    /**
     * Парсит строку vendor в enum Vendor
     *
     * @param vendor строка vendor ("perplexity", "gigachat" или "huggingface")
     * @return Vendor enum или null, если строка невалидна
     */
    fun parseVendor(vendor: String): Vendor? {
        return when (vendor.lowercase()) {
            "perplexity" -> Vendor.PERPLEXITY
            "gigachat" -> Vendor.GIGACHAT
            "huggingface" -> Vendor.HUGGINGFACE
            else -> null
        }
    }
    
    /**
     * Определяет vendor по имени модели
     * 
     * @param model имя модели
     * @return Vendor enum или null, если модель не распознана
     */
    fun detectVendorFromModel(model: String): Vendor? {
        val modelLower = model.lowercase()
        return when {
            modelLower.startsWith("sonar") || 
            modelLower.contains("perplexity") -> Vendor.PERPLEXITY
            modelLower.contains("gigachat") || 
            modelLower.startsWith("gpt") && modelLower.contains("giga") -> Vendor.GIGACHAT
            modelLower.contains("huggingface") || 
            modelLower.contains("meta-llama") ||
            modelLower.contains("mistral") -> Vendor.HUGGINGFACE
            else -> null
        }
    }
}
