# UH - Android WebSocket Random Number Service

## Overview
Android application that advertises itself via mDNS and broadcasts random numbers to multiple WebSocket clients.

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

### User Interface
- **Log Window**: Scrollable text view showing:
  - Client connection/disconnection events
  - Generated random numbers sent
- **Connection Indicator**: Boolean display showing active client count > 0

### Configuration System
- Internal configuration support (not exposed in UI yet)
- Default values used for initial implementation

## Non-Functional Requirements
- Service must run in foreground (Android 8+ requirement)
- Clean shutdown on app termination
- Proper resource cleanup (sockets, threads)
- No ANR (background threads for network operations)

## Future Considerations
- Configuration UI
- Authentication/authorization
- Message history
- Client tracking and statistics
