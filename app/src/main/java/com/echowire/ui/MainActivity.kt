package com.echowire.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.echowire.R
import com.echowire.ml.EnrollmentRecorder
import com.echowire.service.EchoWireService
import android.os.CountDownTimer
import android.widget.ScrollView
import com.google.android.material.tabs.TabLayout
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main activity: dual-mode STT (Android / Percept) with split UI.
 * Upper half = STT detection (tab-specific). Lower half = network protocol (shared).
 */
class MainActivity : AppCompatActivity(), EchoWireService.ServiceListener {

    companion object {
        private const val MAX_LOG_LINES = 100
        private const val RECORD_AUDIO_REQUEST_CODE = 1001
    }

    // Shared header
    private lateinit var nameTextView: TextView
    private lateinit var connectionIndicator: TextView
    private var headerMdnsName = "EchoWire Service"
    private var headerPort = 0

    // Tabs
    private lateinit var sttTabLayout: TabLayout
    private lateinit var androidSttContent: LinearLayout
    private lateinit var perceptSttContent: LinearLayout
    private var currentTab = 0  // 0=Android, 1=Percept

    // Android tab views
    private lateinit var dbMeterView: DbMeterView
    private lateinit var languageEnButton: Button
    private lateinit var languageRuButton: Button
    private var currentLanguage = "en-US"

    // Percept tab views
    private lateinit var enrollmentStatusText: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var recordSampleButton: Button
    private var isRecording = false
    private var enrollmentCountdown: CountDownTimer? = null

    // Log + toggle
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var serviceToggleButton: Button

    // Service
    private var echoWireService: EchoWireService? = null
    private var serviceBound = false

    private val logLines = mutableListOf<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as EchoWireService.LocalBinder
            val svc = binder.getService()
            echoWireService = svc
            svc.setListener(this@MainActivity)
            serviceBound = true

            runOnUiThread {
                updateConnectionIndicator(svc.getClientCount())
                updateServiceButton(svc.isServiceRunning())
                if (svc.isServiceRunning())
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                headerMdnsName = svc.getConfigValue("name") ?: "EchoWire Service"
                headerPort = svc.getServerPort()
                updateHeader()

                // Sync language with service
                val lang = svc.getCurrentLanguage()
                if (lang != "auto") { currentLanguage = lang }
                updateLanguageButtons()

                val port = svc.getServerPort()
                if (port > 0) {
                    headerPort = port
                    updateHeader()
                    addLog("Service bound — port $port")
                }

                // Apply currently selected tab
                applyCurrentTab(svc)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            echoWireService?.setListener(null)
            echoWireService = null
            serviceBound = false
            runOnUiThread {
                updateConnectionIndicator(0)
                updateServiceButton(false)
                addLog("Service disconnected")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Header
        nameTextView        = findViewById(R.id.nameTextView)
        connectionIndicator = findViewById(R.id.connectionIndicator)

        // Tabs
        sttTabLayout        = findViewById(R.id.sttTabLayout)
        androidSttContent   = findViewById(R.id.androidSttContent)
        perceptSttContent   = findViewById(R.id.perceptSttContent)

        // Android tab
        dbMeterView         = findViewById(R.id.dbMeterView)
        languageEnButton    = findViewById(R.id.languageEnButton)
        languageRuButton    = findViewById(R.id.languageRuButton)

        // Percept tab
        enrollmentStatusText = findViewById(R.id.enrollmentStatusText)
        sampleCountText      = findViewById(R.id.sampleCountText)
        recordSampleButton   = findViewById(R.id.recordSampleButton)

        // Log + toggle
        logTextView         = findViewById(R.id.logTextView)
        logScrollView       = findViewById(R.id.logScrollView)
        serviceToggleButton = findViewById(R.id.serviceToggleButton)

        // Setup tabs
        sttTabLayout.addTab(sttTabLayout.newTab().setText(R.string.tab_android))
        sttTabLayout.addTab(sttTabLayout.newTab().setText(R.string.tab_percept))
        sttTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                echoWireService?.let { applyCurrentTab(it) }
                    ?: updateTabContent()  // just swap views if service not bound
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Button listeners
        serviceToggleButton.setOnClickListener {
            if (echoWireService?.isServiceRunning() == true) stopService() else startService()
        }
        languageEnButton.setOnClickListener { setLanguage("en-US") }
        languageRuButton.setOnClickListener { setLanguage("ru-RU") }
        recordSampleButton.setOnClickListener { startEnrollmentRecording() }

        updateConnectionIndicator(0)
        updateServiceButton(false)
        updateLanguageButtons()
        addLog("Activity created")
    }

    // Switch service backend + update UI for current tab
    private fun applyCurrentTab(svc: EchoWireService) {
        updateTabContent()
        when (currentTab) {
            0 -> svc.switchToAndroidStt(currentLanguage)
            1 -> {
                svc.switchToPercept()
                updateEnrollmentUi(svc)
            }
        }
    }

    // Show/hide tab-specific content panels
    private fun updateTabContent() {
        androidSttContent.visibility  = if (currentTab == 0) View.VISIBLE else View.GONE
        perceptSttContent.visibility  = if (currentTab == 1) View.VISIBLE else View.GONE
    }

    private fun updateEnrollmentUi(svc: EchoWireService) {
        val profile = svc.ownerProfile
        val count   = profile.getSampleCount()
        val loaded  = profile.meanEmbedding != null && count == 0  // restored from disk
        val ready   = profile.isReady() || loaded

        enrollmentStatusText.text = if (ready) getString(R.string.enrollment_ready)
                                    else getString(R.string.enrollment_not_ready)
        sampleCountText.text = when {
            loaded -> getString(R.string.sample_count_loaded)
            else   -> getString(R.string.sample_count_format, count)
        }
    }

    // Enrollment recording (mic exclusive — stops Percept first)
    private fun startEnrollmentRecording() {
        if (isRecording) return
        val svc = echoWireService ?: run { addLog("Service not running"); return }
        val durationMs = EnrollmentRecorder.DURATION_MS.toLong()

        isRecording = true
        recordSampleButton.isEnabled = false
        svc.stopCurrentBackend()  // release mic before AudioRecord

        // Countdown: update button text every second
        enrollmentCountdown = object : CountDownTimer(durationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000) + 1
                runOnUiThread { recordSampleButton.text = "● ${secs}s" }
            }
            override fun onFinish() {
                runOnUiThread { recordSampleButton.text = getString(R.string.recording_in_progress) }
            }
        }.start()

