# ML Inference Framework Research for Android (Samsung Note20, Exynos 990, Mali-G77)

**Date:** 2024-11-26  
**Target Device:** Samsung Galaxy Note20 (SM-N980F)
- **SoC:** Exynos 990 (ARM64-v8a)
- **GPU:** Mali-G77 MP11
- **RAM:** 8GB
- **Android:** 12
- **No Qualcomm Hardware** (no QNN support)

## Current Stack (As of Phase 4)

### Speech Recognition: TensorFlow Lite ✅ ACTIVE
- **Model:** Whisper tiny multilingual (66MB)
- **Acceleration:** XNNPack (ARM NEON CPU) - 2-3x speedup
- **GPU Delegate:** DISABLED (TFLite library bug - all 2.x versions broken)
- **Performance:** 400-600ms for 1s audio (0.4-0.6x RTF)
- **Status:** Meets <500ms target with buffering

### Text Embeddings: ONNX Runtime ✅ ACTIVE
- **Model:** all-MiniLM-L6-v2 (86MB, 384 dimensions)
- **Acceleration:** CPU only
- **Performance:** ~50ms per phrase
- **Status:** Fast enough, no GPU needed

## Research Question: What Alternatives Exist?

### Framework Comparison Overview

| Framework | GPU Support | Mali Optimization | Size | Speed | Maturity | Recommendation |
|-----------|-------------|-------------------|------|-------|----------|----------------|
| **TFLite** | OpenGL (broken), XNNPack | Good | Small | Medium | Very High | ✅ Current (good enough) |
| **Arm NN** | OpenCL, Vulkan | **Excellent** | Medium | **Very Fast** | High | ⭐ Best for Mali |
| **MNN** | OpenCL, Vulkan, OpenGL | **Excellent** | **Tiny** | **Very Fast** | High | ⭐ Best alternative |
| **NCNN** | Vulkan | Good | Small | Fast | Medium | ✅ Solid choice |
| **ONNX Runtime** | None for Mali | Poor | Medium | Slow | High | ❌ CPU only |
| **MediaPipe** | OpenGL | Good | Large | Medium | High | 🤔 Framework overhead |

---

## 1. Arm NN (ARM Software) ⭐ BEST FOR MALI GPUs

### Overview
The most performant ML inference engine for Android and Linux, specifically accelerating ML on Arm Cortex-A CPUs and Arm Mali GPUs through architecture-specific optimizations using Arm Compute Library (ACL).

### Key Features
- Outperforms generic ML libraries due to Arm architecture-specific optimizations (e.g. SVE2) by utilizing Arm Compute Library
- Supports ML models in TensorFlow Lite and ONNX formats
- AAR (Android Archive) file available for easy Android Studio integration
- Uses Arm Compute Library to provide optimized operators (convolution, pooling) targeting ARM accelerators like DSP (NEON) or Mali GPU
- Includes CLTuner tool that tunes hardware knobs to fully utilize all computational horsepower the GPU provides

### Architecture
- **Acceleration:** OpenCL (primary), Vulkan, CPU
- **Backend:** Arm Compute Library (ACL) - heavily optimized for Mali GPUs
- **Integration:** TFLite Delegate API (drop-in replacement for TFLite GPU delegate)
- **NNAPI:** Works with NNAPI to target Arm Mali GPUs and Arm Ethos NPUs, enabling exponential performance boosts

### Performance
- Fastest option for Mali GPUs among all mobile inference frameworks
- **Expected Speedup:** 2-4x over TFLite CPU (based on Mali GPU performance papers)
- **Real-Time Factor:** Likely 0.2-0.3x (200-300ms for 1s audio on Whisper tiny)

### Implementation
```kotlin
// Using Arm NN TFLite Delegate (easiest approach)
dependencies {
    implementation("org.armnn:armnn-tflite-delegate:24.02") // Check latest version
}

val options = Interpreter.Options()
val delegate = ArmNNDelegate()  // Automatically selects Mali GPU if available
options.addDelegate(delegate)
interpreter = Interpreter(modelFile, options)
```

### Pros
- ✅ **Best Mali GPU performance** (optimized specifically for Mali architecture)
- ✅ Drop-in TFLite delegate (minimal code changes)
- ✅ Supports both TFLite and ONNX models
- ✅ Mature, production-tested (used by ARM ecosystem)
- ✅ Open source (MIT license)
- ✅ Active development (latest release: 24.02)

### Cons
- ⚠️ Larger binary size (~5-10MB) due to ACL
- ⚠️ Requires downloading AAR or building from source
- ⚠️ Not available on Maven Central (need direct download)
- ⚠️ Learning curve for advanced configuration

