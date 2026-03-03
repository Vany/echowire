package com.echowire.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.echowire.R
import com.echowire.service.EchoWireService
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main activity: service control, real-time status, event log.
 */
class MainActivity : AppCompatActivity(), EchoWireService.ServiceListener {

    companion object {
        private const val MAX_LOG_LINES = 100
        private const val RECORD_AUDIO_REQUEST_CODE = 1001
    }

    private lateinit var nameTextView: TextView
    private lateinit var ipAddressTextView: TextView
    private lateinit var connectionIndicator: TextView
    private lateinit var logTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var languageEnButton: Button
    private lateinit var languageRuButton: Button
    private lateinit var waveformView: WaveformView
    private lateinit var dbMeterView: DbMeterView

    private var currentLanguage = "en-US"

    private var echoWireService: EchoWireService? = null
    private var serviceBound = false

    private val logLines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as EchoWireService.LocalBinder
            echoWireService = binder.getService()
            echoWireService?.setListener(this@MainActivity)
            serviceBound = true

            runOnUiThread {
                updateConnectionIndicator(echoWireService?.getClientCount() ?: 0)
                updateButtons(echoWireService?.isServiceRunning() ?: false)
                nameTextView.text = echoWireService?.getConfigValue("name") ?: "EchoWire Service"

                // Sync language with service
                currentLanguage = echoWireService?.getCurrentLanguage() ?: "en-US"
                updateLanguageButtons()

                val port = echoWireService?.getServerPort() ?: 0
                if (port > 0) {
                    updateIpAddress(port)
                    addLog("Service bound - port $port")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            echoWireService?.setListener(null)
            echoWireService = null
            serviceBound = false
            runOnUiThread {
                updateConnectionIndicator(0)
                updateButtons(false)
                addLog("Service disconnected")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nameTextView = findViewById(R.id.nameTextView)
        ipAddressTextView = findViewById(R.id.ipAddressTextView)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        logTextView = findViewById(R.id.logTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        languageEnButton = findViewById(R.id.languageEnButton)
        languageRuButton = findViewById(R.id.languageRuButton)
        waveformView = findViewById(R.id.waveformView)
        dbMeterView = findViewById(R.id.dbMeterView)

        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }
        languageEnButton.setOnClickListener { setLanguage("en-US") }
        languageRuButton.setOnClickListener { setLanguage("ru-RU") }

        updateConnectionIndicator(0)
        updateButtons(false)
        updateLanguageButtons()
        addLog("Activity created")
    }

    private fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        updateLanguageButtons()

        echoWireService?.let { service ->
            // Send language change command to service via RuntimeConfig
            val command = "{\"command\":\"set_config\",\"key\":\"language\",\"value\":\"$languageCode\"}"
            addLog("Setting language to $languageCode")
            // The service will handle this through its WebSocket command handler
            // For now, we'll use a direct method call
            changeServiceLanguage(languageCode)
        } ?: run {
            addLog("Language set to $languageCode (will apply when service starts)")
        }
    }

    private fun changeServiceLanguage(languageCode: String) {
        echoWireService?.setLanguage(languageCode)
    }

    private fun updateLanguageButtons() {
        // Visual feedback: highlight selected language
        val selectedColor = ContextCompat.getColor(this, R.color.connected_green)
        val defaultColor = ContextCompat.getColor(this, android.R.color.darker_gray)

        when (currentLanguage) {
            "en-US" -> {
                languageEnButton.setBackgroundColor(selectedColor)
                languageRuButton.setBackgroundColor(defaultColor)
            }

            "ru-RU" -> {
                languageEnButton.setBackgroundColor(defaultColor)
                languageRuButton.setBackgroundColor(selectedColor)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, EchoWireService::class.java), serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            echoWireService?.setListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE
            )
            addLog("Requesting microphone permission...")
            return
        }
        startServiceInternal()
    }

