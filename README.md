# EchoWire - Android Speech-to-Text WebSocket Server

**Version 2.0** — Production-ready real-time speech recognition service for Android

## What is EchoWire?

EchoWire is a lightweight Android foreground service that runs continuous speech recognition using the native Android STT API and broadcasts transcription results over WebSocket to multiple clients in real-time.

Perfect for:
- Voice-controlled applications
- Real-time transcription dashboards
- Multi-device voice input
- Local network voice assistants
- Development/debugging voice features

## Key Features

- **Real-time transcription** with incremental partial results
- **Multiple alternatives** with confidence scores
- **Dual language support**: English (US) and Russian
- **Low latency**: 100-300ms (platform STT)
- **Minimal overhead**: ~12MB APK, no bundled ML models
- **Multi-client broadcast**: WebSocket server with mDNS discovery
- **Auto-restart** on recognition errors
- **Visual feedback**: Audio waveform and dB meter
- **Zero configuration**: Works out of the box

## Architecture

```
Android SpeechRecognizer API
    ↓
EnhancedAndroidSpeechRecognizer (continuous mode, auto-restart)
    ↓
EchoWireService (foreground service)
    ↓
EchoWireWebSocketServer (broadcast to all clients)
    ↓
mDNS Advertisement (_echowire._tcp.local.)
```

**No ML models, no embeddings, no heavy dependencies.** Pure Android platform STT.

## Requirements

- **Android 12+** (API 31+)
- **Internet connection** (for cloud STT, device-dependent)
- **Microphone permission**
- **Local network** (for WebSocket clients)

**Tested on:** Samsung Galaxy Note20, Exynos 990, Android 12

## Quick Start

### 1. Install APK
```bash
adb install app-release.apk
```

### 2. Grant Permissions
- Microphone
- Notifications (for foreground service)

### 3. Start Service
Tap "Start Recognition" in the app. Service runs in foreground with persistent notification.

### 4. Connect Client
```javascript
const ws = new WebSocket('ws://192.168.1.100:8080');

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.type === 'hello') {
    console.log(`Connected to ${msg.device_name}`);
  }
  
  if (msg.type === 'partial_result') {
    console.log('Partial:', msg.text);
  }
  
  if (msg.type === 'final_result') {
    console.log('Final:', msg.best_text, `(${msg.best_confidence})`);
  }
};
```

### 5. Find Device via mDNS (Optional)
Service advertises as `_echowire._tcp.local.` for automatic discovery:
```bash
# macOS/Linux
dns-sd -B _echowire._tcp local.

# Or use Bonjour Browser, avahi-browse, etc.
```

## WebSocket Protocol

See [ANDROID_STT_PROTOCOL.md](ANDROID_STT_PROTOCOL.md) for full specification.

### Message Types

#### `hello` — Handshake on connect
```json
{
  "type": "hello",
  "device_name": "EchoWire Service",
  "protocol_version": 1,
  "timestamp": 1736707200000
}
```

#### `partial_result` — Real-time transcription (incremental)
```json
{
  "type": "partial_result",
  "text": "world",
  "timestamp": 1736707201234,
  "session_start": 1736707200000
}
```
**Note:** Sends only **new words** since last partial (incremental diff).

#### `final_result` — Complete result with alternatives
```json
{
  "type": "final_result",
  "alternatives": [
    {"text": "Hello world", "confidence": 0.95},
    {"text": "Hello word", "confidence": 0.72}
  ],
  "best_text": "Hello world",
  "best_confidence": 0.95,
  "language": "en-US",
  "timestamp": 1736707202000,
  "session_start": 1736707200000,
  "session_duration_ms": 2000,
  "speech_start": 1736707200500,
  "speech_duration_ms": 1500
}
```

#### `recognition_error` — Error events (filtered)
```json
{
  "type": "recognition_error",
  "error_code": 2,
  "error_message": "Network error",
  "timestamp": 1736707202500,
  "auto_restart": true
}
```
**Note:** Error code 7 ("No speech match") is suppressed as it's normal silence.

## Language Support

Switch language via UI buttons (EN/RU) or WebSocket command:
```json
{
  "command": "set_config",
  "key": "language",
  "value": "ru-RU"
}
```

