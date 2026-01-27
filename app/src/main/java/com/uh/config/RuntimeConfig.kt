package com.uh.config

import android.util.Log
import org.json.JSONObject

/**
 * Runtime configuration that can be modified during service execution.
 * Thread-safe configuration storage with change notification support.
 * Accessed via WebSocket configure messages.
 */
class RuntimeConfig {

    companion object {
        private const val TAG = "RuntimeConfig"
    }

    private val config = mutableMapOf<String, String>()
    private val lock = Any()
    private val listeners = mutableSetOf<ConfigChangeListener>()

    interface ConfigChangeListener {
        fun onConfigChanged(key: String, value: String?)
    }

    init {
        // Initialize default values
        synchronized(lock) {
            config["name"] = "EchoWire Service"
        }
    }

    /**
     * Set configuration value and notify listeners.
     * Returns the new value.
     */
    fun set(key: String, value: String): String {
        synchronized(lock) {
            config[key] = value
            Log.i(TAG, "Config set: $key = $value")
        }
        notifyListeners(key, value)
        return value
    }

    /**
     * Get configuration value.
     * Returns null if key doesn't exist.
     */
    fun get(key: String): String? {
        synchronized(lock) {
            return config[key]
        }
    }

    /**
     * Add listener for configuration changes.
     */
    fun addListener(listener: ConfigChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove listener for configuration changes.
     */
    fun removeListener(listener: ConfigChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Process configure message and return response.
     * Message format: { "configure": "key", "value": "optional" }
     * Response format: { "configure": "key", "value": "current_value" }
     * If value is provided, sets it first then returns the new value.
     */
    fun processConfigureMessage(message: String): String? {
        return try {
            val json = JSONObject(message)

            if (!json.has("configure")) {
                Log.w(TAG, "Invalid configure message: missing 'configure' field")
                return null
            }

            val key = json.getString("configure")

            // If value is provided, set it first
            if (json.has("value")) {
                val value = json.getString("value")
                set(key, value)
            }

            // Get current value
            val currentValue = get(key)

            if (currentValue == null) {
                Log.w(TAG, "Unknown config key: $key")
                return null
            }

            // Build response
            JSONObject().apply {
                put("configure", key)
                put("value", currentValue)
            }.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing configure message: $message", e)
            null
        }
    }

    private fun notifyListeners(key: String, value: String?) {
        val listenersCopy: List<ConfigChangeListener>
        synchronized(listeners) {
            listenersCopy = listeners.toList()
        }

        listenersCopy.forEach { listener ->
            try {
                listener.onConfigChanged(key, value)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }
}
