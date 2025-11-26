# UH - Android WebSocket Random Number Service

## Overview
Android application that advertises itself via mDNS and broadcasts random numbers to multiple WebSocket clients.

## Subprojects

### UH Android App (`/app`)
Android application providing the WebSocket server and mDNS service.

### UH CLI (`/cli`)
Rust command-line client for discovering and connecting to UH services.

## Technical Requirements

### mDNS Service
- Service type: `_uh._tcp.local.`
- Advertises WebSocket server port
- Service name: app identifier + instance

### WebSocket Server
- Protocol: WebSocket server mode
- Port: Dynamic (start from 8080, find first available)
- Multiple simultaneous client connections
- No client tracking - fire-and-forget broadcast model
- Clients may miss events (no buffering/replay)

### Data Streams
- **Random Numbers**: Generated every 1 second, broadcast to all connected clients
- **Ping**: Sent every 5 seconds to maintain connection health
- Format: JSON for all non-control messages

### Message Format
```json
{
  "type": "random",
  "value": <number>,
  "timestamp": <unix_timestamp_ms>
}
```

### Configuration System
- Runtime configuration modifiable via WebSocket messages
- Any client can send configuration commands (secure network assumed)
- Message format: `{"configure": "variable", "value": "optional"}`
  - If `value` is provided: sets the config and returns new value
  - If `value` is omitted: returns current value
- Response format: `{"configure": "variable", "value": "current_value"}`
- Available configuration variables:
  - `name`: Service name displayed on UI (default: "UH Service")

### User Interface
- **Service Name**: Displays configurable service name (default: "UH Service")
- **Connection Indicator**: Boolean display showing active client count > 0
- **Log Window**: Scrollable text view showing:
  - Client connection/disconnection events
  - Generated random numbers sent
  - Configuration changes

## CLI Client Requirements
- Discovers all UH services via mDNS (`_uh._tcp.local.`)
- Lists discovered services with details (name, address, port)
- Connects to a randomly selected service
- Receives and displays all broadcast messages
- Clean output format for human consumption
- Handles connection failures and reconnection
- Ctrl+C for clean shutdown

## Non-Functional Requirements
- Service must run in foreground (Android 8+ requirement)
- Clean shutdown on app termination
- Proper resource cleanup (sockets, threads)
- No ANR (background threads for network operations)
- CLI client handles network interruptions gracefully

## Future Considerations
- Configuration UI for Android app
- Authentication/authorization
- Message history
- Client tracking and statistics
- CLI client service selection (non-random)
- CLI client TUI interface with real-time updates
