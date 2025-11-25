# CLAUDE Memory - UH Project

## Project Context
- Target: Android 12 (API 31+)
- Development: macOS 26
- Language: Kotlin (preferred for Android)
- Architecture: Service-based with Activity UI

## Key Technical Decisions

### WebSocket Implementation
- Library: Java-WebSocket (org.java-websocket:Java-WebSocket) - mature, simple API
- Alternative considered: OkHttp WebSocket (more complex, requires separate server setup)
- Server runs on background thread, broadcasts to all clients without tracking

### mDNS Implementation  
- Android NsdManager (native Android API)
- No external dependencies needed
- Service type: `_uh._tcp.local.`

### Threading Model
- Main thread: UI updates only
- Service thread: WebSocket server
- Scheduled executor: Random number generation (1s), ping (5s)
- No synchronization needed - broadcast is fire-and-forget

### Message Format
```
Random: {"type":"random","value":123456,"timestamp":1234567890123}
Ping: WebSocket frame-level ping (not JSON)
```

## Android Specifics
- **Foreground Service**: Required for Android 8+ to prevent killing
- **Permissions**: INTERNET, FOREGROUND_SERVICE
- **Service Type**: FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE (Android 14+)
- **Notification**: Required for foreground service visibility

## Build System
- Gradle 8.x
- Kotlin 1.9.x
- Android Gradle Plugin 8.x
- Target SDK: 34 (Android 14)
- Min SDK: 31 (Android 12)

## Code Style
- Explicit types where ambiguity exists
- Null safety enforced
- Coroutines avoided initially (simpler threading model)
- Direct callback patterns for service-to-UI communication

## Known Limitations
- No client tracking (by design)
- No message replay/history
- No authentication
- Configuration system exists but not exposed in UI
