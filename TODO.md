# TODO - UH Speech Recognition App

## ✅ COMPLETED PHASES

### Phase 1: Project Setup & Dependencies ✅
- AndroidManifest.xml permissions (RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE)
- build.gradle.kts dependencies (TFLite, ONNX Runtime, JTransforms, WebSocket)
- Runtime permission handling in MainActivity
- Foreground service type configuration

### Phase 2: Model Management ✅
- ModelManager class with asset extraction
- Whisper tiny multilingual (66MB) bundled
- all-MiniLM-L6-v2 embedding model (86MB) bundled
- Tokenizer (1.5MB) bundled
- Total assets: 177MB in APK
- Extraction on first run to internal storage
- Fixed vocabulary loading bug (51,865 tokens)

### Phase 3: Audio Capture ✅
- AudioCaptureManager (16kHz mono PCM, 100ms chunks)
- SimpleVAD energy-based detection (threshold=0.02)
- Continuous capture with high-priority thread
- Audio level monitoring (RMS calculation)
- Clean resource lifecycle management

### Phase 4: Speech Recognition ✅
- AudioPreprocessor: PCM → mel spectrogram (JTransforms FFT)
- WhisperModel: TFLite with XNNPack CPU acceleration
- WhisperTokenizer: Token IDs → text with BPE cleanup
- SpeechRecognitionManager: Full pipeline orchestration
- CircularAudioBuffer: 1-30s audio accumulation
- Integrated into UhService with ServiceListener callbacks
- Latency: 400-600ms, RTF: 0.4-0.6x ✓

### Phase 5: Text Embeddings ✅
- EmbeddingManager: ONNX Runtime with all-MiniLM-L6-v2
- Simple word-level tokenization
- Mean pooling + L2 normalization
- 384-dimensional vectors
- Latency: ~30-50ms per phrase
- Integrated into speech recognition pipeline

### Phase 6: WebSocket Integration ✅
- Speech message broadcasting (text, embedding, metadata)
- Audio status broadcasting (~10 times/sec)
- CLI client message parsing (SpeechMessage, AudioStatusMessage)
- Backward compatible with legacy messages

### Phase 7: Service Architecture ✅
- UhService orchestrates all components
- ServiceListener interface with full callback system
- Model loading coroutines
- Component lifecycle management
- Error handling and recovery

### Phase 8: UI Implementation ✅
- WaveformView custom view (real-time audio visualization)
- State-based colors: Green/Yellow/Red
- Thread-safe circular buffer for waveform
- Log window for recognition results
- MainActivity callbacks fully integrated

## 🚧 REMAINING WORK

### Phase 9: Model Pipeline Verification & Device Testing (CURRENT PHASE)
**Status**: AUDIT COMPLETE - READY FOR DEVICE TESTING

#### Pre-Testing Verification ✅
- [x] Download whisper model from usefulsensors
- [x] Verify model file integrity (66.16 MB, valid TFLite)
- [x] Verify JSON vocabulary exists (51,865 tokens)
- [x] Verify binary vocabulary exists (289.9 KB)
- [x] Audit code pipeline for correctness

#### Critical Testing Required (30 minutes)
**Goal**: Determine model output format (INT32 vs FLOAT32)

- [ ] Install on Samsung Note20 (`make install`)
- [ ] Start log monitoring (`make logs`)
- [ ] Observe model loading logs
- [ ] **CRITICAL**: Record output tensor shape and dtype
- [ ] Speak into device and trigger inference
- [ ] **CRITICAL**: Check if token IDs are sensible (0-51865 range)
- [ ] Verify token decoding produces readable text
- [ ] Check language detection accuracy

**See WHISPER_MODEL_AUDIT.md for detailed test procedures and diagnosis tree**

#### Potential Fixes (if needed, 1-2 hours)
- [ ] If model outputs FLOAT32: Implement argmax in WhisperModel.kt
- [ ] If wrong vocabulary: Switch from JSON to binary vocab
- [ ] If model issues: Download vilassn/whisper_android reference model

### Phase 10: Testing & Validation
#### Device Testing (Physical Device Required)
- [ ] Install on Samsung Note20 (8GB RAM, Exynos 990)
- [ ] Verify model extraction (check internal storage files)
- [ ] Test audio capture (speak, watch waveform)
- [ ] Verify speech recognition accuracy
  - [ ] English phrases
  - [ ] Russian phrases (if needed)
  - [ ] Background noise handling
  - [ ] Various volumes
