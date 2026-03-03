# Build Status & Testing Guide

## ✅ Build Status: SUCCESSFUL

**Date:** 2024-11-30  
**Branch:** master  
**Commit:** b4fa676  

## Build Configuration

**Java Version Required:** Java 17 or 21 (LTS versions)
- ✅ Java 21 configured in Makefile
- ❌ Java 25 not supported (Kotlin compiler incompatibility)

**Build Command:**
```bash
# Using Makefile (recommended)
make build

# Or manually with Gradle
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew assembleDebug
```

**Build Output:**
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Size: ~177MB (includes bundled models)

## Installation

### Quick Install
```bash
# Build and install to connected device
make install
```

### Manual Install
```bash
# Check device connected
adb devices

# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Verify Installation
```bash
# Check if app is installed
adb shell pm list packages | grep com.echowire
```

Expected output: `package:com.echowire`

## Testing Checklist

### 1. Initial Launch
```bash
# View logs
make logs

# Expected logs (within 10 seconds):
# - "Service created"
# - "Models already extracted" OR "Extracting models from assets..."
# - "All models extracted successfully"
# - "Loading models..."
# - "Models initialized successfully"
# - "WebSocket server started on port XXXX"
# - "Audio capture initialized"
# - "Listening started - microphone active"
```

### 2. Speech Recognition Test
**Action:** Speak into the device microphone

**Expected logs:**
```
Speech detected: level=0.XX, buffer=X.Xs
Processing audio: XXXX samples, X.Xs
Preprocessing: XX frames in XXms
Inference: 448 tokens in XXXms
Decoding: "your phrase" in Xms
Language: en
Embedding: 384 dims in XXms
Transcription: "your phrase"
  Language: en
  Audio duration: XXXms
  Processing time: XXXms (RTF: X.XX)
  Embedding: 384 dimensions (first 5: [X.XXXX, ...])
Broadcast speech message: your phrase...
```

### 3. WebSocket Client Test
```bash
# Build CLI client
cd cli && cargo build --release

# Run CLI
cargo run --release

# Or use installed binary
./target/release/uhcli
```

**Expected CLI output:**
```
EchoWire CLI - WebSocket Client
=========================

Discovering services (5s timeout)...

  Found: UH_Service._uh._tcp.local. at hostname:8080

Discovered 1 service(s):

  [1] UH_Service._uh._tcp.local.
      Host: hostname
      Port: 8080
      Addresses: [192.168.1.XXX]

Randomly selected: UH_Service._uh._tcp.local.

Connecting to ws://192.168.1.XXX:8080/...
Connected!

Receiving messages (Ctrl+C to stop):

[12:34:56.789] Audio: LISTENING | Level: ██████              30.5%
[12:34:57.123] Speech [en] (650ms, RTF=0.65): "hello world"
      Embedding: [0.1234, -0.5678, 0.9012, -0.3456, 0.7890...] (384 dims)
```

### 4. Performance Verification

**Latency Target:** <500ms end-to-end ✓
```bash
# Check RTF (Real-Time Factor) in logs
make logs | grep RTF

# RTF should be < 1.0 (ideally 0.4-0.6)
# RTF = 0.65 means 1 second of audio processed in 650ms
```

**Memory Target:** <2GB
```bash
# Check memory usage
adb shell dumpsys meminfo com.echowire

# Look for "TOTAL PSS:" should be < 2000000 (2GB)
```

### 5. Audio Quality Test

**Test different scenarios:**
1. **Clear speech:** "Hello, how are you today?"
2. **Background noise:** Speak with music/TV in background
3. **Different volumes:** Whisper, normal, loud speech
4. **Different languages:** Try Russian if available
5. **Long phrases:** Speak for 5-10 seconds continuously

**Check accuracy:**
- Text matches what you said?
- Language detected correctly?
- Reasonable RTF (not degrading over time)?

### 6. Multi-Client Test

**Start multiple CLI clients:**
```bash
# Terminal 1
cd cli && cargo run --release

# Terminal 2
cd cli && cargo run --release

# Terminal 3
cd cli && cargo run --release
```

**Verify:**
- All clients receive same messages
- No crashes or errors
- Performance doesn't degrade

### 7. Long-Running Stability Test

**Goal:** Run for 1+ hour without issues

```bash
# Start logging to file
make logs > test_logs.txt

