# CLAUDE.md

We are a team of high qualified AI developers.
Create functional, production-ready services with concise, highly optimized idiomatic code.
This is your code - take responsibility for its quality and completeness.
Code must be correct, efficient, comprehensive, and elegant
Write code and comments for AI consumption: explicit, unambiguous, clearly separated, predictable patterns, consistent typing
Always finish functionality - log unimplemented features with errors to log.
Ask before creating unasked-for functionality.
Challenge my decisions if you disagree - argue your position.
If no good solution exists, say so directly.
When plan big kommon known functionality, search internet for ready libraries.
se only english language and math in memory.
Express your self and enjoy the work.


## Files
Each module has its own directory and may contain following files, use it instead of CLAUDE.md:
- prog.md - general rules
- SPEC.md - specifications
- MEMO.md - information about development
- TODO.md - list of tasks to do, complete tasks one by one, mark finished.

Read files if you didn't. Maintain it on AI comprehensive maner.
Use git commits to document project history and decisions.


## Architecture (v2.0 - Android STT)

```
Android SpeechRecognizer API
    -> EnhancedAndroidSpeechRecognizer (continuous, auto-restart)
        -> EchoWireService (foreground service, RecognitionEventListener)
            -> EchoWireWebSocketServer (broadcast JSON to all clients)
            -> MdnsAdvertiser (_echowire._tcp.local.)
            -> MainActivity (UI: waveform, dB meter, log)
```

No ML models, no embeddings, no TFLite, no ONNX. Pure platform STT.

### Source Structure
```
com.echowire/
├── service/EchoWireService.kt    # Foreground service, STT -> WebSocket
├── ml/EnhancedAndroidSpeechRecognizer.kt  # Android STT wrapper
├── network/EchoWireWebSocketServer.kt   # WebSocket server + protocol
├── network/MdnsAdvertiser.kt      # mDNS service advertisement
├── config/EchoWireConfig.kt      # Static config
├── config/RuntimeConfig.kt       # Runtime config (WebSocket commands)
├── ui/MainActivity.kt            # Main UI
├── ui/WaveformView.kt            # Audio waveform visualization
└── ui/DbMeterView.kt             # dB meter visualization
```

### WebSocket Protocol (see ANDROID_STT_PROTOCOL.md)
- `hello` — handshake on connect (device_name, protocol_version)
- `audio_level` — RMS dB, 20-33 Hz
- `partial_result` — real-time transcription during speech
- `final_result` — complete result with alternatives + confidence
- `recognition_event` — state changes (ready, speech_start, speech_end)
- `recognition_error` — error with auto-restart info

### Key Facts
- APK: ~12MB (no bundled models)
- Latency: 100-300ms (platform STT)
- Startup: instant (no model loading)
- Target device: Samsung Note20, Exynos 990, Android 12
- Dependencies: Java-WebSocket 1.5.5, AndroidX
