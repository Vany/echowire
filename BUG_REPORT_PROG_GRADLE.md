# Bug Report: prog:gradle Tool

**Date**: 2025-11-26  
**Tool**: `prog:gradle`  
**Severity**: High - breaks Android builds

## Issue
The `prog:gradle` tool executes system-installed gradle binary instead of project's `./gradlew` wrapper.

## Evidence
1. Wrapper config (`gradle/wrapper/gradle-wrapper.properties`): Gradle 8.2
2. `./gradlew --version`: Shows Gradle 8.2 ✓
3. `prog:gradle` output: Shows Gradle 9.2.1 ✗

## Impact
- Build fails with version incompatibility errors
- `NoClassDefFoundError: org/gradle/api/internal/HasConvention`
- AGP 8.2.0 + Kotlin 1.9.20 require Gradle 8.x, not 9.x

## Expected Behavior
`prog:gradle` should execute `./gradlew` in project directory

## Actual Behavior
Executes `/opt/homebrew/bin/gradle` (system installation)

## Workaround
Use `prog:shell` with explicit `./gradlew` command:
```
prog:shell("./gradlew assembleDebug")
```

## Reproduction
```
prog:gradle(["assembleDebug"], project="uh")
# Shows "Welcome to Gradle 9.2.1!" instead of 8.2
```
