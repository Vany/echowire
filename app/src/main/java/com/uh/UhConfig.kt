package com.uh

/**
 * Configuration for UH service.
 * Immutable data class holding all service parameters.
 * Future: expose via UI, load from preferences/file.
 */
data class UhConfig(
    val mdnsServiceType: String = "_uh._tcp.local.",
    val mdnsServiceName: String = "UH_Service",
    val websocketStartPort: Int = 8080,
    val websocketMaxPortSearch: Int = 100,
    val randomNumberIntervalMs: Long = 1000,
    val pingIntervalMs: Long = 5000
) {
    companion object {
        /**
         * Default configuration instance.
         * Used when no custom configuration is provided.
         */
        val DEFAULT = UhConfig()
    }
}
