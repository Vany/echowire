.PHONY: help clean build install uninstall logs logs-all retrieve-logs start-service stop-service cli-build cli-run cli-clean

# Java configuration - Gradle runs on Java 21, compiles with Java 25
JAVA_21_HOME := /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
JAVA_25_HOME := /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
export JAVA_HOME := $(JAVA_21_HOME)

# Android SDK location
export ANDROID_HOME := $(HOME)/Library/Android/sdk

GRADLE := ./gradlew
APK_PATH := app/build/outputs/apk/debug/app-debug.apk
PACKAGE := com.uh
CLI_DIR := cli
CLI_BINARY := $(CLI_DIR)/target/release/uhcli

help:
	@echo "UH Android App - Makefile Targets"
	@echo "=================================="
	@echo "Android App:"
	@echo "  build         - Build debug APK"
	@echo "  install       - Build and install to connected device"
	@echo "  uninstall     - Uninstall from device"
	@echo "  clean         - Clean build artifacts"
	@echo "  logs          - Show logcat filtered for UH app only"
	@echo "  logs-all      - Show all Android runtime errors (all apps)"
	@echo "  retrieve-logs - Retrieve and save last logs to file"
	@echo "  start-service - Start UH service on device"
	@echo "  stop-service  - Stop UH service on device"
	@echo ""
	@echo "CLI Client (Rust):"
	@echo "  cli-build     - Build uhcli (release mode)"
	@echo "  cli-run       - Build and run uhcli"
	@echo "  cli-clean     - Clean CLI build artifacts"

clean:
	@echo "Cleaning Android build artifacts..."
	$(GRADLE) clean
	@echo "Cleaning CLI build artifacts..."
	cd $(CLI_DIR) && cargo clean

build:
	@echo "Building debug APK..."
	$(GRADLE) assembleDebug

install: build
	@echo "Installing to device..."
	@adb devices | grep -q "device$$" || (echo "ERROR: No device connected" && exit 1)
	adb install -r $(APK_PATH)
	@echo "Installation complete!"

uninstall:
	@echo "Uninstalling $(PACKAGE)..."
	adb uninstall $(PACKAGE) || echo "App not installed"

logs:
	@echo "Starting logcat (filtered for UH)..."
	adb logcat -s UhService:* UhWebSocketServer:* MainActivity:* | grep -E "(UhService|UhWebSocketServer|MainActivity|$(PACKAGE))"

logs-all:
	@echo "Starting logcat (all Android runtime errors)..."
	adb logcat -s UhService:* UhWebSocketServer:* MainActivity:* AndroidRuntime:E

retrieve-logs:
	@echo "Retrieving last logs from device..."
	@mkdir -p logs
	@TIMESTAMP=$$(date +%Y%m%d_%H%M%S); \
	LOGFILE="logs/uh_logs_$$TIMESTAMP.txt"; \
	adb logcat -d -s UhService:* UhWebSocketServer:* MainActivity:* | grep -E "(UhService|UhWebSocketServer|MainActivity|$(PACKAGE))" > $$LOGFILE; \
	echo "Logs saved to: $$LOGFILE"; \
	echo "Total lines: $$(wc -l < $$LOGFILE | tr -d ' ')"

start-service:
	@echo "Starting UH service..."
	adb shell am start-foreground-service -n $(PACKAGE)/.UhService

stop-service:
	@echo "Stopping UH service..."
	adb shell am stopservice $(PACKAGE)/.UhService

# CLI Client targets

cli-build:
	@echo "Building CLI client (release mode)..."
	cd $(CLI_DIR) && cargo build --release
	@echo "CLI binary: $(CLI_BINARY)"

cli-run:
	@echo "Building and running CLI client..."
	cd $(CLI_DIR) && cargo run --release

cli-clean:
	@echo "Cleaning CLI build artifacts..."
	cd $(CLI_DIR) && cargo clean
