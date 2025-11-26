# UH - Android Real-Time Speech Recognition Service

## Overview
Android application that performs continuous on-device speech recognition, generates text embeddings, and broadcasts recognized phrases with embeddings to multiple WebSocket clients via mDNS-discoverable service.

## Architecture
Real-time audio processing pipeline:
```
Microphone → TensorFlow Lite Whisper (Speech→Text) → ONNX Embeddings (Text→Vector) → WebSocket Broadcast
```

## Subprojects

### UH Android App (`/app`)
Android application providing:
- Continuous speech recognition (TensorFlow Lite Whisper tiny/base/small model)
- Text embedding generation (all-MiniLM-L6-v2 ONNX, 384 dimensions)
- WebSocket server for streaming results
- mDNS service advertisement

### UH CLI (`/cli`)
Rust command-line client for discovering and connecting to UH services, receiving real-time transcription and embeddings.

## Technical Requirements

### Speech Recognition
- **Engine**: TensorFlow Lite with Whisper ONNX-converted models
- **Hardware Acceleration**: 
  - GPU Delegate (Mali-G77 optimized)
  - XNNPack for ARM CPU optimization
  - NNAPI delegate for Samsung NPU (Exynos 990)
- **Model**: Tiny (39MB), Base (74MB), or Small (244MB) - configurable
- **Mode**: Continuous listening with Voice Activity Detection (VAD)
- **Latency Target**: <500ms end-to-end (audio capture → broadcast)
- **Real-time Factor**: 0.2-0.3x with GPU delegate (1 sec audio → 200-300ms processing)
- **Languages**: Multilingual (99 languages including English, Russian) with automatic detection
- **Audio**: 16kHz mono, continuous capture while service running
- **Preprocessing**: PCM audio → mel spectrogram (80 bins, 25ms window, 10ms hop)

### Text Embeddings
- **Model**: all-MiniLM-L6-v2 (384 dimensions, 86MB ONNX format)
- **Framework**: ONNX Runtime Android (Microsoft onnxruntime-android)
- **Tokenization**: Rust-based HuggingFace tokenizers via JNI
- **Purpose**: Semantic search, similarity matching, vector database ingestion
- **Latency**: ~50ms per phrase on ARM CPU
- **Pooling**: Mean pooling (SBERT standard)

### mDNS Service
- Service type: `_uh._tcp.local.`
- Advertises WebSocket server port
- Service name: Configurable (default: "UH Speech Service")

### WebSocket Server
- Protocol: WebSocket server mode
- Port: Dynamic (start from 8080, find first available)
- Multiple simultaneous client connections
- Fire-and-forget broadcast model (no client tracking)
- Clients may miss events (no buffering/replay)

### Data Streams
- **Speech Recognition Results**: Broadcast as phrases are recognized (streaming)
- **Ping**: Sent every 5 seconds to maintain connection health
- Format: JSON for all non-control messages

### Message Format

**Speech Recognition Result:**
```json
{
  "type": "speech",
  "text": "recognized phrase",
  "embedding": [0.123, -0.456, ...],
  "timestamp": 1234567890123,
  "segment_start": 0.5,
  "segment_end": 2.3,
  "confidence": 0.92
}
```

**Audio Status:**
```json
{
  "type": "audio_status",
  "listening": true,
  "audio_level": 0.45,
  "timestamp": 1234567890123
}
```

**Ping:**
```
WebSocket ping frame (non-JSON)
```

### Configuration System
- Runtime configuration modifiable via WebSocket messages
- Any client can send configuration commands (secure network assumed)
- Message format: `{"configure": "variable", "value": "optional"}`
  - If `value` is provided: sets the config and returns new value
  - If `value` is omitted: returns current value
- Response format: `{"configure": "variable", "value": "current_value"}`
- Available configuration variables:
  - `name`: Service name displayed on UI (default: "UH Speech Service")
  - `model`: Whisper model selection ("tiny", "base", "small" - default: "tiny")
  - `language`: Recognition language code or "auto" for detection (default: "auto")
  - `vad_threshold`: Voice activity detection threshold 0.0-1.0 (default: 0.02)
  - `listening`: Enable/disable continuous listening ("true"/"false")

### User Interface
- **Service Name**: Displays configurable service name (default: "UH Speech Service")
- **Listening Indicator**: Boolean display showing microphone active state
- **Audio Level Meter**: Real-time audio input level visualization
- **Recognition Display**: Scrollable text view showing recognized phrases in real-time
- **Connection Indicator**: Boolean display showing active client count > 0
- **Log Window**: Scrollable text view showing:
  - Client connection/disconnection events
  - Speech recognition events
  - Configuration changes
  - Model loading status
  - Errors and warnings

