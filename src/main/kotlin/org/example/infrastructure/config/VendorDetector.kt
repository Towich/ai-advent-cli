package org.example.infrastructure.config

/**
 * Утилита для определения вендора по модели
 */
enum class Vendor {
    PERPLEXITY,
    GIGACHAT
}

object VendorDetector {

    /**
     * Парсит строку vendor в enum Vendor
     *
     * @param vendor строка vendor ("perplexity" или "gigachat")
     * @return Vendor enum или null, если строка невалидна
     */
    fun parseVendor(vendor: String): Vendor? {
        return when (vendor.lowercase()) {
            "perplexity" -> Vendor.PERPLEXITY
            "gigachat" -> Vendor.GIGACHAT
            else -> null
        }
    }
}
