# Bug: percept.aar — unresolvable sherpa-onnx dependency

## Environment

- Project: EchoWire Android app
- `percept.aar` version: (unknown — no version embedded in AAR metadata)
- Gradle: 8.11
- `settings.gradle.kts` repos: `google()`, `mavenCentral()`

## Problem

Build fails when `percept.aar` is added as a local dependency alongside:

```kotlin
implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.28")
```

**Error:**

```
FAILURE: Build failed with an exception.

Execution failed for task ':app:dataBindingMergeDependencyArtifactsDebug'.
> Could not resolve all files for configuration ':app:debugCompileClasspath'.
   > Could not find com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.28.
     Required by: project :app
```

## Root cause

`com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.28` is not published to Maven Central or Google Maven under that group/artifact/version.

Bytecode inspection of `percept.aar/classes.jar` confirms it references:

```
com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
com.k2fsa.sherpa.onnx.Vad
com.k2fsa.sherpa.onnx.OnlineRecognizer
com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
```

The standard sherpa-onnx Android artifact on Maven Central uses a different group/artifact:

```kotlin
// What's on Maven Central:
implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-android:VERSION")
```

## What's needed from you

Please provide **one** of the following:

**Option A** — Correct Maven coordinates + repository URL:
```kotlin
// settings.gradle.kts — which repo to add?
maven { url = uri("???") }

// build.gradle.kts — correct coordinates?
implementation("???:sherpa-onnx:1.12.28")
```

**Option B** — A local `sherpa-onnx.aar` to drop into `app/libs/` alongside `percept.aar`.

## Additional info

The `percept.aar` bundles ONNX model files (~258 MB total) but **no `.so` native libraries**. The native sherpa-onnx runtime (JNI `.so` files for `arm64-v8a` / `armeabi-v7a`) must therefore come from the sherpa-onnx dependency. Without it the app will not link.
