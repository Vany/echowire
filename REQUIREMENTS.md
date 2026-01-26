# UH - Requirements Specification

## Product Overview

**UH** is an Android application that performs continuous speech recognition and streams results over WebSocket to multiple clients. Designed for local network use with mDNS discovery.

**Primary Use Case**: Real-time speech-to-text streaming for integration with other applications, automation systems, or voice-controlled interfaces.

---

## Functional Requirements

### FR-1: Speech Recognition

#### FR-1.1: Android Native STT (Primary)
- Use Android's built-in SpeechRecognizer API
- Support continuous 24/7 recognition with auto-restart
- Provide partial results during speech (real-time transcription)
- Provide final results with confidence scores
- Support multiple recognition alternatives (top 5)
- Calculate timing metadata (session duration, speech duration)

#### FR-1.2: Language Support
- Default language: English (en-US)
- Secondary language: Russian (ru-RU)
- Runtime language switching via configuration
- Language code included in results

#### FR-1.3: Audio Level Monitoring
- Continuous RMS audio level measurement
- Report levels in decibels (dB)
- Update frequency: 20-30 Hz while listening

### FR-2: Network Services

#### FR-2.1: WebSocket Server
- Run WebSocket server on dynamic port (start from 8080)
- Support multiple simultaneous client connections
- Fire-and-forget broadcast model
- Automatic port selection if default unavailable

#### FR-2.2: Connection Handshake
- Send `hello` message immediately on client connection
- Include device name for client authentication
- Include protocol version for compatibility
- Format:
```json
{
  "type": "hello",
  "device_name": "UH Service",
  "protocol_version": 1,
  "timestamp": 1736707200000
}
```

#### FR-2.3: mDNS Service Advertisement
- Advertise service as `_uh._tcp.local.`
- Include WebSocket port in service info
- Configurable service name

#### FR-2.4: Keep-Alive
- Send WebSocket ping every 5 seconds
- Detect and remove dead connections

### FR-3: Message Protocol

#### FR-3.1: Audio Level Message (10-30 Hz)
```json
{
  "type": "audio_level",
  "rms_db": -12.5,
  "listening": true,
  "timestamp": 1736707200000
}
```

#### FR-3.2: Partial Result Message (0.5-2 Hz during speech)
```json
{
  "type": "partial_result",
  "text": "Hello wor",
  "timestamp": 1736707201234,
  "session_start": 1736707200000
}
```

#### FR-3.3: Final Result Message (once per utterance)
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

#### FR-3.4: Recognition Event Message
```json
{
  "type": "recognition_event",
  "event": "ready_for_speech|speech_start|speech_end|listening_started|listening_stopped",
  "timestamp": 1736707200000,
  "listening": true
}
```

#### FR-3.5: Recognition Error Message
```json
{
  "type": "recognition_error",
  "error_code": 7,
  "error_message": "No speech match",
  "timestamp": 1736707202500,
  "auto_restart": true
}
```

#### FR-3.6: Audio Status Message (backward compatibility)
```json
{
  "type": "audio_status",
  "listening": true,
  "audio_level": 0.42,
  "timestamp": 1736707200000
}
```

### FR-4: Configuration System

#### FR-4.1: Runtime Configuration
- Modify configuration during service execution
- Thread-safe configuration storage
- Change notification to listeners

#### FR-4.2: WebSocket Configuration Commands
- Get value: `{"configure": "key"}`
- Set value: `{"configure": "key", "value": "new_value"}`
- Response: `{"configure": "key", "value": "current_value"}`

#### FR-4.3: Configuration Variables
| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `name` | string | "UH Service" | Service display name |
| `language` | string | "en-US" | Recognition language code |

### FR-5: User Interface

