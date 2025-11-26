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

## Build System
- **Makefile targets**: Simple interface for common operations
  - `make install`: Build debug APK and install to connected device
  - `make build`: Build debug APK only
  - `make clean`: Clean build artifacts
  - `make uninstall`: Remove app from device
  - `make logs`: View filtered logcat output
  - `make start-service`/`stop-service`: Control service via adb
- **Gradle wrapper**: Version 8.2, included in repository
- **Build output**: `app/build/outputs/apk/debug/app-debug.apk`

## CLI Client (uhcli) - Rust Implementation

### Project Structure
- Location: `/cli` subdirectory
- Binary name: `uhcli`
- Rust Edition: 2021

### Key Dependencies
- **tokio**: Async runtime with full features
- **tokio-tungstenite**: WebSocket client (async, works with tokio)
- **mdns-sd**: Pure Rust mDNS/DNS-SD implementation (cross-platform)
- **serde/serde_json**: JSON deserialization for messages
- **rand**: Random service selection
- **anyhow**: Error handling with context
- **chrono**: Timestamp formatting

### Architecture Decisions

**mDNS Discovery:**
- `mdns-sd` crate chosen for pure Rust, cross-platform support
- Discovery timeout: 5 seconds (configurable)
- Browses for `_uh._tcp.local.` service type
- Collects all resolved services with addresses
- Handles service removal events during discovery

**Service Selection:**
- Uses `rand::seq::SliceRandom` for cryptographically secure random selection
- Requires at least one discovered service
- Uses first available IP address from service info

**WebSocket Client:**
- `tokio-tungstenite` for async WebSocket operations
- Split stream pattern: separate read/write halves
- Text messages only (JSON)
- Automatic pong responses to ping frames

**Message Handling:**
- Deserializes JSON to `RandomMessage` struct
- Validates message type == "random"
- Formats timestamps as HH:MM:SS.mmm using chrono
- Falls back to raw message display for parsing errors

**Shutdown Handling:**
- `tokio::signal::ctrl_c()` for graceful Ctrl+C handling
- `tokio::select!` for concurrent message receiving and signal handling
- Clean connection closure on shutdown

### Error Handling Strategy
- `anyhow::Result` for all fallible operations
- Context added at each error point for debugging
- Early return on critical failures (no services, connection failure)
- Continue on non-critical errors (message parse errors)

### Output Format
```
UH CLI - WebSocket Random Number Client
========================================

Discovering services (5s timeout)...

  Found: UH_Service._uh._tcp.local. at hostname:8080

Discovered 1 service(s):

  [1] UH_Service._uh._tcp.local.
      Host: hostname
      Port: 8080
      Addresses: [192.168.1.100]

Randomly selected: UH_Service._uh._tcp.local.

Connecting to ws://192.168.1.100:8080/...
Connected!

Receiving messages (Ctrl+C to stop):

[12:34:56.789] Random: 123456
[12:34:57.790] Random: 789012
...
```

## Known Limitations
- No client tracking (by design)
- No message replay/history
- No authentication
- Configuration system exists but not exposed in UI

## Implementation Notes

### Service Architecture
- **UhService**: Foreground service managing lifecycle
  - Creates notification channel and foreground notification
  - Manages WebSocket server, mDNS registration, scheduled tasks
  - ServiceListener interface for UI callbacks (background thread!)
  
### Threading Strategy
- **Main thread**: UI updates only, runOnUiThread() for callbacks
- **WebSocket thread**: Java-WebSocket library handles internally
- **ScheduledExecutorService**: 2 threads for random number and ping tasks
- **No shared mutable state**: Broadcast is fire-and-forget, connection count is synchronized

### Port Selection Logic
- Iterates from startPort (8080) up to maxPortSearch (100 attempts)
- Uses ServerSocket() probe to test availability
- Port announced via mDNS after selection

### Error Handling
- WebSocket errors: logged, reported to listener, connection auto-closed
- mDNS registration failures: logged but service continues
- Port unavailability: service stops with error
- All exceptions caught at task boundaries

### UI Update Pattern
```kotlin
// Service callbacks arrive on background threads
override fun onClientConnected(address: String, totalClients: Int) {
    runOnUiThread {
        updateConnectionIndicator(totalClients)
        addLog("Client connected: $address")
    }
}
```

### Resource Cleanup
Order matters for clean shutdown:
1. Stop scheduler (no new messages)
2. Unregister mDNS
3. Shutdown WebSocket server (closes connections)
4. Notify listener

## Configuration System

### RuntimeConfig Class
Thread-safe runtime configuration management with change notification.

**Storage:**
- `MutableMap<String, String>` for key-value storage
- Synchronized access for thread safety
- Default values initialized in constructor

**Operations:**
- `set(key, value)`: Sets value and notifies listeners, returns new value
- `get(key)`: Returns current value or null if not exists
- `addListener()/removeListener()`: Observer pattern for change notifications

**WebSocket Integration:**
- `processConfigureMessage(json)`: Parses configure messages, processes set/get, returns response JSON
- Message format: `{"configure": "key", "value": "optional"}`
- Response format: `{"configure": "key", "value": "current_value"}`
- If value provided: sets then returns new value
- If value omitted: returns current value only
- Returns null for invalid/unknown keys

**Configuration Variables:**
- `name`: Service name displayed on UI (default: "UH Service")

**Flow:**
1. Client sends configure message via WebSocket
2. UhWebSocketServer.onMessage() receives message
3. RuntimeConfig.processConfigureMessage() parses and processes
4. If set operation: notifies listeners via ConfigChangeListener
5. Response sent back to requesting client only
6. UI updates via ServiceListener.onConfigChanged() callback

**Thread Safety:**
- All config access synchronized
- Listeners notified on calling thread (WebSocket thread)
- UI updates handled via runOnUiThread() in MainActivity

**Future Extensions:**
- Persistence: Save to SharedPreferences
- Validation: Type checking, value constraints
- More config variables: intervals, thresholds, etc.

