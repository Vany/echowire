# Android STT WebSocket Protocol

## Design Goals
- **Maximum information extraction**: Send all available data from Android STT
- **Maximum frequency**: Stream updates as often as possible (partial results, audio levels)
- **Real-time**: Low-latency updates for live transcription UX
- **Backward compatible**: Clients can ignore messages they don't need

## Connection Handshake

Upon WebSocket connection, the server immediately sends a `hello` message for device identification/authentication:

```json
{
  "type": "hello",
  "device_name": "UH Service",
  "protocol_version": 1,
  "timestamp": 1736707200000
}
```

**Fields:**
- `type`: Always `"hello"`
- `device_name`: Configured device/service name (can be changed via `configure` command)
- `protocol_version`: Protocol version for compatibility checking (currently `1`)
- `timestamp`: Server time (milliseconds since epoch)

**Client behavior:**
- Clients should verify `device_name` matches expected device for authentication
- Clients may disconnect if `protocol_version` is incompatible
- This is always the first message received after connection

---

## Message Types

### 1. `audio_level` - RMS Audio Level (10-30 Hz)
Sent continuously while listening, ~10-30 times per second.

```json
{
  "type": "audio_level",
  "rms_db": -12.5,
  "listening": true,
  "timestamp": 1736707200000
}
```

**Fields:**
- `type`: Always `"audio_level"`
- `rms_db`: RMS audio level in decibels (float, typically -40 to 0 dB)
- `listening`: Boolean, true if recognition is active
- `timestamp`: System time (milliseconds since epoch)

**Frequency**: 10-30 messages/second while listening

---

### 2. `partial_result` - Real-time Transcription (0.5-2 Hz)
Sent while user is speaking, provides best guess so far.

```json
{
  "type": "partial_result",
  "text": "Hello wor",
  "timestamp": 1736707201234,
  "session_start": 1736707200000
}
```

**Fields:**
- `type`: Always `"partial_result"`
- `text`: Partial transcription (string, may be incomplete)
- `timestamp`: System time when partial was generated
- `session_start`: Timestamp when recognition session started

**Frequency**: 0.5-2 messages/second during speech

---

### 3. `final_result` - Complete Transcription with Metadata
Sent when user stops speaking and recognition completes.