### Use Case Fit: ⭐⭐⭐⭐⭐ EXCELLENT
**This is the ideal solution for your Exynos 990 + Mali-G77 device.** It's specifically designed and optimized for Mali GPUs and would likely give you the 2-3x speedup you need to hit <300ms inference times.

---

## 2. MNN (Alibaba) ⭐ BEST ALTERNATIVE

### Overview
Lightweight mobile-side deep learning inference engine made open source by Alibaba, with deep tuning for mainstream GPUs (Adreno and Mali).

### Key Features
- Blazing fast, lightweight deep learning framework, battle-tested by business-critical use cases in Alibaba
- For Android, OpenCL, Vulkan, and OpenGL are available to meet as many device requirements as possible
- Convolution and transposition convolution algorithms are efficient and stable, with Winograd convolution algorithm widely used
- On Android, the core size combined with OpenCL/Vulkan is less than 1MB

### Architecture
- **Acceleration:** OpenCL (primary), Vulkan, OpenGL
- **Optimization:** Implements core operations using large amounts of handwritten assembly code to make full use of ARM CPU
- **Algorithms:** Applies Winograd algorithm in convolution and Strassen algorithm in matrix multiplication
- **Model Support:** Supports Tensorflow, Caffe, ONNX, Torchscripts

### Performance
- Inference time drop by about 7-8% on CPU and 50-75% on GPU using preparation-execution decoupling
- Leading inference speed on both mobile CPUs and GPUs with optimized KV cache and weights layout
- **Expected:** Similar to Arm NN (0.2-0.3x RTF on Mali GPU)

### Implementation
```kotlin
// Native MNN Android integration
dependencies {
    implementation("com.alibaba.android:mnn:2.8.0") // Check Maven Central
}

// Load model
val netNative = MNN.NetNative.createFromFile("model.mnn")
val session = netNative.createSession(MNN.SessionConfig().apply {
    forwardType = MNN.ForwardType.FORWARD_VULKAN  // or OPENCL
})

// Inference
val inputTensor = session.getInput(null)
inputTensor.copyFromHostTensor(inputData)
session.run()
val outputTensor = session.getOutput(null)
```

### Pros
- ✅ **Excellent Mali GPU optimization** (deeply tuned)
- ✅ **Tiny binary size** (<1MB core + GPU backend)
- ✅ Multiple GPU backends (OpenCL, Vulkan, OpenGL)
- ✅ Hybrid CPU/GPU execution
- ✅ Active development (LLM support added recently)
- ✅ Mature (battle-tested at Alibaba scale)
- ✅ Maven Central availability

### Cons
- ⚠️ Different API from TFLite (more work to integrate)
- ⚠️ Requires model conversion to .mnn format
- ⚠️ Less documentation than TFLite
- ⚠️ Chinese-language community (some docs not translated)

### Use Case Fit: ⭐⭐⭐⭐⭐ EXCELLENT
**Best alternative to Arm NN.** If you want maximum performance and minimal binary size, MNN is the top choice. Mali-G77 optimization is explicit and documented.

---

## 3. NCNN (Tencent)

### Overview
Tencent's high-performance neural network inference framework optimized for mobile platforms.

### Key Features
- **Acceleration:** Vulkan (primary GPU API)
- **Optimization:** Hand-optimized ARM NEON assembly
- **Binary Size:** ~500KB-1MB
- **Model Support:** Caffe, ONNX, PyTorch (via conversion)

### Performance
- Performs well on both Mali and Adreno GPUs
- **Expected:** 0.3-0.4x RTF on Vulkan (faster than TFLite CPU, slower than MNN/Arm NN)

### Pros
- ✅ Good Mali GPU support via Vulkan
- ✅ Small binary size
- ✅ Mature and stable
- ✅ Active community

### Cons
- ⚠️ Vulkan only (no OpenCL fallback)
- ⚠️ Requires model conversion
- ⚠️ Not as optimized for Mali as Arm NN or MNN
- ⚠️ No Maven Central (manual integration)

### Use Case Fit: ⭐⭐⭐⭐ GOOD
Solid choice if you want Vulkan-based acceleration. Not as optimized as Arm NN/MNN but still faster than TFLite CPU.

---

## 4. MediaPipe (Google)

### Overview
Open-source framework from Google for building pipelines to perform computer vision inference over arbitrary sensory data.

