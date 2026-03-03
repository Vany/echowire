# Changelog

All notable changes to EchoWire project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-01-28

### 🚀 Major Rewrite - Android STT Architecture & EchoWire Rebrand

Complete architecture overhaul replacing Whisper/ONNX pipeline with native Android SpeechRecognizer API.
Project renamed from "UH" to "EchoWire".

### Added
- **Real-time incremental partial results** - Send only new words during speech
- **Multiple transcription alternatives** - Up to 5 alternatives with confidence scores
- **Dual language support** - English (US) and Russian with UI toggle
- **WebSocket handshake** - `hello` message with device authentication
- **Session metadata** - Detailed timing info (session duration, speech duration)
- **Error filtering** - Suppress "No speech match" errors (normal silence)
- **Auto-restart on errors** - Automatic recovery from recognition failures
- **mDNS service advertisement** - `_echowire._tcp.local.` for device discovery
- **Runtime configuration** - WebSocket commands for dynamic config changes
- **Enhanced UI** - Language selection buttons, improved state feedback

### Changed
- **Speech recognition engine** - Android STT API (was Whisper/ONNX)
- **Latency** - 100-300ms (was 400-600ms) - **3x faster**
- **APK size** - ~12MB (was ~50MB) - **4x smaller**
- **Startup time** - Instant (was 5-10s model loading)
- **WebSocket protocol** - Clean v1 protocol (see ANDROID_STT_PROTOCOL.md)
- **Message frequency** - 1-3 messages/utterance (was 10-30 messages/second)
- **Bandwidth** - Minimal text-only (was high with audio levels)
- **Package structure** - Reorganized into service/network/config/ui packages

### Removed
- **Embeddings** - No semantic embeddings (Android STT doesn't provide)
- **`audio_level` messages** - Removed spam (was 20-30 Hz broadcast)
- **`recognition_event` messages** - Removed state change spam
- **Whisper models** - No bundled ML models (50MB TFLite)
- **ONNX Runtime** - No embedding model (86MB)
- **Offline support** - Requires internet (cloud STT, device-dependent)
- **Auto language detection** - Manual selection only (EN/RU)

### Fixed
- **Race condition** - EchoWireService.startListening() NPE crash
- **Wake lock leak** - Release lock on acquire() failure
- **Multicast lock leak** - Cleanup on mDNS registration error
- **Audio level spam** - Throttled to 20 Hz (was 100 Hz)
- **JSON serialization** - Float array conversion for embeddings
- **Duplicate word broadcasts** - Incremental partial results prevent duplicates
- **App branding** - All "UH" references replaced with "EchoWire"
- **mDNS service** - Updated from `_uh._tcp.local.` to `_echowire._tcp.local.`

### Performance
- **Latency:** 100-300ms (target met)
- **Memory:** ~50-80MB (platform STT, minimal overhead)
- **CPU:** Low (no on-device inference)
- **Battery:** Minimal (cloud STT offloads compute)
- **Network:** Text-only, 500-1000 bytes/utterance

### Documentation
- **README.md** - Complete user guide with quick start
- **ANDROID_STT_PROTOCOL.md** - Full WebSocket protocol specification
- **CLAUDE.md** - Architecture overview and development guide
- **RELEASE_CHECKLIST.md** - Release verification process

### Breaking Changes
⚠️ **WebSocket protocol completely changed**

Clients using v1.0 must update to new protocol:
- Replace `speech` message handling with `partial_result` + `final_result`
- Remove `audio_level` message handling (no longer sent)
- Add `hello` message handling for device authentication
- Handle incremental partial results (only new words)
- Parse `alternatives` array for multiple results

See [ANDROID_STT_PROTOCOL.md](ANDROID_STT_PROTOCOL.md) for migration guide.

### Migration Guide (v1.0 → v2.0)

#### Old Protocol (v1.0)
```javascript
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.type === 'speech') {
    console.log(msg.text);           // "Hello world"
    console.log(msg.embedding);      // [0.123, -0.456, ...]
    console.log(msg.language);       // "en"
  }
  
  if (msg.type === 'audio_level') {
    updateMeter(msg.rms_db);         // ~20-30 Hz spam
  }
};
```

#### New Protocol (v2.0)
```javascript
let currentText = '';  // Accumulate incremental partials

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  
  if (msg.type === 'hello') {
    console.log(`Connected to ${msg.device_name}`);
  }
  
  if (msg.type === 'partial_result') {
    currentText += (currentText ? ' ' : '') + msg.text;  // Incremental
    console.log('Partial:', currentText);
  }
  
  if (msg.type === 'final_result') {
    console.log('Final:', msg.best_text);              // "Hello world"
    console.log('Confidence:', msg.best_confidence);   // 0.95
    console.log('Alternatives:', msg.alternatives);    // [{text, confidence}, ...]
    console.log('Language:', msg.language);            // "en-US"
    currentText = '';  // Reset for next utterance
    
    // No embeddings - generate client-side if needed
  }
};
```

### Upgrade Checklist
- [ ] Update WebSocket client code (see above)
- [ ] Remove embedding handling (no longer provided)
- [ ] Remove audio_level visualization (no longer sent)
- [ ] Add hello message authentication
- [ ] Handle incremental partial results
- [ ] Parse alternatives array
- [ ] Test with real device

---

## [1.0.0] - 2026-01-XX

### Initial Release - Whisper/ONNX Architecture

### Added
- **Whisper tiny multilingual** - On-device STT with TFLite
- **Sentence embeddings** - all-MiniLM-L6-v2 (384-dim)
- **WebSocket server** - Broadcast to multiple clients
- **Audio visualization** - Waveform + dB meter
- **Voice activity detection** - Energy-based VAD
- **Foreground service** - Continuous background operation

### Features
- **Latency:** 400-600ms
- **APK size:** ~50MB (bundled models)
- **Offline support:** Full offline operation
- **Auto language detection:** 99 languages
- **Embeddings:** Semantic similarity support

### Known Issues
- **TFLite GPU delegate broken** - CPU-only (still performant)
- **Simple tokenization** - Lower embedding accuracy
- **High audio_level spam** - 100 Hz broadcast load

---

## Version Comparison

| Feature | v1.0 (Whisper) | v2.0 (Android STT) |
|---------|----------------|-------------------|
| Latency | 400-600ms | 100-300ms ⚡ |
| APK size | 50MB | 12MB 📦 |
| Startup | 5-10s | Instant ⚡ |
| Offline | ✅ Yes | ❌ Requires internet |
| Partial results | ❌ No | ✅ Yes (incremental) |
| Alternatives | ❌ No | ✅ Yes (top 5) |
| Confidence | ❌ No | ✅ Yes (per result) |
| Embeddings | ✅ 384-dim | ❌ No |
| Languages | ✅ 99 (auto) | ⚠️ 2 (manual) |
| Protocol spam | ⚠️ High | ✅ Minimal |

---

## Unreleased

### Planned Features
- WSS (WebSocket Secure) with self-signed certificates
- More languages (Spanish, French, German, etc.)
- Custom vocabulary hints
- Optional local embedding generation
- Automatic punctuation
- On-device mode fallback (offline support)
- Speaker identification

---

[2.0.0]: https://github.com/yourorg/echowire/releases/tag/v2.0.0
[1.0.0]: https://github.com/yourorg/echowire/releases/tag/v1.0.0
