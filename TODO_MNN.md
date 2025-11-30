## Phase 5: MNN Migration (Optional Performance Upgrade) 🚀 PLANNED

**Decision Date:** 2024-11-26  
**Status:** PLANNED - User chose MNN over Arm NN  
**Goal:** 2-3x speedup via Mali-G77 GPU acceleration  
**See:** `/MNN_MIGRATION_PLAN.md` for detailed implementation plan

### Overview

Migrate from TensorFlow Lite (XNNPack CPU) to MNN (Alibaba Mobile Neural Network) to unlock Mali-G77 GPU performance.

**Current Performance (TFLite + XNNPack):**
- Inference: 400-600ms for 1s audio
- RTF: 0.4-0.6x (real-time factor)
- Backend: ARM NEON CPU (4 threads)
- GPU: Unused (TFLite GPU delegate broken)
- Status: ✅ Meets <500ms target, but leaves 2-3x on table

**Target Performance (MNN + OpenCL/Vulkan):**
- Inference: 200-300ms for 1s audio ⚡
- RTF: 0.2-0.3x
- Backend: OpenCL (primary) or Vulkan for Mali GPU
- GPU: 70-90% utilization
- Binary size: 95MB reduction (177MB → 90MB with FP16)

### Why MNN?

1. **Best Mali GPU optimization** - Explicitly tuned for Mali GPUs
2. **Smallest binary** - +400KB (vs +5MB for Arm NN)
3. **Future-proof** - LLM support recently added
4. **Battle-tested** - Used in 30+ Alibaba apps (Taobao, Tmall, Youku)
5. **Flexible backends** - OpenCL, Vulkan, OpenGL, CPU fallback

### Implementation Checklist (6-8 hours total)

#### Phase 5.1: Setup & Model Conversion (1-2 hours)
- [ ] Download MNN tools (pre-built or build from source)
- [ ] Convert Whisper TFLite to MNN format:
  ```bash
  ./MNNConvert -f TFLITE \
    --modelFile whisper_tiny.tflite \
    --MNNModel whisper_tiny.mnn \
    --bizCode biz --fp16
  ```
- [ ] Verify model conversion (dump to JSON, check I/O shapes)
- [ ] Add .mnn model to `app/src/main/assets/models/`
- [ ] Update .gitignore for .mnn files

#### Phase 5.2: Android Integration (2-3 hours)
- [ ] Add MNN dependency to build.gradle.kts:
  ```kotlin
  implementation("com.alibaba.android:mnn:2.9.0")
  // Or: implementation(files("libs/mnn-release.aar"))
  ```
- [ ] Create `MnnWhisperModel.kt` (drop-in replacement for `WhisperModel.kt`)
  - [ ] Implement OpenCL backend (primary for Mali GPU)
  - [ ] Implement Vulkan backend (fallback #1)
  - [ ] Implement CPU backend (fallback #2)
  - [ ] Thread-safe inference with ReentrantLock
  - [ ] Input tensor preparation (transpose + pad)
  - [ ] Output tensor extraction (token IDs)
- [ ] Update `SpeechRecognitionManager.kt` to use `MnnWhisperModel`
- [ ] Update `UhService.kt` model paths (`.tflite` → `.mnn`)
- [ ] Update `ModelManager.kt` for .mnn file handling

#### Phase 5.3: Testing & Validation (1-2 hours)
- [ ] Build and install on Samsung Note20
- [ ] Verify GPU backend loads (check logs for "OpenCL" or "Vulkan")
- [ ] Run benchmark (10 inferences, measure avg/min/max)
- [ ] Compare MNN vs TFLite performance side-by-side
- [ ] Verify accuracy unchanged (test with known audio samples)
- [ ] Test CPU fallback (disable GPU in code)
- [ ] Memory profiling (ensure <2GB)

#### Phase 5.4: Optimization & Cleanup (30 min)
- [ ] Measure APK size before/after
- [ ] Add ProGuard rules for MNN (if using R8)
- [ ] Remove TFLite dependency (or keep for A/B testing)
- [ ] Update documentation (CLAUDE.md, SPEC.md)

#### Phase 5.5: Git & Deployment (30 min)
- [ ] Create feature branch: `feature/mnn-migration`
- [ ] Commit model conversion
- [ ] Commit MNN integration code
- [ ] Commit tests and benchmarks
- [ ] Merge to master after validation
- [ ] Tag release: `v1.0-mnn`

### Expected Results

| Metric | Before (TFLite) | After (MNN) | Improvement |
|--------|----------------|-------------|-------------|
| Inference Time | 400-600ms | 200-300ms | **2-3x faster** |
| RTF | 0.4-0.6x | 0.2-0.3x | **2x faster** |
| GPU Utilization | 0% | 70-90% | **Unlocked** |
| APK Size | 185MB | 90MB | **51% smaller** |
| Memory | 200MB | 180MB | Same/better |

### Success Criteria

- ✅ Model converts successfully (TFLite → MNN)
- ✅ GPU backend loads (OpenCL or Vulkan)
- ✅ Inference ≥1.5x faster than TFLite
- ✅ Accuracy unchanged (±0.01 WER tolerance)
- ✅ No crashes or ANRs
- ✅ Works reliably on Samsung Note20

### Rollback Plan

If MNN doesn't work out:
1. Keep TFLite code (commented, not deleted)
2. Use feature toggle: `val USE_MNN = false`
3. Revert commits: `git revert <sha>`
4. Current TFLite solution already meets target

### Resources

- **Implementation Guide:** `/MNN_MIGRATION_PLAN.md` (detailed 20KB guide)
- **Research:** `/RESEARCH_ML_FRAMEWORKS.md` (framework comparison)
- **MNN GitHub:** https://github.com/alibaba/MNN
- **MNN Docs:** https://mnn-docs.readthedocs.io/
- **Conversion Tool:** https://github.com/alibaba/MNN/tree/master/tools/converter

### Timeline

**Conservative:** 1-2 days (with testing and validation)  
**Optimistic:** 6-8 hours (if everything works first try)

### Next Steps

1. **Download MNN tools** - Pre-built binaries or build from source
2. **Convert model** - TFLite → MNN (with FP16 for 50% size reduction)
3. **Implement MnnWhisperModel** - Follow template in MNN_MIGRATION_PLAN.md
4. **Test on device** - Verify GPU acceleration and performance
5. **Benchmark** - Compare against TFLite baseline
6. **Commit** - Merge if successful, revert if not

**Ready to proceed?** The detailed implementation is in `/MNN_MIGRATION_PLAN.md`

---

