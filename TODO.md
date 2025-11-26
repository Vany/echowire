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

## Phase 5: Build & Deployment
- [x] Create Makefile with build/install targets
- [x] Set up Gradle wrapper
- [ ] Test on physical device
- [ ] Verify mDNS discovery
- [ ] Test multi-client connections

## Phase 6: Configuration System
- [x] Create RuntimeConfig class for thread-safe mutable configuration
- [x] Integrate RuntimeConfig with WebSocket server
- [x] Handle configure messages (set/get operations)
- [x] Add "name" configuration variable
- [x] Update UI to display configurable name
- [x] Add config change notifications to ServiceListener
- [x] Document configuration system in SPEC.md and CLAUDE.md

## Phase 7: CLI Client (uhcli)
- [x] Create Rust project structure
- [x] Implement mDNS service discovery
- [x] Implement service listing
- [x] Implement random service selection
- [x] Implement WebSocket client
- [x] Implement message receiving and display
- [x] Implement Ctrl+C handler
- [x] Add CLI to Makefile targets
- [x] Add clap for argument parsing
- [x] Implement listen subcommand (default)
- [x] Implement set subcommand (configure with value)
- [x] Implement get subcommand (configure without value)
- [x] Add response handling with timeout
- [x] Document CLI usage in SPEC.md and CLAUDE.md
- [ ] Test with Android app