```json
{
  "type": "final_result",
  "alternatives": [
    {
      "text": "Hello world",
      "confidence": 0.95
    },
    {
      "text": "Hello word",
      "confidence": 0.72
    },
    {
      "text": "Hell low world",
      "confidence": 0.45
    }
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

**Fields:**
- `type`: Always `"final_result"`
- `alternatives`: Array of recognition alternatives, sorted by confidence (max 5)
  - `text`: Transcribed text
  - `confidence`: Confidence score (0.0-1.0, higher is better)
- `best_text`: Highest confidence result (convenience field)
- `best_confidence`: Confidence of best result
- `language`: Language code used for recognition (e.g., "en-US", "ru-RU")
- `timestamp`: System time when result finalized
- `session_start`: Timestamp when recognition session started ("ready for speech")
- `session_duration_ms`: Total time from ready to result
- `speech_start`: Timestamp when user started speaking
- `speech_duration_ms`: Time from speech start to end

**Frequency**: Once per utterance (every 2-10 seconds typically)

---

### 4. `recognition_event` - State Changes
Sent when recognition state changes.

```json
{
  "type": "recognition_event",
  "event": "ready_for_speech",
  "timestamp": 1736707200000,
  "listening": true
}
```

**Event types:**
- `"ready_for_speech"`: Recognition session ready, waiting for speech
- `"speech_start"`: User started speaking
- `"speech_end"`: User stopped speaking (silence detected)
- `"listening_started"`: Recognition started
- `"listening_stopped"`: Recognition stopped

**Fields:**
- `type`: Always `"recognition_event"`
- `event`: Event type (string, see above)
- `timestamp`: System time
- `listening`: Current listening state (boolean)

**Frequency**: Sparse, only on state changes

---

### 5. `recognition_error` - Error Events
Sent when recognition error occurs.

```json
{
  "type": "recognition_error",
  "error_code": 7,
  "error_message": "No speech match",
  "timestamp": 1736707202500,
  "auto_restart": true
}
```

**Common error codes:**
- `1`: ERROR_NETWORK_TIMEOUT
- `2`: ERROR_NETWORK
- `3`: ERROR_AUDIO
- `4`: ERROR_SERVER
- `5`: ERROR_CLIENT
- `6`: ERROR_SPEECH_TIMEOUT
- `7`: ERROR_NO_MATCH
- `8`: ERROR_RECOGNIZER_BUSY
- `9`: ERROR_INSUFFICIENT_PERMISSIONS

**Fields:**
- `type`: Always `"recognition_error"`
- `error_code`: Android error code (int)
- `error_message`: Human-readable error message
- `timestamp`: System time
- `auto_restart`: Boolean, true if recognition will auto-restart

**Frequency**: Sparse, only on errors

---

### 6. `audio_status` - Periodic Status (Backward Compatible)
Sent every 50ms for backward compatibility with existing clients.

```json
{
  "type": "audio_status",
  "listening": true,
  "audio_level": 0.42,
  "timestamp": 1736707200000
}
```

**Note**: Redundant with `audio_level` but kept for compatibility.

---

## Message Flow Example

Typical recognition session:

```
[connect]  hello: device_name="UH Service", protocol_version=1
[T+0ms]    recognition_event: ready_for_speech
[T+10ms]   audio_level: -40 dB (silence)
[T+40ms]   audio_level: -38 dB
[T+70ms]   audio_level: -35 dB
[T+100ms]  audio_level: -30 dB
[T+500ms]  recognition_event: speech_start
[T+510ms]  audio_level: -15 dB (speaking)
[T+540ms]  audio_level: -12 dB
[T+800ms]  partial_result: "Hel"
[T+1200ms] partial_result: "Hello"
[T+1500ms] partial_result: "Hello wor"
[T+1800ms] partial_result: "Hello world"
[T+2000ms] recognition_event: speech_end
[T+2200ms] final_result: "Hello world" (confidence: 0.95)
[T+2300ms] recognition_event: ready_for_speech (auto-restart)
```

**Total messages**: ~50-100 per utterance
**Bandwidth**: ~1-5 KB/utterance (very low)

---

## Implementation Notes

### Throttling
- `audio_level`: Send every 30-50ms (20-30 Hz max)
- `partial_result`: Send as received from Android (~500ms intervals)
- `final_result`: Send immediately on completion
- `recognition_event`: Send immediately on state change
- `recognition_error`: Send immediately on error

### Language Configuration
- Currently hardcoded in service
- Future: Add WebSocket command to change language
  ```json
  {
    "command": "set_language",
    "language": "ru-RU"
  }
  ```

### No Embeddings
- Android STT doesn't provide semantic embeddings
- Clients needing embeddings must:
  - Run local embedding model on transcribed text
  - OR use external API (OpenAI, Cohere, etc.)

### Network Dependency
- Requires internet connection (usually)
- Some devices support on-device recognition (unreliable)
- Consider fallback message if offline

---

## Comparison: Old vs New Protocol

| Feature | Old (Whisper) | New (Android STT) |
|---------|--------------|-------------------|
| Message types | 2 (`speech`, `audio_status`) | 6 (detailed events) |
| Partial results | ❌ No | ✅ Yes (~0.5-2 Hz) |
| Audio levels | ✅ 20 Hz | ✅ 20-30 Hz |
| Multiple alternatives | ❌ No | ✅ Yes (top 5) |
| Confidence scores | ❌ No | ✅ Yes (per alternative) |
| Timing metadata | ✅ Basic | ✅ Detailed (session, speech) |
| Embeddings | ✅ 384-dim | ❌ No |
| Language detection | ✅ Auto | ❌ Manual config |
| Latency | ~400-600ms | ~100-300ms |
| Offline support | ✅ Yes | ⚠️ Device-dependent |

---

## Client Implementation Example

```javascript
const ws = new WebSocket('ws://192.168.1.100:8080');

const EXPECTED_DEVICE = 'UH Service';
let authenticated = false;

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  switch (msg.type) {
    case 'hello':
      // Verify device identity
      if (msg.device_name === EXPECTED_DEVICE) {
        authenticated = true;
        console.log(`Connected to ${msg.device_name} (protocol v${msg.protocol_version})`);
      } else {
        console.error(`Unexpected device: ${msg.device_name}`);
        ws.close();
      }
      break;
      
    case 'audio_level':
      if (authenticated) updateWaveform(msg.rms_db);
      break;
      
    case 'partial_result':
      if (authenticated) displayPartialText(msg.text);
      break;
      
    case 'final_result':
      if (authenticated) {
        displayFinalText(msg.best_text, msg.best_confidence);
        console.log('Alternatives:', msg.alternatives);
      }
      break;
      
    case 'recognition_event':
      console.log('Event:', msg.event);
      break;
      
    case 'recognition_error':
      console.error('Error:', msg.error_message);
      break;
  }
};
```

---

## Future Enhancements

1. **Language switching**: WebSocket command to change language on-the-fly
2. **Embedding integration**: Optional local embedding generation for `final_result`
3. **Audio streaming**: Stream raw audio chunks for client-side processing
4. **Multi-language detection**: Try multiple languages and return all results
5. **Custom vocabulary**: Android supports hints for domain-specific words
6. **Speaker identification**: Device-dependent, if available
