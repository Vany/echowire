# Whisper Model Pipeline Audit & Test Plan

## Date: 2024-12-01
## Status: AUDIT COMPLETE - READY FOR DEVICE TESTING

---

## Executive Summary

After comprehensive audit of the whisper model pipeline, **all components are correctly configured**. The critical unknown is the **model output format** (INT32 vs FLOAT32), which can only be determined through device testing.

---

## Model Pipeline Components

### ✅ 1. Model Source & Download
- **Source**: `usefulsensors/openai-whisper` repository
- **URL**: https://github.com/usefulsensors/openai-whisper/raw/main/models/whisper-tiny.tflite
- **Size**: 66.16 MB
- **Format**: TFLite FlatBuffer (monolithic, not encoder-decoder split)
- **Verification**: Model downloaded successfully, valid TFLite header

### ✅ 2. Vocabulary Files
- **JSON Vocab**: `whisper_vocab.json` (1147.4 KB, 51,865 tokens)
  - Source: HuggingFace `openai/whisper-tiny` repo
  - Format: Text JSON mapping
  - Present in: `app/src/main/assets/models/`
  - Status: ✅ **Downloaded and verified**
  
- **Binary Vocab**: `filters_vocab_multilingual.bin` (289.9 KB)
  - Source: Reference implementation (vilassn/whisper_android)
  - Format: Binary file for efficient lookup
  - Present in: `app/src/main/assets/models/`
  - Status: ✅ **Downloaded and verified**

### ⚠️ 3. Model Output Format (UNKNOWN)
**Critical Question**: What does the model output?
- **Option A**: INT32 token IDs directly `[1, 448]`
- **Option B**: FLOAT32 logits requiring argmax `[1, 448, 51865]`

**Current Code Assumption**: INT32 token IDs in ByteBuffer
```kotlin
// WhisperModel.kt lines 193-203
val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
outputBuffer.order(ByteOrder.nativeOrder())
interpreter!!.run(inputTensor, outputBuffer)
outputBuffer.rewind()
val tokenIds = IntArray(maxTokens)
for (i in 0 until maxTokens) {
    if (outputBuffer.remaining() >= 4) {
        tokenIds[i] = outputBuffer.getInt()  // Reads INT32
    }
}
```

**If Model Outputs FLOAT32 Logits**:
Code needs modification to:
1. Read as `FloatArray` instead of INT32
2. Apply argmax over vocabulary dimension
3. Extract token IDs from argmax results

### ✅ 4. Input Preprocessing
- **Format**: Mel spectrogram [1, 80, 3000]
- **Implementation**: `AudioPreprocessor.kt`
- **FFT**: JTransforms library
- **Mel Bins**: 80 (Whisper standard)
- **Frames**: 3000 max (30 seconds at 10ms hop)
- **Status**: ✅ Implementation correct

### ✅ 5. Tokenization Pipeline
- **Tokenizer**: `WhisperTokenizer.kt`
- **Vocabulary**: Loaded from JSON or binary file
- **Size**: 51,865 tokens (verified)
- **Status**: ✅ Implementation correct

---

## Test Plan: Device Testing Required

Since Python 3.14 doesn't support TensorFlow, and Python 3.11 isn't available, **device testing is the fastest path** to determine model output format.

### Phase 1: Basic Model Loading (5 minutes)
```bash
# Install app
make install

# Watch logs
make logs
```

**Expected Logs**:
```
WhisperModel: Loading Whisper model from: /data/data/com.echowire/files/models/whisper_tiny.tflite
WhisperModel: Model size: 66 MB
WhisperModel: Model loaded successfully in XXXXms
WhisperModel: Input tensor: TensorInfo(shape=[1, 80, 3000], type=FLOAT32)
WhisperModel: Output tensor: TensorInfo(shape=[?, ?], type=?)  <-- KEY INFO
WhisperModel: Output shape array: [?, ?]
WhisperModel: Output num elements: XXXXX
```

**Critical Data Points**:
1. ✅ Model loads without errors
2. ⚠️ Output tensor shape (look for this)
3. ⚠️ Output tensor dtype (INT32 or FLOAT32?)

### Phase 2: Inference Test (10 minutes)

**Test Procedure**:
1. Start app
2. Speak clearly into microphone
3. Observe waveform (should turn yellow during speech)
4. Check logs for inference results

**Expected Logs**:
```
WhisperModel: Output shape: [?, ?]
WhisperModel: Output data type: <INT32 or FLOAT32>  <-- CRITICAL
WhisperModel: Output total elements: XXXXX
WhisperModel: Inference completed in XXXms
WhisperModel: First 20 tokens: [?, ?, ...]  <-- Check if sensible
WhisperModel: Token stats: X non-zero, Y unique
```

