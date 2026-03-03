# EchoWire - Research & Development Knowledge Base

## Overview

This document captures research findings, technical decisions, and lessons learned during development of the EchoWire speech recognition service.

---

## Speech Recognition Approaches

### Approach 1: TensorFlow Lite Whisper (Deprecated)

**Implementation**: On-device Whisper model running via TensorFlow Lite

**Architecture**:
```
Microphone -> AudioCaptureManager (16kHz PCM)
           -> VAD (energy threshold)
           -> CircularAudioBuffer (1-30s)
           -> AudioPreprocessor (mel spectrogram)
           -> WhisperModel (TFLite inference)
           -> WhisperTokenizer (token -> text)
           -> EmbeddingManager (ONNX, 384-dim)
           -> WebSocket broadcast
```

**Pros**:
- Fully offline operation
- Privacy (audio never leaves device)
- Semantic embeddings included
- Automatic language detection

**Cons**:
- High latency (400-600ms)
- Large model files (66MB+)
- Complex pipeline (FFT, mel banks, tokenization)
- GPU delegate broken in TFLite 2.16-2.17
- No partial results
- No confidence scores

**Performance (Samsung Note20, Exynos 990)**:
- Latency: 400-600ms
- Real-time factor: 0.4-0.6x
- Memory: ~800MB

**Why Deprecated**: 
- Android STT provides better UX (partial results, confidence)
- Lower latency critical for real-time applications
- Complexity not justified without offline requirement

---

### Approach 2: Android Native STT (Current)

**Implementation**: Android SpeechRecognizer API wrapper

**Architecture**:
```
Microphone -> SpeechRecognizer (Android API)
           -> RecognitionListener callbacks
           -> WebSocket broadcast
```

**Pros**:
- Low latency (100-300ms)
- Partial results (real-time transcription)
- Confidence scores
- Multiple alternatives
- Simple implementation
- No model files needed
- Continuously improving (Google updates)

**Cons**:
- Requires internet (usually)
- No semantic embeddings
- No automatic language detection
- Privacy concerns (audio to Google)
- On-device recognition unreliable

**Performance**:
- Latency: 100-300ms
- Partial result frequency: 0.5-2 Hz
- Memory: < 200MB

---

## TensorFlow Lite GPU Delegate Issues

### Problem
TFLite GPU delegate fails on Android with various errors:
- `NoClassDefFoundError: GpuDelegateFactory$Options`
- Factory classes in source but not in Maven artifacts
- Affects TFLite 2.14, 2.15, 2.16, 2.17

### Attempted Solutions

**1. Direct GpuDelegate()**
```kotlin
val gpuDelegate = GpuDelegate()  // Crashes
```
Result: NoClassDefFoundError

**2. CompatibilityList API (TFLite 2.17)**
```kotlin
val compatList = CompatibilityList()
if (compatList.isDelegateSupportedOnThisDevice) {
    val options = compatList.bestOptionsForThisDevice
    val gpuDelegate = GpuDelegate(options)
}
```
Result: Still fails on some devices

**3. GPU Delegate Options Builder**
```kotlin
val options = GpuDelegate.Options()
    .setPrecisionLossAllowed(true)
    .setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
```
Result: Options class not found

### Working Fallback: XNNPack CPU
```kotlin
val options = Interpreter.Options()
options.setNumThreads(4)
options.setUseXNNPACK(true)  // 2-3x faster than default CPU
```
Performance: 400-600ms (acceptable)

### Conclusion
GPU acceleration on Android TFLite is unreliable. XNNPack provides sufficient performance for speech recognition. Monitor TFLite releases for fixes.

---

## Whisper Model Variants

### usefulsensors/openai-whisper (Used)
- **Format**: Single TFLite file
- **Output**: INT32 token IDs [1, 448]
- **Size**: 66MB (tiny multilingual)
- **Status**: Works, but language detection broken

**Language Detection Bug**:
- Outputs Russian (50263) for English speech
- Text transcription correct
- Language token position: index 1 in output
- Root cause unknown (model training or conversion issue)

### vilassn/whisper_android (Researched)
- **Format**: Separate encoder + decoder TFLite files
- **Architecture**: Encoder-decoder split for flexibility
- **Status**: Not implemented (complexity vs benefit)

**Encoder-Decoder Split**:
```
Audio -> Encoder -> Features [1, 1500, 384]
Features + Previous Tokens -> Decoder -> Next Token Logits
Loop until EOS or max_length
```

