# TODO - UH Speech Recognition App

## Phase 1: Project Setup & Dependencies
- [ ] Update AndroidManifest.xml permissions:
  - [x] INTERNET (already have)
  - [x] FOREGROUND_SERVICE (already have)
  - [ ] RECORD_AUDIO
  - [ ] FOREGROUND_SERVICE_MICROPHONE
  - [x] WAKE_LOCK (already have)
- [ ] Add dependencies to build.gradle.kts:
  - [ ] WhisperKit Android (com.argmaxinc:whisperkit:0.3.3)
  - [ ] Qualcomm QNN runtime and delegate
  - [ ] Sentence-Embeddings-Android library
  - [ ] ONNX Runtime Android
  - [ ] Kotlinx Coroutines
- [ ] Update foreground service type to MICROPHONE
- [ ] Verify Android 12 (API 31) compatibility

## Phase 2: Model Management
- [ ] Create ModelManager class for downloading and managing models
  - [ ] WhisperKit model download (base: ~75MB)
  - [ ] all-MiniLM-L6-v2 ONNX model download (~23MB)
  - [ ] Tokenizer configuration download (~500KB)
  - [ ] Checksum verification
  - [ ] Storage in internal app directory
  - [ ] Model loading with progress callbacks
  - [ ] Error handling and retry logic
- [ ] Add model download UI (progress bar, status text)
- [ ] Implement model switching (runtime reconfiguration)

## Phase 3: Audio Capture
- [ ] Create AudioCaptureManager class
  - [ ] AudioRecord setup (16kHz, mono, 16-bit PCM)
  - [ ] Continuous capture loop on background thread
  - [ ] Audio level monitoring (for UI visualization)
  - [ ] Buffer management (circular buffer or streaming)
  - [ ] Voice Activity Detection (VAD) integration
  - [ ] Clean resource cleanup
- [ ] Test audio capture without recognition (verify levels)

## Phase 4: Speech Recognition Integration
- [ ] Create SpeechRecognitionManager class
  - [ ] WhisperKit initialization with base model
  - [ ] Audio data feeding (from AudioCaptureManager)
  - [ ] Streaming transcription (progressive segments)
  - [ ] Confidence score extraction
  - [ ] Timestamp tracking (segment start/end)
  - [ ] Error handling and recovery
- [ ] Test recognition pipeline (audio → text)
- [ ] Optimize for real-time performance (<500ms latency)
- [ ] Profile memory usage (target <2GB)

## Phase 5: Text Embedding Generation
- [ ] Create EmbeddingManager class
  - [ ] Sentence-Embeddings-Android initialization
  - [ ] Model and tokenizer loading
  - [ ] Text → embedding conversion (384 dims)
  - [ ] Batch processing optimization
  - [ ] Error handling
- [ ] Integrate with SpeechRecognitionManager (text → embedding)
- [ ] Test embedding generation (<50ms per phrase)

## Phase 6: WebSocket Integration
- [ ] Extend UhWebSocketServer for speech messages
  - [ ] Add "speech" message type handling
  - [ ] Add "audio_status" message type
  - [ ] Serialize embedding arrays to JSON
  - [ ] Broadcast speech recognition results
  - [ ] Maintain backward compatibility with configure messages
- [ ] Update RuntimeConfig with new variables:
  - [x] name (already have)
  - [ ] model (tiny/base/small)
  - [ ] language (en/multilingual)
  - [ ] vad_threshold (0.0-1.0)
  - [ ] listening (true/false)
- [ ] Test WebSocket broadcasting of speech messages

## Phase 7: Service Architecture
- [ ] Refactor UhService for speech recognition:
  - [x] WebSocket server (already have)
  - [x] mDNS registration (already have)
  - [ ] Remove random number generation
  - [ ] Remove ping task (keep ping frames)
  - [ ] Add ModelManager lifecycle
  - [ ] Add AudioCaptureManager lifecycle
  - [ ] Add SpeechRecognitionManager lifecycle
  - [ ] Add EmbeddingManager lifecycle
  - [ ] Coordinate pipeline: Audio → Speech → Embedding → Broadcast
  - [ ] Handle start/stop/pause listening
