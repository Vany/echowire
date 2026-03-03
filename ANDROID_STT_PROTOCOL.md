# Android STT WebSocket Protocol

## Design Goals
- **Minimal, clean output**: Send only meaningful recognition data
- **Incremental updates**: Send only new words in partial results
- **Low bandwidth**: No spam, no redundant events
- **Real-time**: Low-latency updates for live transcription UX

## Connection Handshake

Upon WebSocket connection, the server immediately sends a `hello` message for device identification/authentication:

```json
{
  "type": "hello",
  "device_name": "EchoWire Service",
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

### 1. `partial_result` - Real-time Transcription (Incremental)
Sent while user is speaking, provides **only new words** since last partial result.

```json
{
  "type": "partial_result",
  "text": "world",
  "timestamp": 1736707201234,
  "session_start": 1736707200000
}
```

**Example sequence:**
1. User says "hello" → `{"text": "hello"}`
2. User says "hello world" → `{"text": "world"}` (only new word)
3. User says "hello world how" → `{"text": "how"}` (only new word)

**Fields:**
- `type`: Always `"partial_result"`
- `text`: **New words only** since last partial (incremental diff)
- `timestamp`: System time when partial was generated
- `session_start`: Timestamp when recognition session started

**Filtering:**
- Empty/blank results are suppressed
- Only sends when there are actual new words

**Frequency**: 0.5-2 messages/second during speech

---

### 2. `final_result` - Complete Transcription with Metadata
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
  - `text`: Complete transcribed text
  - `confidence`: Confidence score (0.0-1.0, higher is better)
- `best_text`: Highest confidence result (convenience field)
- `best_confidence`: Confidence of best result
- `language`: Language code used for recognition (e.g., "en-US", "ru-RU")
- `timestamp`: System time when result finalized
- `session_start`: Timestamp when recognition session started
- `session_duration_ms`: Total time from ready to result
- `speech_start`: Timestamp when user started speaking
- `speech_duration_ms`: Time from speech start to end

**Frequency**: Once per utterance (every 2-10 seconds typically)

---

### 3. `recognition_error` - Error Events (Filtered)
Sent when **significant** recognition error occurs.

```json
{
  "type": "recognition_error",
  "error_code": 2,
  "error_message": "Network error",
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
- `8`: ERROR_RECOGNIZER_BUSY
- `9`: ERROR_INSUFFICIENT_PERMISSIONS

**Filtered out:**
- `7` (ERROR_NO_MATCH / "No speech match") - suppressed as this is normal when user is silent

**Fields:**
- `type`: Always `"recognition_error"`
- `error_code`: Android error code (int)
- `error_message`: Human-readable error message
- `timestamp`: System time
- `auto_restart`: Boolean, true if recognition will auto-restart

**Frequency**: Sparse, only on real errors

---

## Removed Messages (No Longer Sent)

The following message types are **NOT sent** to reduce spam:

### ❌ `audio_level` - Removed
Audio level updates (~20-30 Hz) created too much spam. Audio visualization is now UI-only.

### ❌ `recognition_event` - Removed
State change events (`ready_for_speech`, `speech_start`, `speech_end`, `listening_started`, `listening_stopped`) are no longer broadcast. Clients should infer state from `partial_result` and `final_result` messages.

### ❌ `audio_status` - Removed
Backward compatibility message removed as it's redundant.

---

## Message Flow Example

Typical recognition session (clean, minimal):

```
[connect]   hello: device_name="EchoWire Service", protocol_version=1
[T+800ms]   partial_result: "hello"
[T+1500ms]  partial_result: "world"    (only new word)
[T+2200ms]  final_result: "hello world" (confidence: 0.95)
```

**Total messages**: 3 per utterance (hello, 1-3 partials, 1 final)
**Bandwidth**: ~500-1000 bytes/utterance (minimal)

---

## Language Support

Currently supported languages:
- **English (US)**: `en-US`
- **Russian**: `ru-RU`

Language can be changed:
1. Via Android app UI (EN/RU buttons)
2. Via WebSocket command (future):
   ```json
   {
     "command": "set_config",
     "key": "language",
     "value": "ru-RU"
   }
   ```

---

## Implementation Notes

### Incremental Partial Results
The server tracks the last partial result and sends only new words:
- If previous partial was "hello" and new one is "hello world", only "world" is sent
- Resets on each new recognition session
- Empty/blank partials are suppressed

### Error Filtering
- Error code 7 ("No speech match") is suppressed - this is normal when user stops speaking
- Only real errors are broadcast to clients

### No Embeddings
- Android STT doesn't provide semantic embeddings
- Clients needing embeddings must:
  - Run local embedding model on transcribed text
  - OR use external API (OpenAI, Cohere, etc.)

### Network Dependency
- Requires internet connection (usually)
- Some devices support on-device recognition (device-dependent)
- Samsung Note20 uses cloud STT for best accuracy

---

## Comparison: Old vs New Protocol

| Feature | Old (Whisper/ONNX) | New (Android STT v2) |
|---------|-------------------|---------------------|
| Message types | 2 | 3 (minimal) |
| Partial results | ❌ No | ✅ Yes (incremental) |
| Audio levels | ✅ 20 Hz | ❌ Removed (spam) |
| State events | ❌ No | ❌ Removed (spam) |
| Multiple alternatives | ❌ No | ✅ Yes (top 5) |
| Confidence scores | ❌ No | ✅ Yes (per alternative) |
| Timing metadata | ✅ Basic | ✅ Detailed |
| Embeddings | ✅ 384-dim | ❌ No |
| Language support | ✅ Auto | ✅ Manual (EN/RU) |
| Latency | ~400-600ms | ~100-300ms |
| Bandwidth | High (audio levels) | Minimal (text only) |
| APK size | ~50MB (bundled model) | ~12MB (no models) |

---

## Client Implementation Example

```javascript
const ws = new WebSocket('ws://192.168.1.100:8080');

const EXPECTED_DEVICE = 'EchoWire Service';
let authenticated = false;
let currentText = '';  // Accumulate partial results

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
      
    case 'partial_result':
      if (authenticated) {
        // Accumulate new words
        currentText += (currentText ? ' ' : '') + msg.text;
        displayPartialText(currentText);
      }
      break;
      
    case 'final_result':
      if (authenticated) {
        displayFinalText(msg.best_text, msg.best_confidence);
        console.log('Alternatives:', msg.alternatives);
        currentText = '';  // Reset for next utterance
      }
      break;
      
    case 'recognition_error':
      console.error('Recognition error:', msg.error_message);
      currentText = '';  // Reset on error
      break;
  }
};
```

---

## Future Enhancements

1. **More languages**: Add support for additional languages (Spanish, French, German, etc.)
2. **Embedding integration**: Optional local embedding generation for `final_result`
3. **Custom vocabulary**: Android supports hints for domain-specific words
4. **Punctuation**: Enable automatic punctuation in results
5. **Speaker identification**: Device-dependent, if available
6. **On-device mode**: Fallback to on-device STT when offline
