package com.echowire.config

/**
 * Configuration for EchoWire service.
 * Immutable data class holding all service parameters.
 * Future: expose via UI, load from preferences/file.
 */
data class EchoWireConfig(
    val mdnsServiceType: String = "_echowire._tcp.",
    val mdnsServiceName: String = "EchoWire_Service",
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
        val DEFAULT = EchoWireConfig()
    }
}