- [ ] Implement ServiceListener callbacks:
  - [x] onClientConnected/onClientDisconnected (already have)
  - [ ] onSpeechRecognized(text, embedding, timestamp)
  - [ ] onAudioLevelChanged(level)
  - [ ] onModelLoaded(modelName)
  - [ ] onListeningStateChanged(listening)
  - [x] onConfigChanged (already have)
  - [x] onError (already have)

## Phase 8: UI Implementation
- [ ] Update MainActivity layout:
  - [x] Service name display (already have)
  - [x] Connection indicator (already have)
  - [ ] Listening indicator (microphone active)
  - [ ] Audio level meter (visual bar or waveform)
  - [ ] Recognition display (scrollable text view for phrases)
  - [x] Log window (already have, extend for new events)
  - [ ] Model status display (loaded/loading/error)
- [ ] Implement UI callbacks from ServiceListener:
  - [ ] Update listening indicator
  - [ ] Update audio level meter (smooth animation)
  - [ ] Display recognized phrases in real-time
  - [ ] Show model loading progress
  - [ ] Handle errors gracefully
- [ ] Add UI controls:
  - [ ] Start/Stop listening button (optional, or always-on)
  - [ ] Model selection dropdown (optional)
  - [ ] Clear recognition display button

## Phase 9: Build & Testing
- [x] Makefile targets (already have, verify still work)
- [ ] Test on physical device with 8GB RAM
  - [ ] Verify model loading (<10s)
  - [ ] Verify real-time performance (<500ms latency)
  - [ ] Verify memory usage (<2GB)
  - [ ] Test continuous operation (1+ hour)
  - [ ] Test multiple WebSocket clients
- [ ] Test mDNS discovery from CLI client
- [ ] Test configuration changes via WebSocket
- [ ] Test error recovery (disconnect/reconnect)

## Phase 10: CLI Client Updates
- [ ] Update uhcli for speech messages:
  - [x] mDNS discovery (already have)
  - [x] WebSocket connection (already have)
  - [ ] Parse "speech" message type
  - [ ] Parse "audio_status" message type
  - [ ] Display recognized text with timestamps
  - [ ] Display embeddings (truncated/optional)
  - [ ] Display confidence scores
  - [ ] Add --full flag for verbose output
  - [ ] Add --json flag for raw JSON output
- [ ] Update configuration commands:
  - [x] set/get (already have)
  - [ ] Add new config variables (model, language, vad_threshold, listening)
- [ ] Test CLI with Android app

## Phase 11: Optimization & Polish
- [ ] Profile and optimize hot paths:
  - [ ] Audio capture buffering
  - [ ] WhisperKit inference
  - [ ] Embedding generation
  - [ ] WebSocket serialization
- [ ] Memory leak detection and fixes
- [ ] Battery usage optimization (line power assumed, but still)
- [ ] Audio quality tuning (sample rate, bit depth)
- [ ] VAD threshold tuning for best accuracy/latency
- [ ] Error messages and user feedback
- [ ] Log verbosity levels

## Phase 12: Documentation & Deployment
- [x] Update SPEC.md (done)
- [ ] Update CLAUDE.md with implementation notes
- [ ] Update README.md with usage instructions
- [ ] Document model selection guide (tiny/base/small trade-offs)
- [ ] Document CLI usage examples
- [ ] Create APK release build
- [ ] Test deployment on clean device

## Known Challenges & Questions
- WhisperKit QNN acceleration requires Qualcomm device - what if device incompatible?
- Model download on first launch - need progress UI and error handling
- Real-time performance tuning - may need to adjust buffer sizes, model size
- WebSocket message size - embedding arrays are 384 floats (~1.5KB per message)
- Memory pressure - need to test on actual 8GB device under load
- Audio permission handling - runtime permission request flow
- Service lifecycle - ensure clean shutdown and restart
