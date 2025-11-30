package com.uh

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main activity for UH application.
 * Displays connection status, event log, and service control buttons.
 * Binds to UhService for real-time updates.
 */
class MainActivity : AppCompatActivity(), UhService.ServiceListener {

    companion object {
        private const val MAX_LOG_LINES = 100
        private const val RECORD_AUDIO_REQUEST_CODE = 1001
    }

    private lateinit var nameTextView: TextView
    private lateinit var connectionIndicator: TextView
    private lateinit var logTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var waveformView: com.uh.ui.WaveformView

    private var uhService: UhService? = null
    private var serviceBound: Boolean = false

    private val logLines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as UhService.LocalBinder
            uhService = binder.getService()
            uhService?.setListener(this@MainActivity)
            serviceBound = true
            
            // Update UI with current state
            runOnUiThread {
                updateConnectionIndicator(uhService?.getClientCount() ?: 0)
                updateButtons(uhService?.isServiceRunning() ?: false)
                
                // Read current configuration
                val currentName = uhService?.getConfigValue("name") ?: "UH Service"
                nameTextView.text = currentName
                
                val port = uhService?.getServerPort() ?: 0
                if (port > 0) {
                    addLog("Service bound - port $port")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            uhService?.setListener(null)
            uhService = null
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
        connectionIndicator = findViewById(R.id.connectionIndicator)
        logTextView = findViewById(R.id.logTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        waveformView = findViewById(R.id.waveformView)

        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }

        updateConnectionIndicator(0)
        updateButtons(false)
        addLog("Activity created")
    }

    override fun onStart() {
        super.onStart()
        // Try to bind to service if it's running
        val intent = Intent(this, UhService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            uhService?.setListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startService() {
        // Check for RECORD_AUDIO permission before starting service
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            // Request permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_REQUEST_CODE
            )
            addLog("Requesting microphone permission...")
            return
        }
        
        // Permission already granted, start service
        startServiceInternal()
    }
    
    private fun startServiceInternal() {
        val intent = Intent(this, UhService::class.java)
        ContextCompat.startForegroundService(this, intent)
        
        // Bind to service to receive updates
        bindService(intent, serviceConnection, 0)
        
        addLog("Starting service...")
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, 
        permissions: Array<String>, 
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                addLog("Microphone permission granted")
                startServiceInternal()
            } else {
                addLog("ERROR: Microphone permission denied - cannot function without microphone")
            }
        }
    }

    private fun stopService() {
        val intent = Intent(this, UhService::class.java)
        stopService(intent)
        
        if (serviceBound) {
            uhService?.setListener(null)
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
            connectionIndicator.setBackgroundColor(
                ContextCompat.getColor(this, R.color.connected_green)
            )
        } else {
            connectionIndicator.text = getString(R.string.not_connected)
            connectionIndicator.setBackgroundColor(
                ContextCompat.getColor(this, R.color.disconnected_red)
            )
        }
    }

    private fun updateButtons(serviceRunning: Boolean) {
        startButton.isEnabled = !serviceRunning
        stopButton.isEnabled = serviceRunning
    }

    private fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"
        
        synchronized(logLines) {
            logLines.add(logEntry)
            if (logLines.size > MAX_LOG_LINES) {
                logLines.removeAt(0)
            }
        }
        
        runOnUiThread {
            logTextView.text = logLines.joinToString("\n")
            // Auto-scroll to bottom
            val scrollView = logTextView.parent as? android.widget.ScrollView
            scrollView?.post {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // ServiceListener implementation - called from background threads
    
    override fun onServiceStarted(port: Int) {
        runOnUiThread {
            updateButtons(true)
            addLog("Service started on port $port")
        }
    }

    override fun onServiceStopped() {
        runOnUiThread {
            updateButtons(false)
            updateConnectionIndicator(0)
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
            val errorMsg = if (exception != null) {
                "$message: ${exception.message}"
            } else {
                message
            }
            addLog("ERROR: $errorMsg")
            // Set waveform to red on error
            waveformView.setState(com.uh.ui.WaveformView.State.ERROR)
        }
    }

    override fun onConfigChanged(key: String, value: String?) {
        runOnUiThread {
            when (key) {
                "name" -> {
                    nameTextView.text = value ?: "UH Service"
                    addLog("Config updated: name = $value")
                }
                else -> {
                    addLog("Config updated: $key = $value")
                }
            }
        }
    }
    
    // Model management callbacks
    
    override fun onModelDownloadProgress(modelName: String, progress: Float, downloaded: Long, total: Long) {
        runOnUiThread {
            val downloadedMB = downloaded / (1024 * 1024)
            if (total > 0) {
                // Known file size - show percentage and totals
                val totalMB = total / (1024 * 1024)
                val percentage = (progress * 100).toInt()
                addLog("Extracting $modelName: $percentage% ($downloadedMB MB / $totalMB MB)")
            } else {
                // Compressed file - show bytes extracted only
                addLog("Extracting $modelName: $downloadedMB MB (compressed)")
            }
        }
    }
    
    override fun onModelLoading(status: String) {
        runOnUiThread {
            addLog(status)
        }
    }
    
    override fun onModelLoaded(status: String) {
        runOnUiThread {
            addLog(status)
        }
    }
    
    // Audio capture callbacks
    
    override fun onAudioLevelChanged(level: Float) {
        // Update waveform with audio level
        waveformView.addSample(level)
    }
    
    override fun onListeningStateChanged(listening: Boolean) {
        runOnUiThread {
            if (listening) {
                addLog("Listening started - microphone active")
                waveformView.setState(com.uh.ui.WaveformView.State.IDLE)
            } else {
                addLog("Listening stopped")
                waveformView.clear()
            }
        }
    }
    
    // Speech recognition callbacks
    
    override fun onTranscriptionReceived(text: String, language: String?, processingTimeMs: Long) {
        runOnUiThread {
            val langLabel = language?.let { "[$it]" } ?: ""
            addLog("Speech $langLabel (${processingTimeMs}ms): \"$text\"")
            // Return to idle state after transcription completes
            waveformView.setState(com.uh.ui.WaveformView.State.IDLE)
        }
    }
    
    override fun onProcessingStarted() {
        runOnUiThread {
            // Set waveform to yellow during recognition
            waveformView.setState(com.uh.ui.WaveformView.State.RECOGNIZING)
        }
    }
}