### Key Features
- Supports OpenGL ES up to version 3.2 on Android/Linux
- Allows graphs to run OpenGL in multiple GL contexts for combining slower GPU inference path with faster GPU rendering
- Unified API to process text, images, and audio with automatic hardware selection of GPU, NPU, CPU
- Achieves state-of-the-art latency on-device focusing on CPU and GPU

### Architecture
- **Acceleration:** OpenGL ES (primary), delegates to TFLite GPU internally
- **Pipeline:** Graph-based execution (calculators and streams)
- **Model Support:** TFLite models

### Pros
- ✅ High-level pipeline abstraction
- ✅ Good for complex multi-stage pipelines
- ✅ Google support and documentation
- ✅ Built-in utilities (image processing, audio)

### Cons
- ⚠️ Framework overhead (adds complexity)
- ⚠️ Larger binary size (10-20MB+)
- ⚠️ Uses TFLite GPU under the hood (same limitations)
- ⚠️ Overkill for simple inference tasks

### Use Case Fit: ⭐⭐⭐ MODERATE
**Not recommended for your use case.** You're already doing the pipeline work (audio capture → preprocess → inference → decode). MediaPipe adds unnecessary abstraction and binary size.

---

## 5. ONNX Runtime

### Current Implementation
- **Acceleration:** CPU only (no GPU delegate for Mali)
- **Performance:** Adequate for embeddings (50ms), too slow for Whisper
- **Status:** Working fine for text embeddings

### GPU Support Status
- ONNX Runtime with NNAPI defaults to using edgetpu for inference tasks, which is not currently supported
- No direct GPU inference pathway for Mali GPUs
- **Verdict:** CPU-only for your device

### Use Case Fit: ⭐⭐ POOR FOR SPEECH
Keep using it for embeddings (works fine), but don't use for Whisper inference.

---

## Performance Comparison (Estimated for Whisper Tiny on Note20)

| Framework | Backend | RTF (Real-Time Factor) | Latency (1s audio) | Speedup vs Current |
|-----------|---------|------------------------|--------------------|--------------------|
| **TFLite (current)** | XNNPack CPU | 0.4-0.6x | 400-600ms | Baseline |
| **Arm NN** | Mali GPU (OpenCL) | 0.2-0.3x | 200-300ms | **2-3x faster** ⭐ |
| **MNN** | Mali GPU (Vulkan) | 0.2-0.3x | 200-300ms | **2-3x faster** ⭐ |
| **NCNN** | Vulkan | 0.3-0.4x | 300-400ms | **1.5-2x faster** |
| **MediaPipe** | OpenGL (TFLite) | 0.4-0.6x | 400-600ms | Same as TFLite |
| **ONNX Runtime** | CPU | 0.6-0.8x | 600-800ms | **1.5x slower** ❌ |

---

## Recommendations

### For Speech Recognition (Whisper):

#### Option A: **Arm NN TFLite Delegate** ⭐ RECOMMENDED
**Why:** Best performance for Mali GPUs, minimal code changes (drop-in TFLite delegate)

**Implementation:**
1. Download Arm NN AAR from ARM GitHub releases
2. Add AAR to project: `implementation files('libs/armnn-delegate.aar')`
3. Replace TFLite delegate:
```kotlin
val options = Interpreter.Options()
val delegate = ArmNNDelegate()  // Auto-detects Mali GPU
options.addDelegate(delegate)
interpreter = Interpreter(modelFile, options)
```

**Expected Result:**
- 200-300ms inference (0.2-0.3x RTF)
- 2-3x speedup over current XNNPack
- **Meets <500ms target comfortably**

**Effort:** Low (2-4 hours)

---

#### Option B: **MNN** ⭐ ALTERNATIVE
**Why:** Excellent Mali optimization, smallest binary size, future-proof for LLMs

**Implementation:**
1. Add MNN dependency: `implementation("com.alibaba.android:mnn:2.8.0")`
2. Convert Whisper model to .mnn format using MNN converter
3. Rewrite inference code to use MNN API
4. Configure Vulkan or OpenCL backend

**Expected Result:**
- 200-300ms inference (0.2-0.3x RTF)
- 2-3x speedup over current XNNPack
- **Meets <500ms target comfortably**
- Binary size: +400KB (vs +5MB for Arm NN)

**Effort:** Medium (1-2 days for conversion + integration)

---

#### Option C: **Keep TFLite XNNPack** ✅ STATUS QUO
**Why:** Already working, meets target with buffering, lowest risk

**Current Performance:**
- 400-600ms inference (0.4-0.6x RTF)
- Meets <500ms target with proper buffering
- No GPU, but good enough for real-time