### Model Management
- **Bundling Strategy**: Models bundled in APK as assets (no network dependency)
- **Models Included**:
  - Whisper tiny multilingual TFLite: ~39MB (99 languages, built-in detection)
  - all-MiniLM-L6-v2 ONNX: ~86MB (text embeddings)
  - Tokenizer config: ~455KB (HuggingFace format)
- **Total APK Size**: +177MB (acceptable for enterprise/testing use case)
- **Storage**: Extracted from assets to internal storage on first run
  - Location: `/data/data/com.uh/files/models/`
  - Extraction: One-time, takes 2-5 seconds
- **Loading**: Models loaded into memory when service starts (5-10 seconds)
- **Runtime Switching**: Support model swapping via configuration (requires service restart)
- **Benefits**: Instant availability, no download delays, no network errors

## CLI Client Requirements
- Discovers all UH services via mDNS (`_uh._tcp.local.`)
- Lists discovered services with details (name, address, port)
- Connects to a randomly selected service (or specific service if specified)
- Multiple operation modes:
  - **Listen mode** (default): Receives and displays speech recognition results with timestamps
  - **Set mode**: Sends configuration command to set a value
  - **Get mode**: Sends configuration command to retrieve current value
- Display format options:
  - **Text only**: Show recognized text with timestamps
  - **Full**: Show text, embeddings (truncated), confidence scores
  - **JSON**: Raw JSON messages for piping to other tools
- Clean output format for human consumption
- Handles connection failures and reconnection
- Ctrl+C for clean shutdown

### CLI Usage
```bash
# Listen to speech recognition (default behavior)
uhcli
uhcli listen

# Listen with full output (including embeddings preview)
uhcli listen --full

# Listen with JSON output (for piping)
uhcli listen --json

# Set configuration value
uhcli set model=small
uhcli set listening=true
uhcli set vad_threshold=0.5

# Get configuration value
uhcli get model
uhcli get listening
```

## Non-Functional Requirements
- Service must run in foreground with FOREGROUND_SERVICE_TYPE_MICROPHONE (Android 8+ requirement)
- Clean shutdown on app termination
- Proper resource cleanup (audio, models, sockets, threads)
- No ANR (background threads for audio/ML operations)
- CLI client handles network interruptions gracefully
- Audio processing must maintain real-time performance (<1.0x real-time factor)
- Model loading must not block UI (show progress)
- Memory usage must stay under 2GB (plenty of headroom on 8GB device)
- Wake lock keeps screen on while service running (line power assumption)

## Performance Targets (Samsung Note20, Exynos 990, Mali-G77, 8GB RAM)
- **Latency**: <500ms end-to-end (audio capture → WebSocket broadcast)
- **Real-time Factor**: <0.3x with GPU delegate (process 1 second audio in <300ms)
- **Throughput**: Support 3+ simultaneous WebSocket clients without degradation
- **Memory**: <2GB total (models + runtime + buffers)
- **Model Loading**: <10 seconds initial load (extraction + TFLite initialization)
- **Audio Capture**: 16kHz mono, <100ms buffering
- **GPU Acceleration**: Mali-G77 MP11 via TFLite GPU delegate
- **CPU Fallback**: XNNPack ARM optimizations if GPU unavailable
- **NPU Option**: Samsung NPU via NNAPI delegate (experimental)

## Security Considerations
- **Network**: Assumed secure local network (no authentication/encryption)
- **Configuration**: Any WebSocket client can modify configuration (intentional for testing)
- **Audio Privacy**: Microphone access clearly indicated in UI and notification
- **Model Integrity**: Models bundled in APK, verified by Android package signing

## Future Considerations
- Speaker diarization (who is speaking)
- Multiple model support (download additional base/small models on-demand)
- Authentication/authorization for configuration changes
- TLS/encryption for WebSocket connections
- FP16 quantization for 2x faster inference
- INT8 quantization for 4x smaller models
- Custom wake word detection
- Audio recording and replay
- Offline model training/fine-tuning on device
- Vector database integration
- CLI client service selection (non-random)
- CLI client TUI interface with real-time updates
- Model extraction progress in UI
- Error recovery and automatic reconnection
- Vulkan GPU delegate (alternative to OpenGL)
- Hexagon DSP acceleration (if Qualcomm hardware available)