Supported languages:
- `en-US` — English (United States)
- `ru-RU` — Russian

## Network Configuration

**Default WebSocket port:** 8080  
**mDNS service name:** `_echowire._tcp.local.`

To find device IP:
```bash
# On Android device
adb shell ip addr show wlan0

# Or check Settings → About Phone → Status → IP address
```

## Building from Source

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Kotlin 1.9+

### Build Steps
```bash
# Clone repository
git clone <repository-url>
cd echowire

# Build debug APK
./gradlew assembleDebug

# Build release APK (unsigned)
./gradlew assembleRelease

# Install to connected device
./gradlew installDebug
```

**Output:** `app/build/outputs/apk/`

## Project Structure

```
com.echowire/
├── service/
│   └── EchoWireService.kt              # Foreground service, STT orchestration
├── ml/
│   └── EnhancedAndroidSpeechRecognizer.kt  # Continuous STT wrapper
├── network/
│   ├── EchoWireWebSocketServer.kt      # WebSocket server + protocol
│   └── MdnsAdvertiser.kt               # mDNS service advertisement
├── config/
│   ├── EchoWireConfig.kt               # Static configuration
│   └── RuntimeConfig.kt                # Runtime config (WebSocket commands)
└── ui/
    ├── MainActivity.kt                 # Main UI controller
    ├── WaveformView.kt                 # Audio waveform visualization
    └── DbMeterView.kt                  # dB meter visualization
```

## Dependencies

```kotlin
// WebSocket server
implementation("org.java-websocket:Java-WebSocket:1.5.5")

// AndroidX
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.lifecycle:lifecycle-service:2.6.2")
```

**Total APK size:** ~12MB (no bundled models)

## Performance Characteristics

| Metric | Value |
|--------|-------|
| Latency | 100-300ms |
| Startup time | Instant (no model loading) |
| APK size | ~12MB |
| Memory usage | ~50-80MB (platform STT) |
| Network bandwidth | Minimal (text-only, 500-1000 bytes/utterance) |
| Message frequency | 1-3 messages/utterance (minimal spam) |

## Comparison: v1.0 (Whisper/ONNX) vs v2.0 (Android STT)

| Feature | v1.0 (Whisper) | v2.0 (Android STT) |
|---------|----------------|-------------------|
| Latency | 400-600ms | 100-300ms |
| APK size | ~50MB | ~12MB |
| Startup | 5-10s (model load) | Instant |
| Partial results | ❌ No | ✅ Yes (incremental) |
| Alternatives | ❌ No | ✅ Yes (top 5) |
| Confidence scores | ❌ No | ✅ Yes |
| Embeddings | ✅ 384-dim | ❌ No |
| Languages | Auto-detect | Manual (EN/RU) |
| Internet required | ❌ No | ✅ Usually |

## Troubleshooting

### Service won't start
- Check microphone permission granted
- Check notification permission granted (Android 13+)
- Verify device has Google app installed (required for STT on some devices)

### No WebSocket connection
- Check device and client on same network
- Verify firewall not blocking port 8080
- Check device IP address (Settings → About Phone → Status)
- Try `telnet <device-ip> 8080` to verify port open

### Recognition errors
- **Error code 1/2 (Network)**: Check internet connection
- **Error code 3 (Audio)**: Check microphone not in use by another app
- **Error code 6 (Speech timeout)**: Normal when user is silent
- **Error code 9 (Permissions)**: Re-grant microphone permission

### Poor recognition accuracy
- Speak clearly and not too fast
- Reduce background noise
- Check correct language selected (EN/RU)
- Ensure strong internet connection (better STT models on server)

## Future Enhancements

- [ ] WSS (WebSocket Secure) with self-signed certificates
- [ ] More languages (Spanish, French, German, etc.)
- [ ] Custom vocabulary hints for domain-specific words
- [ ] Optional local embedding generation
- [ ] Automatic punctuation
- [ ] On-device mode fallback (offline support)

## License

[Specify license here]

## Contributing

[Specify contribution guidelines here]

## Credits

Built with:
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) by TooTallNate
- Android SpeechRecognizer API
- Kotlin & AndroidX

---

**Version:** 2.0  
**Protocol Version:** 1  
**Last Updated:** 2026-01-28