#### FR-5.1: Main Screen
- Service name display (configurable)
- IP address and port display (ws://x.x.x.x:port format)
- Connection indicator (client count, color-coded)
- Real-time waveform visualization
- dB meter display
- Event log (scrollable, max 100 lines)
- Start/Stop service buttons
- Test Android STT button

#### FR-5.2: Visual States
- **Green**: Idle/listening, ready for speech
- **Yellow**: Processing/recognizing speech
- **Red**: Error state

#### FR-5.3: Notifications
- Foreground service notification while running
- Show port number in notification

### FR-6: Service Lifecycle

#### FR-6.1: Foreground Service
- Run as Android foreground service
- Use FOREGROUND_SERVICE_TYPE_MICROPHONE
- Maintain wake lock to prevent sleep
- Continue running when app in background

#### FR-6.2: Resource Management
- Clean shutdown on service stop
- Release audio resources
- Close WebSocket connections
- Unregister mDNS service
- Release wake lock

#### FR-6.3: Error Recovery
- Auto-restart recognition on recoverable errors
- Log all errors with codes and messages
- Notify clients of errors

---

## Non-Functional Requirements

### NFR-1: Performance

#### NFR-1.1: Latency
- Partial result latency: < 300ms from speech
- Final result latency: < 500ms from speech end
- Audio level update: < 50ms

#### NFR-1.2: Throughput
- Support 5+ simultaneous WebSocket clients
- No degradation with multiple clients
- Audio level: 20-30 messages/second
- Partial results: 0.5-2 messages/second during speech

#### NFR-1.3: Resource Usage
- Memory: < 500MB total
- CPU: < 30% sustained during recognition
- Battery: Acceptable for continuous operation (line power assumed)

### NFR-2: Reliability

#### NFR-2.1: Uptime
- 99%+ uptime for 24-hour continuous operation
- Auto-recovery from recognition errors
- Handle network interruptions gracefully

#### NFR-2.2: Error Handling
- Never crash from recognition errors
- Log all errors with actionable messages
- Notify clients of all error conditions

### NFR-3: Compatibility

#### NFR-3.1: Android Version
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 34 (Android 14)
- Tested on: Android 12 (Samsung Note20)

#### NFR-3.2: Network
- IPv4 support required
- IPv6 support optional
- WiFi and Ethernet interfaces

### NFR-4: Security

#### NFR-4.1: Network Security
- Assumed secure local network
- No authentication (device name for identification only)
- No encryption (plaintext WebSocket)

#### NFR-4.2: Permissions
- RECORD_AUDIO: Required for microphone
- FOREGROUND_SERVICE_MICROPHONE: Required for service
- INTERNET: Required for Google STT (usually)

---

## Hardware Requirements

### Target Device
- **Model**: Samsung Galaxy Note20 (SM-N980F)
- **SoC**: Exynos 990
- **RAM**: 8GB
- **Android**: 12

### Minimum Requirements
- ARM64-v8a architecture
- 4GB RAM
- Microphone
- WiFi or Ethernet connectivity
- Android 8.0+

---

## External Dependencies

### Android APIs
- SpeechRecognizer (android.speech)
- AudioRecord (android.media) - for audio level
- NsdManager (android.net.nsd) - for mDNS
- PowerManager (android.os) - for wake lock

### Libraries
- Java-WebSocket 1.5.3 - WebSocket server
- jmDNS 3.5.8 - mDNS advertisement (fallback)

### Services
- Google Speech Recognition (cloud, usually)
- On-device recognition (device-dependent)

---

## Constraints

### C-1: Network Dependency
- Android STT typically requires internet connection
- Some devices support on-device recognition (unreliable)
- No offline guarantee

### C-2: Recognition Limitations
- Maximum utterance length: ~60 seconds (Android limit)
- Brief pause triggers end of speech
- Background noise affects accuracy

### C-3: Platform Limitations
- WebSocket server port may conflict
- mDNS may not work on all networks
- Wake lock drains battery

---

## Future Requirements (Backlog)

### Phase 2: Enhanced Features
- [ ] On-device Whisper model (offline support)
- [ ] Text embeddings (384-dim semantic vectors)
- [ ] Custom wake word detection
- [ ] Speaker diarization

### Phase 3: Security
- [ ] Client authentication tokens
- [ ] TLS/WSS encryption
- [ ] Access control lists

### Phase 4: Advanced
- [ ] Multiple language simultaneous detection
- [ ] Audio recording and replay
- [ ] Custom vocabulary hints
- [ ] Noise cancellation

---

## Acceptance Criteria

### AC-1: Basic Functionality
- [ ] Service starts and shows IP address
- [ ] WebSocket clients can connect
- [ ] Handshake message received on connect
- [ ] Speech is recognized and broadcast
- [ ] Multiple clients receive same messages

### AC-2: Reliability
- [ ] Service runs 24 hours without crash
- [ ] Auto-restarts after recognition errors
- [ ] Handles client disconnect gracefully

### AC-3: Performance
- [ ] Partial results within 300ms
- [ ] Final results within 500ms
- [ ] No dropped messages with 3 clients

---

## Revision History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2024-12 | Initial specification (Whisper-based) |
| 2.0 | 2025-01 | Pivot to Android STT, added handshake |