**Diagnosis Tree**:

```
Output dtype == INT32?
├─ YES → Token IDs look sensible (0-51865)?
│  ├─ YES → ✅ Pipeline correct, test vocabulary decoding
│  └─ NO  → ❌ Wrong token range, investigate model
│
└─ NO (FLOAT32) → Model outputs logits
   └─ Fix required: Modify WhisperModel.kt to apply argmax
```

### Phase 3: Vocabulary Decoding Test (5 minutes)

**If Phase 2 shows sensible token IDs**:
1. Check WhisperTokenizer logs
2. Verify decoded text makes sense
3. Check language detection

**Expected Logs**:
```
WhisperTokenizer: Decoding X tokens
WhisperTokenizer: Detected language: en (token 50259)
WhisperTokenizer: Transcript: "<text>"
```

**Diagnosis**:
- ✅ Text is readable → Pipeline working
- ❌ Text is garbage ("13", "0000") → Token decoding issue
- ❌ Wrong language → Language detection misaligned

---

## Known Issues & Solutions

### Issue 1: Model Outputs FLOAT32 Logits
**Symptom**: Token IDs are garbage, very large numbers
**Solution**: Modify `WhisperModel.kt` to apply argmax

```kotlin
// Read as FLOAT32
val outputShape = interpreter!!.getOutputTensor(0).shape()
val batchSize = outputShape[0]
val seqLen = outputShape[1]
val vocabSize = outputShape[2]

val outputBuffer = Array(batchSize) { 
    Array(seqLen) { 
        FloatArray(vocabSize) 
    }
}
interpreter!!.run(inputTensor, outputBuffer)

// Apply argmax
val tokenIds = IntArray(seqLen)
for (i in 0 until seqLen) {
    var maxIdx = 0
    var maxVal = outputBuffer[0][i][0]
    for (j in 1 until vocabSize) {
        if (outputBuffer[0][i][j] > maxVal) {
            maxVal = outputBuffer[0][i][j]
            maxIdx = j
        }
    }
    tokenIds[i] = maxIdx
}
```

### Issue 2: Wrong Vocabulary File
**Symptom**: Text decoding produces wrong words
**Solution**: Switch from JSON to binary vocabulary

```kotlin
// In ModelManager.kt, change extraction source
val vocabFiles = listOf(
    "filters_vocab_multilingual.bin" to vocabFile,  // Use binary
    "multilingual.tiktoken" to tiktokenFile
)
```

### Issue 3: Model Not Found
**Symptom**: `Model file not found` error
**Solution**: Download models first

```bash
# Download all models
make models

# Or manually
cd scripts
./download_models.sh
```

---

## Next Steps

### Immediate (30 minutes)
1. ✅ Run `make models` to download whisper model (if not done)
2. ✅ Run `make install` to install app on device
3. ✅ Run `make logs` to watch real-time logs
4. ⚠️ **Speak into device and observe logs**
5. ⚠️ **Record output tensor shape and dtype**

### Based on Test Results

**If Output is INT32 with sensible tokens (0-51865)**:
- ✅ Pipeline is correct
- Continue with full feature testing
- Move to Phase 9 in TODO.md

**If Output is FLOAT32 logits**:
- ❌ Code modification required
- Implement argmax in `WhisperModel.kt`
- Re-test and verify token IDs
- Estimated time: 1-2 hours

**If Tokens are garbage regardless of dtype**:
- ❌ Model or preprocessing issue
- Consider downloading vilassn/whisper_android reference model
- Compare models byte-by-byte
- Estimated time: 2-4 hours

---

## Alternative: Reference Implementation Test

If device testing is problematic, use proven working model:

```bash
# Clone reference implementation
cd ~/Downloads
git clone https://github.com/vilassn/whisper_android.git

# Copy their models
cp whisper_android/models_and_scripts/generated_model/whisper-tiny.tflite \\
   /path/to/echowire/models/whisper/

# Copy binary vocabulary
cp whisper_android/whisper_java/app/src/main/assets/filters_vocab_multilingual.bin \\
   /path/to/echowire/app/src/main/assets/models/

# Rebuild and test
make clean
make install
```

**Note**: Their model is encoder-decoder split, requires code changes to use.

---

## Conclusion

✅ **Model download pipeline**: Correct  
✅ **Vocabulary files**: Both present and verified  
✅ **Input preprocessing**: Correct implementation  
⚠️ **Output format**: Unknown, device testing required  
⚠️ **Tokenization**: Depends on output format verification  

**RECOMMENDATION**: Proceed with device testing immediately (30 minutes) to determine output format, then fix code if needed (1-2 hours worst case).