### Model Comparison
| Model | Size | Latency | Accuracy | Languages |
|-------|------|---------|----------|-----------|
| Whisper tiny | 66MB | 400-600ms | Good | 99 |
| Whisper base | 142MB | 800-1200ms | Better | 99 |
| Whisper small | 244MB | 1500-2500ms | Best | 99 |

---

## Audio Processing Pipeline

### Mel Spectrogram Generation

**Parameters (Whisper standard)**:
- Sample rate: 16000 Hz
- FFT size: 512 (faster) or 1024 (more accurate)
- Window: Hamming, 25ms (400 samples)
- Hop: 10ms (160 samples)
- Mel bins: 80
- Frequency range: 0-8000 Hz

**Implementation**:
```kotlin
// JTransforms for FFT
val fft = DoubleFFT_1D(fftSize)
fft.realForward(frame)

// Apply mel filter banks
for (mel in 0 until numMelBins) {
    var energy = 0.0
    for (freq in melFilterStart[mel] until melFilterEnd[mel]) {
        energy += magnitude[freq] * melFilterWeights[mel][freq]
    }
    melSpec[mel] = log(max(energy, 1e-10))
}

// Normalize (Whisper training constants)
melSpec = (melSpec - (-4.27)) / 4.57
```

**Performance**: ~1ms per 10ms audio frame

### Voice Activity Detection (VAD)

**Energy-based VAD**:
```kotlin
fun processFrame(samples: ShortArray): Boolean {
    val rms = sqrt(samples.map { it * it }.average())
    val normalized = rms / 32767.0
    return normalized > threshold  // Default: 0.02
}
```

**Improvements considered**:
- WebRTC VAD (more accurate, complex)
- Silero VAD (ML-based, adds latency)
- Multiple threshold levels (speech start/end hysteresis)

---

## WebSocket Implementation

### Library: Java-WebSocket 1.5.3

**Why chosen**:
- Pure Java, works on Android
- Simple API
- Multiple client support
- Ping/pong built-in

**Server setup**:
```kotlin
class UhWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // Send handshake
        sendHandshake(conn)
    }
    
    override fun onMessage(conn: WebSocket, message: String) {
        // Handle configure commands
    }
    
    fun broadcastMessage(message: String) {
        connections.forEach { it.send(message) }
    }
}
```

### Port Selection Strategy
```kotlin
fun findAvailablePort(startPort: Int = 8080): Int {
    for (port in startPort until startPort + 100) {
        try {
            ServerSocket(port).use { return port }
        } catch (e: Exception) {
            continue
        }
    }
    throw IOException("No available port")
}
```

**Known issue**: Race condition between check and bind (acceptable risk)

---

## mDNS Service Discovery

### Android NsdManager (Primary)
```kotlin
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "EchoWire Service"
    serviceType = "_uh._tcp"
    port = serverPort
}
nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
```

**Issues**:
- Inconsistent across Android versions
- Sometimes fails silently
- Multicast lock required

### jmDNS Library (Fallback)
```kotlin
val jmdns = JmDNS.create(InetAddress.getLocalHost())
val serviceInfo = ServiceInfo.create("_echowire._tcp.local.", "EchoWire Service", port, "")
jmdns.registerService(serviceInfo)
```

**Issues**:
- Requires multicast lock
- May conflict with NsdManager
- More reliable but heavier

### Multicast Lock
```kotlin
val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
val multicastLock = wifiManager.createMulticastLock("uh_mdns")
multicastLock.setReferenceCounted(true)
multicastLock.acquire()
// ... use mDNS ...
multicastLock.release()
```

---

## Android Foreground Service

### Service Declaration
```xml
<service
    android:name=".service.UhService"
    android:foregroundServiceType="microphone"
    android:exported="false" />
```

### Notification Channel (Android 8+)
```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,
    "EchoWire Service",
    NotificationManager.IMPORTANCE_LOW
)
notificationManager.createNotificationChannel(channel)
```

### Wake Lock
```kotlin
val powerManager = getSystemService(POWER_SERVICE) as PowerManager
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "echowire:service"
)
wakeLock.acquire()
```

**Caution**: Always release in onDestroy()

---

## Text Embeddings (Whisper Pipeline)

### Model: all-MiniLM-L6-v2

**Specifications**:
- Dimensions: 384
- Size: 86MB (ONNX format)
- Latency: 30-50ms per phrase