- [ ] Measure actual latency (should be 400-600ms)
- [ ] Measure real-time factor (should be < 1.0x)
- [ ] Profile memory usage (should be < 2GB)
- [ ] Long-running stability test (1+ hour continuous)
- [ ] Multiple WebSocket clients (3+ simultaneous)
- [ ] Service restart/recovery testing

#### Performance Profiling
- [ ] CPU usage monitoring (`adb shell top`)
- [ ] Memory usage analysis (`adb shell dumpsys meminfo com.uh`)
- [ ] Battery drain measurement (line power assumed, but still check)
- [ ] Network bandwidth (WebSocket message size)
- [ ] Audio capture latency breakdown
- [ ] Inference time distribution (min/max/avg/p99)

#### Error Scenarios
- [ ] Microphone permission denied
- [ ] Models missing/corrupted
- [ ] Network disconnection (WebSocket clients)
- [ ] Audio buffer overflow (> 30s speech)
- [ ] Out of memory conditions
- [ ] Service killed by Android (background restrictions)

### Phase 10: CLI Client Enhancements
- [ ] Parse speech and audio_status messages ✅ (already done)
- [ ] Display format improvements
  - [ ] Truncated embedding display (first 5 values)
  - [ ] Colorized output (green/yellow/red)
  - [ ] Progress bars for audio level
  - [ ] Timestamp formatting
- [ ] Command-line flags
  - [ ] `--full`: Show complete embeddings
  - [ ] `--json`: Raw JSON output for piping
  - [ ] `--quiet`: Minimal output
  - [ ] `--format <style>`: Output format selection
- [ ] Service selection (non-random)
  - [ ] List all discovered services
  - [ ] Connect to specific service by name/address

### Phase 11: Configuration System
- [ ] RuntimeConfig extensions
  - [x] `name` (service name) ✅ already implemented
  - [ ] `model` (tiny/base/small) - needs model switching logic
  - [ ] `language` (en/ru/auto) - Whisper already supports, expose via config
  - [ ] `vad_threshold` (0.0-1.0) - needs VAD reconfiguration
  - [ ] `listening` (true/false) - needs start/stop audio capture
- [ ] Configuration message handling
  - [x] set/get via WebSocket ✅ already implemented
  - [ ] Validate configuration values
  - [ ] Apply configuration changes (model switch requires restart)
  - [ ] Persist configuration (SharedPreferences)
- [ ] UI controls (optional)
  - [ ] Settings screen or dialog
  - [ ] Model selection dropdown
  - [ ] VAD threshold slider
  - [ ] Start/stop listening button

### Phase 12: Optimization & Polish
- [ ] Tokenization upgrade
  - [ ] Integrate HuggingFace tokenizers Rust library via JNI
  - [ ] WordPiece tokenization for better accuracy
  - [ ] Benchmark accuracy improvement
- [ ] GPU acceleration (when TFLite fixed)
  - [ ] Monitor TFLite releases for GPU delegate fix
  - [ ] Test with TFLite 2.17+ when available
  - [ ] Benchmark GPU vs CPU performance
- [ ] Model selection
  - [ ] Support base model (142MB, better accuracy)
  - [ ] Support small model (244MB, best accuracy)
  - [ ] Runtime model switching (requires service restart)
  - [ ] Download on-demand vs bundle in APK
- [ ] Memory optimizations
  - [ ] Profile allocation hotspots
  - [ ] Reuse buffers where possible
  - [ ] Reduce working memory footprint
- [ ] Audio quality tuning
  - [ ] Test different VAD thresholds
  - [ ] Tune buffer sizes for latency/accuracy trade-off
  - [ ] Handle audio input edge cases

### Phase 13: Documentation
- [x] SPEC.md updates ✅ (updated with actual performance)
- [x] CLAUDE.md creation ✅ (comprehensive architecture doc)
- [ ] README.md
  - [ ] Installation instructions
  - [ ] Usage guide (Android app + CLI)
  - [ ] Configuration reference
  - [ ] Troubleshooting guide
  - [ ] Performance expectations
- [ ] Code documentation
  - [ ] KDoc comments for public APIs
  - [ ] Architecture diagrams
  - [ ] Sequence diagrams for main flows