# Speak periodically (every few minutes)
# Let it run for 1 hour

# After 1 hour, check:
grep -i "error\|crash\|exception" test_logs.txt
# Should be minimal/none

# Check memory hasn't grown significantly
adb shell dumpsys meminfo com.echowire
```

## Common Issues & Solutions

### Issue: Java version error
**Error:** `java.lang.IllegalArgumentException: 25.0.1`

**Solution:**
```bash
# Use Java 21
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home make build
```

### Issue: No device connected
**Error:** `ERROR: No device connected`

**Solution:**
```bash
# Check USB connection
adb devices

# If no devices shown:
# 1. Enable USB debugging on Android device
# 2. Accept USB debugging prompt on device
# 3. Try different USB cable/port
```

### Issue: Models not loading
**Error:** "Whisper model not found" or "Embedding model not found"

**Solution:**
```bash
# Check model files in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep models

# Should show:
# - assets/models/whisper_tiny.tflite
# - assets/models/whisper_vocab.json  
# - assets/models/all-MiniLM-L6-v2.onnx
# - assets/models/tokenizer.json

# If missing, rebuild from clean:
make clean
make build
make install
```

### Issue: No audio capture
**Error:** "Microphone permission denied"

**Solution:**
- Go to Settings → Apps → EchoWire → Permissions
- Grant Microphone permission
- Restart app

### Issue: WebSocket connection refused
**Error:** CLI can't connect

**Solution:**
```bash
# Check service is running
adb shell ps | grep com.echowire

# Check WebSocket port
make logs | grep "WebSocket server started"

# Check device IP
adb shell ip addr show wlan0 | grep inet

# Ensure CLI and device on same network
```

## Performance Benchmarks (Target Device: Samsung Note20)

**Hardware:**
- SoC: Exynos 990
- CPU: ARM64-v8a with NEON
- GPU: Mali-G77 MP11 (not used - TFLite GPU broken)
- RAM: 8GB

**Expected Performance:**
- Model loading: 5-10 seconds (one-time)
- Audio capture: <100ms latency
- Preprocessing: <100ms per second of audio
- Whisper inference: 400-600ms per second (XNNPack CPU)
- Token decoding: <5ms
- Embedding generation: ~50ms
- **Total end-to-end: 550-750ms** ✓

**Real-Time Factor (RTF):**
- Target: <1.0 (faster than real-time)
- Expected: 0.4-0.6 with XNNPack
- Acceptable: <0.8

## Test Results Template

```
Date: YYYY-MM-DD
Device: Samsung Note20 (Exynos 990)
Android Version: 12
Build: app-debug.apk

[ ] App installs successfully
[ ] Models load without errors (< 10 seconds)
[ ] Audio capture starts automatically
[ ] Speech recognized accurately
[ ] Embeddings generated (384 dims)
[ ] WebSocket broadcasting works
[ ] CLI client connects and displays messages
[ ] Audio level visualization works
[ ] Language detection works (en/ru/etc)
[ ] Performance meets targets (RTF < 1.0)
[ ] Memory usage reasonable (< 2GB)
[ ] Multi-client support works
[ ] No crashes after 1 hour runtime

Average Latency: ___ms
Average RTF: ___
Memory Usage: ___MB
Accuracy: ___% (subjective)

Issues Found:
-

Notes:
-
```

## Next Steps After Testing

If tests pass:
1. ✅ Document any performance issues
2. ✅ Optimize if needed (Phase 11)
3. ✅ Add remaining UI features (Phase 8)
4. ✅ Production release (Phase 12)

If tests fail:
1. 🔧 Check logs for errors
2. 🔧 Verify all models extracted
3. 🔧 Check permissions granted
4. 🔧 Report issues with logs

## Contact & Support

**Documentation:**
- SPEC.md - Project requirements
- TODO.md - Implementation checklist
- CLAUDE.md - Technical deep dive
- PHASE_5_6_SUMMARY.md - Recent changes

**Logs:**
```bash
# Real-time logs
make logs

# Save logs to file
make retrieve-logs
# Saves to: logs/uh_logs_YYYYMMDD_HHMMSS.txt
```

**Git History:**
```bash
git log --oneline --graph --all -20
```

---

**Status:** Ready for device testing! 🚀