**ONNX Runtime Android**:
```kotlin
val env = OrtEnvironment.getEnvironment()
val session = env.createSession(modelPath)

val inputTensor = OnnxTensor.createTensor(env, tokenIds)
val results = session.run(mapOf("input_ids" to inputTensor))
val embeddings = results[0].value as Array<FloatArray>

// Mean pooling
val pooled = embeddings[0].mapIndexed { i, _ ->
    embeddings.map { it[i] }.average().toFloat()
}

// L2 normalize
val norm = sqrt(pooled.map { it * it }.sum())
val normalized = pooled.map { it / norm }
```

### Tokenization
**Current**: Simple word-level (split on spaces)
**Ideal**: WordPiece (HuggingFace tokenizers)

---

## Performance Optimization Techniques

### Threading Model
```
Main Thread: UI only
Audio Thread: High priority, continuous capture
Inference Thread: Single-threaded executor (prevents overlap)
WebSocket Thread: Library-managed
```

### Memory Optimization
- Pre-allocate buffers (avoid GC during capture)
- Reuse mel spectrogram arrays
- Circular buffer for audio (fixed size)

### CPU Optimization
- XNNPack for ARM NEON acceleration
- Reduce FFT size (512 vs 1024)
- Throttle UI updates (20 Hz max)

---

## Known Bugs & Workarounds

### Bug 1: Audio Level Flooding
**Problem**: 100 Hz audio level updates overwhelmed UI thread
**Solution**: Throttle to 20 Hz (50ms minimum interval)

### Bug 2: JSONArray Float Compatibility
**Problem**: Android 12+ JSONArray rejects FloatArray
**Solution**: Convert to DoubleArray before JSONArray

### Bug 3: Wake Lock Leak
**Problem**: Wake lock not released on crash
**Solution**: Only assign after successful acquire, release in finally

### Bug 4: Race Condition in Service Shutdown
**Problem**: VAD/RecognitionManager null during audio callback
**Solution**: Capture local references before starting callback

---

## Testing Strategies

### Manual Testing Checklist
```
[ ] Service starts without crash
[ ] IP address displayed correctly
[ ] WebSocket client can connect
[ ] Handshake message received
[ ] Partial results during speech
[ ] Final result after speech
[ ] Multiple clients work
[ ] Service survives 24 hours
[ ] Auto-restart after error
[ ] Clean shutdown
```

### Log Monitoring
```bash
# EchoWire app logs only
adb logcat -s UhService:* SpeechRecognizer:* WebSocket:*

# All speech-related
adb logcat | grep -i "speech\|recogni\|audio"

# Memory usage
adb shell dumpsys meminfo com.echowire
```

---

## Development Environment

### Build Requirements
- Java 21 (Gradle runtime)
- Android SDK 34
- Gradle 8.11
- Kotlin 2.0.20

### Makefile Targets
```bash
make build      # Build debug APK
make install    # Build and install to device
make logs       # Stream app logs
make clean      # Clean build artifacts
```

### Common Issues

**Issue**: Gradle fails with Java 25
**Solution**: Use Makefile (sets JAVA_HOME to Java 21)

**Issue**: Device not found
**Solution**: Check `adb devices`, enable USB debugging

**Issue**: Permission denied
**Solution**: Grant RECORD_AUDIO in app settings

---

## References

### Android Documentation
- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Foreground Services](https://developer.android.com/guide/components/foreground-services)
- [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)

### Whisper
- [OpenAI Whisper Paper](https://arxiv.org/abs/2212.04356)
- [Whisper GitHub](https://github.com/openai/whisper)
- [usefulsensors TFLite](https://github.com/usefulsensors/openai-whisper)

### TensorFlow Lite
- [TFLite Android](https://www.tensorflow.org/lite/android)
- [XNNPack](https://github.com/google/XNNPACK)
- [GPU Delegate Issues](https://github.com/tensorflow/tensorflow/issues?q=gpu+delegate+android)

### Libraries
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)
- [jmDNS](https://github.com/jmdns/jmdns)
- [ONNX Runtime](https://onnxruntime.ai/)
- [JTransforms](https://github.com/wendykierp/JTransforms)

---

## Revision History

| Date | Changes |
|------|---------|
| 2024-12 | Initial Whisper TFLite implementation |
| 2024-12 | GPU delegate research, fallback to XNNPack |
| 2024-12 | Language detection bug discovered |
| 2025-01 | Pivot to Android STT |
| 2025-01 | Added handshake protocol |