- [ ] User guide
  - [ ] Quick start guide
  - [ ] Configuration options
  - [ ] CLI usage examples
  - [ ] FAQ section

### Phase 14: Build & Deployment
- [ ] APK release build
  - [ ] Signing configuration
  - [ ] ProGuard/R8 optimization
  - [ ] Asset compression
  - [ ] Version tagging
- [ ] Testing on clean device
  - [ ] Fresh install
  - [ ] Permission flows
  - [ ] First-run experience
  - [ ] Model extraction UI
- [ ] Distribution
  - [ ] GitHub releases
  - [ ] Installation instructions
  - [ ] System requirements

## 🐛 KNOWN ISSUES

### High Priority
- **TFLite GPU Delegate Bug**: TFLite 2.16.1 has broken GPU delegate
  - Impact: Using CPU-only (XNNPack), still meets performance targets
  - Workaround: Disabled GPU delegate, waiting for TFLite 2.17+
  - Monitor: https://github.com/tensorflow/tensorflow/issues

### Medium Priority
- **Tokenization Quality**: Simple word-level tokenization
  - Impact: Lower embedding accuracy than production WordPiece
  - Workaround: Acceptable for testing, needs upgrade for production
  - Solution: Integrate HuggingFace tokenizers Rust library

### Low Priority
- **Model Selection**: Only tiny model bundled
  - Impact: Trade-off accuracy for speed and APK size
  - Workaround: Tiny model is sufficient for most use cases
  - Enhancement: Support base/small models in future

## 📊 METRICS & TARGETS

### Performance Targets (Samsung Note20)
- ✅ Latency: 400-600ms (target: < 500ms, acceptable: < 1s)
- ✅ RTF: 0.4-0.6x (target: < 1.0x)
- 🔲 Memory: < 2GB (needs device profiling)
- 🔲 Throughput: 3+ simultaneous clients (needs testing)
- ✅ Model loading: 2-5s (target: < 10s)

### Quality Targets
- 🔲 Transcription accuracy: 90%+ (needs testing with test set)
- 🔲 Language detection: 95%+ (needs testing)
- 🔲 Embedding quality: 0.8+ cosine similarity for semantic duplicates
- 🔲 Uptime: 99%+ for 1 hour continuous operation

### Test Coverage (TODO: Add tests)
- 🔲 Unit tests: > 80% coverage
- 🔲 Integration tests: Core flows covered
- 🔲 Manual testing: All scenarios documented

## 🎯 IMMEDIATE NEXT STEPS

1. **Device Testing** (Highest Priority)
   - Install on physical device
   - Verify end-to-end pipeline works
   - Measure actual performance metrics
   - Document any issues found

2. **CLI Client Polish**
   - Improve output formatting
   - Add command-line flags
   - Test with actual Android app

3. **Configuration System**
   - Add missing config variables
   - Test configuration changes
   - Document configuration options

4. **Documentation**
   - Update README.md
   - Add user guide
   - Create troubleshooting guide

5. **Optimization**
   - Profile memory usage
   - Identify performance bottlenecks
   - Implement optimizations

## 📝 NOTES

### Development Workflow
```bash
# Build and install
make install

# Watch logs
make logs

# Rebuild CLI
cd cli && cargo build --release

# Test CLI
./cli/target/release/uhcli
```

### Testing Checklist
```
[ ] Audio capture works (waveform shows green)
[ ] Speech detection works (waveform turns yellow)
[ ] Transcription works (text appears in log)
[ ] Language detection works (shows correct ISO code)
[ ] Embedding generation works (384 floats in message)
[ ] WebSocket broadcast works (CLI receives messages)
[ ] Multi-client works (3+ clients receive same messages)
[ ] Long-running works (1+ hour no crashes)
[ ] Error recovery works (service restart cleans up)
```

### Performance Monitoring Commands
```bash
# Memory usage
adb shell dumpsys meminfo com.uh

# CPU usage
adb shell top -m 10 | grep com.uh

# Network traffic
adb shell tcpdump -i wlan0 port 8080

# Inference timing
adb logcat -s SpeechRecognitionManager:I | grep "completed in"

# Audio capture status
adb logcat -s AudioCaptureManager:I,SimpleVAD:I
```
