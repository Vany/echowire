# MNN Migration Plan - TFLite to MNN for Mali-G77

**Date:** 2024-11-26  
**Target:** Migrate from TensorFlow Lite (XNNPack CPU) to MNN (Mali GPU)  
**Goal:** 2-3x speedup (400-600ms → 200-300ms for Whisper tiny inference)  
**Device:** Samsung Note20 (Exynos 990, Mali-G77 MP11, Android 12)

---

## Phase 1: Setup & Environment (1-2 hours)

### 1.1 Download MNN Tools

**Option A: Pre-built Binaries (Recommended)**
```bash
# Download latest MNN release from GitHub
# https://github.com/alibaba/MNN/releases
curl -LO https://github.com/alibaba/MNN/releases/download/2.9.0/MNN-2.9.0-Linux.tar.gz
tar -xzf MNN-2.9.0-Linux.tar.gz
cd MNN-2.9.0-Linux

# MNNConvert should be in tools/converter/
ls tools/converter/MNNConvert
```

**Option B: Build from Source (if pre-built doesn't work)**
```bash
git clone https://github.com/alibaba/MNN.git
cd MNN
mkdir build && cd build
cmake .. -DMNN_BUILD_CONVERTER=true
make -j$(nproc)

# MNNConvert will be in build/
ls ../build/MNNConvert
```

**macOS Build:**
```bash
# On your macOS 26 development machine
cd MNN
./build_tool.sh  # Builds everything including converter
```

---

## Phase 2: Model Conversion (30 minutes)

### 2.1 Convert Whisper TFLite to MNN

**Current Model Location:**
- `app/src/main/assets/models/whisper_tiny.tflite` (66MB)

**Conversion Command:**
```bash
cd /path/to/MNN

# Basic conversion
./build/MNNConvert \
  -f TFLITE \
  --modelFile /path/to/echowire/app/src/main/assets/models/whisper_tiny.tflite \
  --MNNModel /path/to/echowire/app/src/main/assets/models/whisper_tiny.mnn \
  --bizCode biz

# With FP16 optimization (recommended for GPU, reduces size ~50%)
./build/MNNConvert \
  -f TFLITE \
  --modelFile /path/to/echowire/app/src/main/assets/models/whisper_tiny.tflite \
  --MNNModel /path/to/echowire/app/src/main/assets/models/whisper_tiny_fp16.mnn \
  --bizCode biz \
  --fp16

# Expected output size: 33MB (with FP16) vs 66MB (original)
```

**Verify Conversion:**
```bash
# Dump MNN model info to JSON for inspection
./build/MNNDump2Json \
  whisper_tiny.mnn \
  whisper_tiny.json

# Check input/output tensors in JSON
cat whisper_tiny.json | grep -A5 "inputs"
cat whisper_tiny.json | grep -A5 "outputs"
```

**Expected Model Specs:**
- Input: `[1, 80, 3000]` (batch, mel bins, time frames)
- Output: `[1, 448]` (batch, token sequence)
- Format: `.mnn` binary (FlatBuffers-based)

### 2.2 Handle Conversion Issues

**Common TFLite → MNN Conversion Problems:**

1. **Unsupported Ops:** MNN supports 58 TFLite ops
   - Check error messages for "NOT_SUPPORTED_OP"
   - Whisper tiny is standard transformer → should work
   - If issues: try ONNX intermediate format

2. **Fused Activation Functions:**
   - Error: `BinaryOP Should not has fused_activation_function`
   - Solution: Re-export TFLite without fused activations

3. **Dynamic Shapes:**
   - MNN prefers static shapes
   - Our model has fixed input `[1, 80, 3000]` → OK

**Fallback: TFLite → ONNX → MNN**
```bash
# If direct TFLite conversion fails, go through ONNX
# Step 1: Install tf2onnx
pip install tf2onnx

# Step 2: Convert TFLite to ONNX
python -m tf2onnx.convert \
  --tflite whisper_tiny.tflite \
  --output whisper_tiny.onnx \
  --inputs-as-nchw input:0

# Step 3: Convert ONNX to MNN
./build/MNNConvert \
  -f ONNX \
  --modelFile whisper_tiny.onnx \
  --MNNModel whisper_tiny.mnn \
  --bizCode biz \
  --fp16
```

---

## Phase 3: Android Integration (2-4 hours)

### 3.1 Add MNN Dependencies

**build.gradle.kts (app module):**
```kotlin
dependencies {
    // Remove or comment out TFLite (keep for comparison initially)
    // implementation("org.tensorflow:tensorflow-lite:2.16.1")
    
    // Add MNN
    implementation("com.alibaba.android:mnn:2.9.0")
    
    // Or use local AAR if Maven version unavailable
    // implementation(files("libs/mnn-release.aar"))
    
    // Keep existing dependencies
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // ... rest
}

android {
    // Prevent compression of MNN models
    aaptOptions {
        noCompress("mnn")
    }
}
```

**Download AAR (if not on Maven Central):**
```bash
# From MNN GitHub releases
curl -LO https://github.com/alibaba/MNN/releases/download/2.9.0/MNN-Android-2.9.0.aar
mv MNN-Android-2.9.0.aar app/libs/mnn-release.aar
```

### 3.2 Create MNN Whisper Model Class

**Location:** `app/src/main/java/com/echowire/ml/MnnWhisperModel.kt`

```kotlin
package com.echowire.ml

import android.content.Context
import android.util.Log
import com.taobao.android.mnn.MNNForwardType
import com.taobao.android.mnn.MNNImageProcess
import com.taobao.android.mnn.MNNNetInstance
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MNN-based Whisper model for speech recognition
 * Replaces TFLite WhisperModel with Mali GPU acceleration
 */
class MnnWhisperModel(
    private val context: Context,
    private val modelFile: File
) {
    companion object {
        private const val TAG = "MnnWhisperModel"
        
        // Model I/O specifications (same as TFLite)
        private const val MEL_BINS = 80
        private const val MAX_FRAMES = 3000
        private const val MAX_TOKENS = 448
        
        // Backend types
        private const val BACKEND_OPENCL = MNNForwardType.FORWARD_OPENCL  // Primary (Mali GPU)
        private const val BACKEND_VULKAN = MNNForwardType.FORWARD_VULKAN  // Alternative
        private const val BACKEND_CPU = MNNForwardType.FORWARD_CPU        // Fallback
    }
    
    private var mnnNet: MNNNetInstance? = null
    private val interpreterLock = ReentrantLock()
    
    @Volatile
    var isLoaded = false
        private set
    
    @Volatile
    var isGpuEnabled = false
        private set
    
    private var backendType: Int = BACKEND_CPU
    
    /**
     * Load model with GPU acceleration (OpenCL for Mali)
     * Falls back to Vulkan, then CPU if GPU unavailable
     */
    fun load() {
        if (isLoaded) {
            Log.w(TAG, "Model already loaded")
            return
        }
        
        if (!modelFile.exists()) {
            throw IllegalStateException("Model file not found: ${modelFile.absolutePath}")
        }
        
        Log.i(TAG, "Loading MNN model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        
        try {
            // Try OpenCL first (best for Mali GPU)
            mnnNet = loadWithBackend(BACKEND_OPENCL)
            backendType = BACKEND_OPENCL
            isGpuEnabled = true
            Log.i(TAG, "✓ MNN loaded with OpenCL (Mali GPU)")
            
        } catch (e: Exception) {
            Log.w(TAG, "OpenCL failed, trying Vulkan", e)
            try {
                // Try Vulkan as alternative
                mnnNet = loadWithBackend(BACKEND_VULKAN)
                backendType = BACKEND_VULKAN
                isGpuEnabled = true
                Log.i(TAG, "✓ MNN loaded with Vulkan (GPU)")
                
            } catch (e2: Exception) {
                Log.w(TAG, "Vulkan failed, falling back to CPU", e2)
                // Fallback to CPU
                mnnNet = loadWithBackend(BACKEND_CPU)
                backendType = BACKEND_CPU
                isGpuEnabled = false
                Log.i(TAG, "✓ MNN loaded with CPU (no GPU)")
            }
        }
        
        // Verify model loaded successfully
        if (mnnNet == null) {
            throw IllegalStateException("Failed to load MNN model")
        }
        
        isLoaded = true
        Log.i(TAG, "Model loaded successfully, GPU: $isGpuEnabled, backend: $backendType")
    }
    
    /**
     * Load model with specific backend
     */
    private fun loadWithBackend(forwardType: Int): MNNNetInstance {
        val config = MNNNetInstance.Config().apply {
            numThread = 4  // Thread count for CPU operations
            forwardType = forwardType
            
            // Memory optimization
            saveTensors = arrayOf<String>()  // Don't save intermediate tensors
        }
        
        return MNNNetInstance.createFromFile(modelFile.absolutePath, config)
            ?: throw IllegalStateException("Failed to create MNN instance")
    }
    
    /**
     * Run inference on mel spectrogram
     * Thread-safe, same API as TFLite version
     * 
     * @param melSpec Array<FloatArray> of shape [numFrames × 80]
     * @return IntArray of token IDs [448]
     */
    fun runInference(melSpec: Array<FloatArray>): IntArray {
        if (!isLoaded) {
            throw IllegalStateException("Model not loaded")
        }
        
        interpreterLock.withLock {
            val startTime = System.currentTimeMillis()
            
            try {
                // 1. Prepare input tensor (reshape + transpose)
                val inputTensor = prepareInputTensor(melSpec)
                
                // 2. Run inference
                val session = mnnNet!!.createSession()
                val inputMNNTensor = session.getInput(null)
                inputMNNTensor.reshape(intArrayOf(1, MEL_BINS, MAX_FRAMES))
                inputMNNTensor.copyFromHostTensor(inputTensor)
                
                session.run()
                
                // 3. Extract output (token IDs)
                val outputMNNTensor = session.getOutput(null)
                val outputTensor = MNNNetInstance.TensorIntHelper.create(outputMNNTensor)
                val tokenIds = IntArray(MAX_TOKENS)
                outputTensor.readFromHostTensor(tokenIds)
                
                session.release()
                
                val elapsedMs = System.currentTimeMillis() - startTime
                Log.d(TAG, "Inference completed in ${elapsedMs}ms")
                
                return tokenIds
                
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                throw e
            }
        }
    }
    
    /**
     * Prepare input tensor: [numFrames × 80] → [1, 80, 3000]
     * Transpose from (frames, mels) to (batch, mels, frames) and pad
     */
    private fun prepareInputTensor(melSpec: Array<FloatArray>): MNNNetInstance.TensorFloat {
        val numFrames = melSpec.size.coerceAtMost(MAX_FRAMES)
        
        if (melSpec.isNotEmpty() && melSpec[0].size != MEL_BINS) {
            throw IllegalArgumentException("Expected $MEL_BINS mel bins, got ${melSpec[0].size}")
        }
        
        if (numFrames > MAX_FRAMES) {
            Log.w(TAG, "Audio too long ($numFrames frames), truncating to $MAX_FRAMES")
        }
        
        // Create tensor [1, 80, 3000]
        val inputData = FloatArray(1 * MEL_BINS * MAX_FRAMES) { 0f }
        
        // Fill with transposed data
        for (frameIdx in 0 until numFrames) {
            for (melIdx in 0 until MEL_BINS) {
                val destIdx = melIdx * MAX_FRAMES + frameIdx  // Transpose
                inputData[destIdx] = melSpec[frameIdx][melIdx]
            }
        }
        
        // Remaining frames stay zero-padded
        
        return MNNNetInstance.TensorFloat.create(intArrayOf(1, MEL_BINS, MAX_FRAMES), inputData)
    }
    
    /**
     * Release model resources
     */
    fun close() {
        interpreterLock.withLock {
            mnnNet?.release()
            mnnNet = null
            isLoaded = false
            isGpuEnabled = false
            Log.i(TAG, "Model resources released")
        }
    }
}
```

### 3.3 Update SpeechRecognitionManager

**Location:** `app/src/main/java/com/echowire/ml/SpeechRecognitionManager.kt`

**Replace WhisperModel with MnnWhisperModel:**

```kotlin
class SpeechRecognitionManager(
    private val context: Context,
    private val modelFile: File,  // whisper_tiny.mnn instead of .tflite
    private val vocabFile: File
) {
    private val audioPreprocessor = AudioPreprocessor()
    private val whisperModel = MnnWhisperModel(context, modelFile)  // ← Changed
    private val tokenizer = WhisperTokenizer(vocabFile)
    
    // Rest stays the same - API is identical
    fun initialize() {
        whisperModel.load()
        Log.i(TAG, "Whisper loaded with GPU: ${whisperModel.isGpuEnabled}")
    }
    
    // ... processBuffer() unchanged - same API
}
```

### 3.4 Update UhService Model Paths

**Location:** `app/src/main/java/com/echowire/service/EchoWireService.kt`

**Change model file extension:**

```kotlin
private fun loadModels() {
    serviceScope.launch {
        try {
            listener?.onModelLoading("Loading models...")
            
            // MNN model instead of TFLite
            val whisperModelPath = File(filesDir, "models/whisper_tiny.mnn")  // ← Changed
            val vocabPath = File(filesDir, "models/whisper_vocab.json")
            
            speechRecognitionManager = SpeechRecognitionManager(
                context = this@UhService,
                modelPath = whisperModelPath,
                vocabPath = vocabPath
            ).apply {
                initialize()
                setListener(/* ... same as before */)
            }
            
            listener?.onModelLoaded("Models loaded successfully")
            startAllComponents()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load models", e)
            listener?.onError("Failed to load models: ${e.message}")
        }
    }
}
```

### 3.5 Update ModelManager for .mnn Files

**Location:** `app/src/main/java/com/echowire/ml/ModelManager.kt`

**Add .mnn file handling:**

```kotlin
class ModelManager private constructor(private val context: Context) {
    // ... existing code
    
    // Update file paths
    val whisperModelFile: File = File(modelsDir, "whisper/whisper_tiny.mnn")  // ← Changed
    
    // ... rest unchanged
}
```

---

## Phase 4: Testing & Validation (1-2 hours)

### 4.1 Unit Tests

**Create test:** `app/src/test/java/com/echowire/ml/MnnWhisperModelTest.kt`

```kotlin
package com.echowire.ml

import org.junit.Test
import org.junit.Assert.*

class MnnWhisperModelTest {
    
    @Test
    fun testModelLoading() {
        // Test will be run on device (instrumented test)
        // Verify model loads without crashes
    }
    
    @Test
    fun testInferenceShape() {
        // Verify output shape is [448] token IDs
    }
    
    @Test
    fun testGpuFallback() {
        // Verify CPU fallback works when GPU unavailable
    }
}
```

### 4.2 Performance Benchmarking

**Add to UhService:**

```kotlin
private fun benchmarkInference() {
    serviceScope.launch(Dispatchers.IO) {
        val testAudio = ShortArray(16000)  // 1 second silence
        val melSpec = audioPreprocessor.pcmToMelSpectrogram(testAudio)
        
        // Warm-up
        repeat(3) {
            whisperModel.runInference(melSpec)
        }
        
        // Benchmark
        val times = mutableListOf<Long>()
        repeat(10) {
            val start = System.currentTimeMillis()
            whisperModel.runInference(melSpec)
            times.add(System.currentTimeMillis() - start)
        }
        
        val avgMs = times.average()
        val minMs = times.minOrNull()
        val maxMs = times.maxOrNull()
        
        Log.i(TAG, """
            Inference benchmark (10 runs):
            - Average: ${avgMs}ms
            - Min: ${minMs}ms
            - Max: ${maxMs}ms
            - GPU enabled: ${whisperModel.isGpuEnabled}
            - RTF: ${avgMs / 1000.0}x
        """.trimIndent())
    }
}
```

### 4.3 Comparison: MNN vs TFLite

**Keep both for A/B testing:**

```kotlin
// In build.gradle.kts, keep both temporarily
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")  // Old
    implementation("com.alibaba.android:mnn:2.9.0")          // New
}

// Create toggle in UhService
private val useMNN = true  // Switch between implementations

private fun createWhisperModel(): WhisperModelInterface {
    return if (useMNN) {
        MnnWhisperModel(context, mnnModelFile)
    } else {
        TfliteWhisperModel(context, tfliteModelFile)
    }
}
```

**Log comparison:**
```
TFLite (XNNPack CPU): 450ms avg, RTF 0.45x
MNN (Mali GPU):       220ms avg, RTF 0.22x  ← Target
Speedup:              2.0x
```

---

## Phase 5: Binary Size Optimization (30 minutes)

### 5.1 Measure APK Size Changes

**Before MNN:**
```bash
# TFLite
./gradlew assembleDebug
ls -lh app/build/outputs/apk/debug/app-debug.apk
# Size: ~180MB (models) + 5MB (TFLite) = ~185MB
```

**After MNN:**
```bash
# MNN + FP16 model
./gradlew assembleDebug
ls -lh app/build/outputs/apk/debug/app-debug.apk
# Size: ~90MB (FP16 models) + 0.4MB (MNN) = ~90.4MB
# Savings: ~95MB (51% reduction!)
```

### 5.2 ProGuard Rules (if using R8/ProGuard)

**Add to proguard-rules.pro:**
```proguard
# MNN
-keep class com.taobao.android.mnn.** { *; }
-keepclassmembers class com.taobao.android.mnn.** { *; }
```

---

## Phase 6: Deployment & Monitoring (30 minutes)

### 6.1 Git Workflow

```bash
# Create feature branch
git checkout -b feature/mnn-migration

# Convert model
./MNNConvert -f TFLITE --modelFile app/src/main/assets/models/whisper_tiny.tflite \
             --MNNModel app/src/main/assets/models/whisper_tiny.mnn --bizCode biz --fp16

# Add MNN model
git add app/src/main/assets/models/whisper_tiny.mnn
git commit -m "Add MNN Whisper model (FP16, 33MB)"

# Implement MNN integration
git add app/src/main/java/com/echowire/ml/MnnWhisperModel.kt
git commit -m "Implement MNN Whisper model with Mali GPU support"

# Update integration
git add app/src/main/java/com/echowire/ml/SpeechRecognitionManager.kt
git commit -m "Migrate SpeechRecognitionManager to MNN"

# Test and benchmark
git add app/src/test/...
git commit -m "Add MNN benchmarks and tests"

# Merge when validated
git checkout master
git merge feature/mnn-migration
```

### 6.2 Rollback Plan

**If MNN doesn't work out:**

```bash
# Keep TFLite code commented, not deleted
git revert <mnn-commit-sha>

# Or toggle via flag
private val USE_MNN = false  // Switch back to TFLite
```

---

## Expected Results

### Performance Targets

| Metric | Current (TFLite) | Target (MNN) | Success Criteria |
|--------|------------------|--------------|------------------|
| **Inference Time (1s audio)** | 400-600ms | 200-300ms | <350ms |
| **RTF (Real-Time Factor)** | 0.4-0.6x | 0.2-0.3x | <0.35x |
| **GPU Utilization** | 0% (CPU only) | 70-90% | >50% |
| **Binary Size** | 185MB | 90MB | <100MB |
| **Memory Usage** | 200MB | 180MB | <220MB |

### Acceptance Criteria

- ✅ Model converts successfully (TFLite → MNN)
- ✅ OpenCL or Vulkan backend loads (Mali GPU)
- ✅ Inference produces same results as TFLite (±0.01 tolerance)
- ✅ Performance improvement ≥1.5x
- ✅ No crashes or ANRs
- ✅ Works on target device (Samsung Note20)

---

## Risk Mitigation

### Risks

1. **Model conversion fails**
   - Mitigation: Try ONNX intermediate format
   - Fallback: Keep TFLite

2. **Performance not improved**
   - Mitigation: Try different backends (OpenCL/Vulkan/OpenGL)
   - Fallback: Keep TFLite

3. **Accuracy degradation (FP16)**
   - Mitigation: Use FP32 model instead
   - Test: WER comparison on test set

4. **Integration complexity**
   - Mitigation: Implement in parallel, toggle via flag
   - Timeline: 1-2 days is reasonable for this complexity

---

## Timeline Estimate

| Phase | Task | Time |
|-------|------|------|
| 1 | Setup & download MNN tools | 1h |
| 2 | Convert model (TFLite → MNN) | 0.5h |
| 3 | Implement MnnWhisperModel | 2h |
| 3 | Update integration code | 1h |
| 4 | Testing & validation | 1h |
| 5 | Binary size optimization | 0.5h |
| 6 | Documentation & commit | 0.5h |
| **Total** | **End-to-end** | **6.5 hours** |

**Realistic:** 1-2 days (with breaks, unexpected issues)

---

## Resources

- **MNN GitHub:** https://github.com/alibaba/MNN
- **MNN Docs:** https://mnn-docs.readthedocs.io/
- **Conversion Guide:** https://mnn-docs.readthedocs.io/en/latest/tools/convert.html
- **Android Guide:** https://github.com/alibaba/MNN/tree/master/project/android
- **Model Zoo:** https://github.com/alibaba/MNN/tree/master/demo/exec

---

## Questions?

1. **Do I need to retrain the model?**
   - No, just convert existing TFLite model

2. **Will accuracy be the same?**
   - FP32: Identical
   - FP16: 99.9% same (negligible WER difference)

3. **Can I use both TFLite and MNN?**
   - Yes, for A/B testing and validation

4. **What if conversion fails?**
   - Try ONNX intermediate format
   - Or report issue to MNN GitHub

5. **What if performance doesn't improve?**
   - Check GPU actually enabled (log output)
   - Try different backends
   - Worst case: keep TFLite (it works)

---

Ready to start? Let me know if you want me to proceed with implementation!
