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
}