    private fun startServiceInternal() {
        val intent = Intent(this, EchoWireService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, 0)
        addLog("Starting service...")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                addLog("Microphone permission granted")
                startServiceInternal()
            } else {
                addLog("ERROR: Microphone permission denied")
            }
        }
    }

    private fun stopService() {
        stopService(Intent(this, EchoWireService::class.java))
        if (serviceBound) {
            echoWireService?.setListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        runOnUiThread {
            updateConnectionIndicator(0)
            updateButtons(false)
            addLog("Service stopped")
        }
    }

    private fun updateConnectionIndicator(clientCount: Int) {
        if (clientCount > 0) {
            connectionIndicator.text = getString(R.string.connected_indicator, clientCount)
            connectionIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.connected_green))
        } else {
            connectionIndicator.text = getString(R.string.not_connected)
            connectionIndicator.setBackgroundColor(ContextCompat.getColor(this, R.color.disconnected_red))
        }
    }

    private fun updateButtons(serviceRunning: Boolean) {
        startButton.isEnabled = !serviceRunning
        stopButton.isEnabled = serviceRunning
    }

    private fun updateIpAddress(port: Int) {
        val ip = getDeviceIpAddress()
        if (ip != null && port > 0) {
            ipAddressTextView.text = getString(R.string.ip_address_format, ip, port)
        } else {
            ipAddressTextView.text = getString(R.string.ip_address_unknown)
        }
    }

    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            addLog("Failed to get IP: ${e.message}")
        }
        return null
    }

    private fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $message"
        synchronized(logLines) {
            logLines.add(entry)
            if (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
        runOnUiThread {
            logTextView.text = logLines.joinToString("\n")
            (logTextView.parent as? android.widget.ScrollView)?.post {
                (logTextView.parent as android.widget.ScrollView).fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // === ServiceListener ===

    override fun onServiceStarted(port: Int) {
        runOnUiThread {
            updateButtons(true)
            updateIpAddress(port)
            addLog("Service started on port $port")
        }
    }

    override fun onServiceStopped() {
        runOnUiThread {
            updateButtons(false)
            updateConnectionIndicator(0)
            ipAddressTextView.text = getString(R.string.ip_address_unknown)
            addLog("Service stopped")
        }
    }

    override fun onClientConnected(address: String, totalClients: Int) {
        runOnUiThread {
            updateConnectionIndicator(totalClients)
            addLog("Client connected: $address (total: $totalClients)")
        }
    }

    override fun onClientDisconnected(address: String, totalClients: Int) {
        runOnUiThread {
            updateConnectionIndicator(totalClients)
            addLog("Client disconnected: $address (total: $totalClients)")
        }
    }

    override fun onError(message: String, exception: Exception?) {
        runOnUiThread {
            val msg = if (exception != null) "$message: ${exception.message}" else message
            addLog("ERROR: $msg")
            waveformView.setState(WaveformView.State.ERROR)
        }
    }

    override fun onConfigChanged(key: String, value: String?) {
        runOnUiThread {
            if (key == "name") nameTextView.text = value ?: "EchoWire Service"
            addLog("Config: $key = $value")
        }
    }

    override fun onAudioLevelChanged(rmsDb: Float) {
        // Convert dB to 0..1 range: Android STT rmsDb is typically -2 to 10
        val normalized = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        waveformView.addSample(normalized)
        dbMeterView.addSample(normalized)
    }

    override fun onListeningStateChanged(listening: Boolean) {
        runOnUiThread {
            if (listening) {
                addLog("Listening")
                waveformView.setState(WaveformView.State.IDLE)
            } else {
                addLog("Stopped listening")
                waveformView.clear()
                dbMeterView.clear()
            }
        }
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            addLog("... $text")
            waveformView.setState(WaveformView.State.RECOGNIZING)
        }
    }

    override fun onFinalResult(text: String, confidence: Float, language: String) {
        runOnUiThread {
            addLog("[$language] \"$text\" (${"%.0f".format(confidence * 100)}%)")
            waveformView.setState(WaveformView.State.IDLE)
        }
    }
}