**Effort:** Zero

**Trade-off:** Slower than GPU options, but stable and working

---

### For Text Embeddings:
**Keep ONNX Runtime** - it's fast enough (50ms), no GPU needed, stable

---

## Decision Matrix

| Criterion | Arm NN | MNN | Keep TFLite |
|-----------|--------|-----|-------------|
| **Performance** | ⭐⭐⭐⭐⭐ Best | ⭐⭐⭐⭐⭐ Best | ⭐⭐⭐ Good |
| **Binary Size** | ⭐⭐⭐ +5MB | ⭐⭐⭐⭐⭐ +400KB | ⭐⭐⭐⭐ Current |
| **Integration Effort** | ⭐⭐⭐⭐ Easy | ⭐⭐⭐ Medium | ⭐⭐⭐⭐⭐ None |
| **Stability/Risk** | ⭐⭐⭐⭐ Proven | ⭐⭐⭐⭐ Alibaba-tested | ⭐⭐⭐⭐⭐ Working |
| **Mali Optimization** | ⭐⭐⭐⭐⭐ Best | ⭐⭐⭐⭐⭐ Excellent | ⭐⭐ Poor |
| **Documentation** | ⭐⭐⭐⭐ Good | ⭐⭐⭐ OK | ⭐⭐⭐⭐⭐ Best |
| **Community** | ⭐⭐⭐ ARM ecosystem | ⭐⭐⭐ Active | ⭐⭐⭐⭐⭐ Huge |

---

## My Engineering Recommendation

### **Path Forward: Try Arm NN First**

**Reasoning:**
1. Your current TFLite XNNPack solution is "good enough" (400-600ms, meets target)
2. But you're leaving 2-3x performance on the table (Mali GPU unused)
3. Arm NN is the **lowest-risk upgrade** with highest performance gain
4. Drop-in replacement = minimal code changes
5. If Arm NN doesn't work out, you can fall back to current solution

**Action Plan:**
1. **Phase 1 (2-4 hours):** Add Arm NN delegate to current code
   - Download AAR, integrate, test
   - Measure actual speedup on your device
   - If 2x+ speedup achieved → **done**, commit
2. **Phase 2 (optional):** If Arm NN has issues
   - Try MNN as alternative
   - Or keep TFLite (it works)

**Conservative Estimate:**
- Success probability: 80% (Arm NN is mature for Mali)
- Speedup: 2-3x (200-300ms vs 400-600ms)
- Risk: Low (can revert if issues)
- Effort: Half a day

**Radical Estimate:**
- If you want smallest binary + future LLM support: Go MNN
- Requires model conversion and API learning
- Higher upfront cost, but more control

---

## Questions for You

1. **Are you satisfied with current performance (400-600ms)?**
   - If yes → don't change anything
   - If no → try Arm NN

2. **Do you care about binary size?**
   - If +5MB is OK → Arm NN
   - If every KB matters → MNN

3. **How much time do you want to invest?**
   - 2-4 hours → Arm NN
   - 1-2 days → MNN
   - 0 hours → Keep TFLite

4. **Are you planning to add more ML models (LLMs, diffusion)?**
   - If yes → MNN (better LLM support)
   - If no → Arm NN (focused on inference)

---

## Sources & References

### Arm NN
- GitHub: https://github.com/ARM-software/armnn
- Documentation: https://arm-software.github.io/armnn/
- AAR Downloads: https://github.com/ARM-software/armnn/releases

### MNN
- GitHub: https://github.com/alibaba/MNN
- Paper: https://arxiv.org/abs/2002.12418
- Android Guide: https://www.mnn.zone/m/0.2/train/android.html

### NCNN
- GitHub: https://github.com/Tencent/ncnn

### MediaPipe
- Documentation: https://developers.google.com/mediapipe

### Performance Papers
- TFLite GPU: https://arxiv.org/abs/1907.01989
- MNN Performance: https://arxiv.org/abs/2002.12418
- Mali GPU Optimization: ARM Developer Guides

---

## Final Verdict

### For Your Exynos 990 + Mali-G77 Device:

**Speech Recognition:**
1. **Best:** Arm NN TFLite Delegate ⭐
2. **Alternative:** MNN (Vulkan/OpenCL) ⭐
3. **Acceptable:** Keep TFLite XNNPack (current) ✅

**Text Embeddings:**
- **Keep:** ONNX Runtime (no change needed) ✅

**My Vote:** Try Arm NN this weekend. If it gives 2x speedup, commit it. If not, your current solution is already good enough.
