# TODO - UH Android App

## Phase 1: Project Setup
- [x] Initialize Android project structure
- [x] Configure Gradle build files
- [x] Add required dependencies (WebSocket, mDNS, JSON)
- [x] Set up AndroidManifest.xml with permissions

## Phase 2: Core Services
- [x] Implement WebSocket server
  - [x] Dynamic port selection (8080+)
  - [x] Client connection management
  - [x] Broadcast mechanism
- [x] Implement mDNS service advertisement
  - [x] Service registration with NsdManager
  - [x] Port announcement
- [x] Implement data generation
  - [x] Random number generator (1 sec interval)
  - [x] Ping mechanism (5 sec interval)
  - [x] JSON message formatting

## Phase 3: UI Implementation
- [x] Create main Activity layout
- [x] Implement log window (TextView/RecyclerView)
- [x] Implement connection indicator
- [x] Wire UI updates from service events

## Phase 4: Service Lifecycle
- [x] Foreground service implementation
- [x] Service binding to Activity
- [x] Proper lifecycle management
- [x] Resource cleanup

## Phase 5: Testing & Polish
- [ ] Test multiple client connections
- [ ] Test service restart scenarios
- [ ] Verify no ANR issues
- [ ] Test mDNS discovery from clients
