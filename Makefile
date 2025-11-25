.PHONY: help clean build install uninstall logs start-service stop-service

GRADLE := ./gradlew
APK_PATH := app/build/outputs/apk/debug/app-debug.apk
PACKAGE := com.uh

help:
	@echo "UH Android App - Makefile Targets"
	@echo "=================================="
	@echo "build         - Build debug APK"
	@echo "install       - Build and install to connected device"
	@echo "uninstall     - Uninstall from device"
	@echo "clean         - Clean build artifacts"
	@echo "logs          - Show logcat filtered for UH"
	@echo "start-service - Start UH service on device"
	@echo "stop-service  - Stop UH service on device"

clean:
	@echo "Cleaning build artifacts..."
	$(GRADLE) clean

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
	adb logcat -s UhService:* UhWebSocketServer:* MainActivity:* AndroidRuntime:E

start-service:
	@echo "Starting UH service..."
	adb shell am start-foreground-service -n $(PACKAGE)/.UhService

stop-service:
	@echo "Stopping UH service..."
	adb shell am stopservice $(PACKAGE)/.UhService