        EnrollmentRecorder().record(
            onComplete = { pcm ->
                enrollmentCountdown?.cancel()
                enrollmentCountdown = null
                svc.ownerProfile.addSample(pcm, 16000)
                svc.saveOwnerProfile()
                svc.switchToPercept()  // restart backend
                runOnUiThread {
                    isRecording = false
                    recordSampleButton.isEnabled = true
                    recordSampleButton.text = getString(R.string.record_sample)
                    updateEnrollmentUi(svc)
                    addLog("Sample recorded (${svc.ownerProfile.getSampleCount()} total)")
                }
            },
            onError = { e ->
                enrollmentCountdown?.cancel()
                enrollmentCountdown = null
                svc.switchToPercept()  // restart even on error
                runOnUiThread {
                    isRecording = false
                    recordSampleButton.isEnabled = true
                    recordSampleButton.text = getString(R.string.record_sample)
                    addLog("ERROR: Recording failed: ${e.message}")
                }
            }
        )
    }

    private fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        updateLanguageButtons()
        echoWireService?.setLanguage(languageCode)
            ?: addLog("Language set to $languageCode (applies when service starts)")
    }

    private fun updateLanguageButtons() {
        val selectedColor = ContextCompat.getColor(this, R.color.connected_green)
        val defaultColor  = ContextCompat.getColor(this, android.R.color.darker_gray)
        when (currentLanguage) {
            "en-US" -> { languageEnButton.setBackgroundColor(selectedColor); languageRuButton.setBackgroundColor(defaultColor) }
            "ru-RU" -> { languageEnButton.setBackgroundColor(defaultColor);  languageRuButton.setBackgroundColor(selectedColor) }
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
            addLog("Requesting microphone permission…")
            return
        }
        startServiceInternal()
    }

    private fun startServiceInternal() {
        val intent = Intent(this, EchoWireService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, 0)
        addLog("Starting service…")
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
            updateServiceButton(false)
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

    private fun updateServiceButton(serviceRunning: Boolean) {
        serviceToggleButton.text = getString(
            if (serviceRunning) R.string.stop_service else R.string.start_service
        )
    }

    private fun updateHeader() {
        val ip = getDeviceIpAddress()
        nameTextView.text = if (ip != null && headerPort > 0)
            "mDNS: $headerMdnsName  ·  ws://$ip:$headerPort"
        else
            "mDNS: $headerMdnsName"
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
        val entry = "[${dateFormat.format(Date())}] $message"
        synchronized(logLines) {
            logLines.add(entry)
            if (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
        runOnUiThread {
            logTextView.text = logLines.joinToString("\n")
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // === ServiceListener ===

    override fun onServiceStarted(port: Int) {
        runOnUiThread {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateServiceButton(true)
            headerPort = port
            updateHeader()
            addLog("Service started on port $port")
        }
    }

    override fun onServiceStopped() {
        runOnUiThread {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateServiceButton(false)
            updateConnectionIndicator(0)
            headerPort = 0
            updateHeader()
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
        runOnUiThread { addLog("ERROR: ${exception?.message ?: message}") }
    }

    override fun onConfigChanged(key: String, value: String?) {
        runOnUiThread {
            if (key == "name") { headerMdnsName = value ?: "EchoWire Service"; updateHeader() }
            addLog("Config: $key = $value")
        }
    }

    override fun onAudioLevelChanged(rmsDb: Float) {
        // rmsDb is real dBFS from AudioLevelMonitor — pass directly
        dbMeterView.addDb(rmsDb)
    }

    override fun onListeningStateChanged(listening: Boolean) {
        runOnUiThread {
            if (listening) {
                addLog("Listening")
            } else {
                addLog("Stopped listening")
                dbMeterView.clear()
            }
        }
    }

    override fun onPartialResult(text: String) {
        runOnUiThread { addLog("… $text") }
    }

    override fun onFinalResult(text: String, confidence: Float, language: String, sentenceType: String?) {
        runOnUiThread {
            val typeTag = sentenceType?.let { " [$it]" } ?: ""
            addLog("[$language]$typeTag \"$text\" (${"%.0f".format(confidence * 100)}%)")
        }
    }

    override fun onBackendChanged(backendName: String) {
        runOnUiThread { addLog("Backend: $backendName") }
    }
}
