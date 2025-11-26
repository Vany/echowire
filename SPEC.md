# UH - Android Real-Time Speech Recognition Service

## Overview
Android application that performs continuous on-device speech recognition, generates text embeddings, and broadcasts recognized phrases with embeddings to multiple WebSocket clients via mDNS-discoverable service.

## Architecture
Real-time audio processing pipeline:
```
Microphone → WhisperKit (Speech→Text) → Sentence-Embeddings (Text→Vector) → WebSocket Broadcast
```

## Subprojects

### UH Android App (`/app`)
Android application providing:
- Continuous speech recognition (WhisperKit base/small model)
- Text embedding generation (all-MiniLM-L6-v2, 384 dimensions)
- WebSocket server for streaming results
- mDNS service advertisement

### UH CLI (`/cli`)
Rust command-line client for discovering and connecting to UH services, receiving real-time transcription and embeddings.

## Technical Requirements

### Speech Recognition
- **Engine**: WhisperKit Android with Qualcomm QNN acceleration
- **Model**: Base or Small (configurable, optimized for 8GB RAM)
- **Mode**: Continuous listening with Voice Activity Detection (VAD)
- **Latency Target**: <500ms end-to-end (audio capture → broadcast)
- **Languages**: English (primary), expandable to multilingual models
- **Audio**: 16kHz mono, continuous capture while service running

### Text Embeddings
- **Model**: all-MiniLM-L6-v2 (384 dimensions, 23MB)
- **Framework**: ONNX Runtime via Sentence-Embeddings-Android
- **Purpose**: Semantic search, similarity matching, vector database ingestion
- **Latency**: ~50ms per phrase

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
  - `model`: WhisperKit model selection ("tiny", "base", "small" - default: "base")
  - `language`: Recognition language (default: "en")
  - `vad_threshold`: Voice activity detection threshold 0.0-1.0 (default: 0.3)
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
- **Initial Setup**: App downloads required models on first launch
  - WhisperKit base model (~75MB)
  - all-MiniLM-L6-v2 ONNX model (~23MB)
  - Tokenizer configuration (~500KB)
- **Storage**: Internal app storage (`/data/data/com.yourapp/files/models/`)
- **Loading**: Models loaded when service starts (may take 5-10 seconds)
- **Updates**: Support runtime model switching via configuration

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

## Performance Targets (8GB RAM, Snapdragon 8 Gen 2)
- **Latency**: <500ms end-to-end (audio capture → WebSocket broadcast)
- **Real-time Factor**: <0.5x (process 1 second of audio in <0.5 seconds)
- **Throughput**: Support 3+ simultaneous WebSocket clients without degradation
- **Memory**: <2GB total (models + runtime)
- **Model Loading**: <10 seconds initial load
- **Audio Capture**: 16kHz mono, <100ms buffering

## Security Considerations
- **Network**: Assumed secure local network (no authentication/encryption)
- **Configuration**: Any WebSocket client can modify configuration (intentional for testing)
- **Audio Privacy**: Microphone access clearly indicated in UI and notification
- **Model Integrity**: Models verified via checksum on download

## Future Considerations
- Speaker diarization (who is speaking)
- Multiple language support (multilingual models)
- Authentication/authorization for configuration changes
- TLS/encryption for WebSocket connections
- Model quantization for faster inference
- Custom wake word detection
- Audio recording and replay
- Offline model training/fine-tuning on device
- Vector database integration
- CLI client service selection (non-random)
- CLI client TUI interface with real-time updates
- Model download progress in UI
- Error recovery and automatic reconnection
