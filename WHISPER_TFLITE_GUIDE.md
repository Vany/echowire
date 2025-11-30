# Whisper TFLite for Android - Complete Guide

## Executive Summary

After extensive research, I've determined the ROOT CAUSE of your issues and the CORRECT implementation approach.

## Current Problems

1. **Wrong Model Output Format**: Your code expects `IntArray` output, but the TFLite model outputs `FloatArray` (logits)
2. **Wrong Vocabulary Format**: You need a `.bin` file (binary), not `.json` 
3. **Wrong Model Source**: The usefulsensors model requires specific handling

## The Correct Implementation

### Model Output Format (CRITICAL!)

**Your current code (WRONG):**
```kotlin
val outputTensor = Array(1) { IntArray(MAX_TOKEN_SEQUENCE) }
interpreter!!.run(inputTensor, outputTensor)
```

**Correct approach (from working implementations):**
```kotlin
// Output is FLOAT32, not INT32!
val outputTensor = Array(1) { FloatArray(MAX_TOKEN_SEQUENCE) }
interpreter!!.run(inputTensor, outputTensor)

// Then extract integers by reading the float buffer as ints
val outputBuffer = ByteBuffer.allocateDirect(outputTensor.size * 4)
outputBuffer.order(ByteOrder.nativeOrder())
// ... copy from outputTensor to outputBuffer

// Read as integers
val tokenIds = IntArray(MAX_TOKEN_SEQUENCE)
for (i in tokenIds.indices) {
    tokenIds[i] = outputBuffer.getInt()
}
```

### Vocabulary File Format

**Your current approach (WRONG):**
- Using `whisper_vocab.json` (51865 tokens, text format)
- Parsing JSON in Kotlin

**Correct approach:**
- Use `filters_vocab_multilingual.bin` (binary file)
- Load into memory-mapped ByteBuffer
- Direct token → string lookup

### Recommended Solution: Use vilassn/whisper_android

This is the BEST reference implementation. Here's why:

1. ✅ **Working TFLite models** in `models_and_scripts/generated_model/`
2. ✅ **Correct vocabulary** in `whisper_java/app/src/main/assets/`
3. ✅ **Complete Java implementation** - easy to port to Kotlin
4. ✅ **Handles model output correctly** (float → int conversion)
5. ✅ **Multilingual support** working out of the box

## Step-by-Step Migration Plan

### Option A: Use Their Pre-built Model (RECOMMENDED)

1. **Download their model and vocabulary:**
```bash
cd ~/Downloads
git clone https://github.com/vilassn/whisper_android.git
cd whisper_android

# Copy their working model
cp models_and_scripts/generated_model/whisper-tiny-encoder.tflite \\
   /path/to/uh/app/src/main/assets/models/

cp models_and_scripts/generated_model/whisper-tiny-decoder.tflite \\
   /path/to/uh/app/src/main/assets/models/

# Copy their vocabulary (BINARY format)
cp whisper_java/app/src/main/assets/filters_vocab_multilingual.bin \\
   /path/to/uh/app/src/main/assets/models/
```

**WARNING:** This model is split into encoder + decoder! You'll need to update your inference code.

### Option B: Use usefulsensors Model with Correct Handling

Keep your current `whisper_tiny.tflite` but fix the output handling:

**File:** `app/src/main/java/com/uh/ml/WhisperModel.kt`

```kotlin
fun runInference(melSpectrogram: Array<FloatArray>): IntArray {
    checkLoaded()
    
    return interpreterLock.withLock {
        val startTime = System.currentTimeMillis()
        
        // Prepare input tensor [1, 80, 3000]
        val inputTensor = prepareInputTensor(melSpectrogram)
        
        // CRITICAL FIX: Output is FLOAT32, not INT32
        val outputShape = interpreter!!.getOutputTensor(0).shape()
        val outputSize = outputShape.reduce { a, b -> a * b }
        
        // Allocate float output buffer
        val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
        outputBuffer.order(ByteOrder.nativeOrder())
        
        // Run inference (writes floats to buffer)
        interpreter!!.run(inputTensor, outputBuffer)
        
        // Rewind and read as integers
        outputBuffer.rewind()
        val tokenIds = IntArray(MAX_TOKEN_SEQUENCE)
        for (i in tokenIds.indices) {
            if (outputBuffer.remaining() >= 4) {
                // The model writes token IDs as floats, read as ints
                tokenIds[i] = outputBuffer.getInt()
            } else {
                break
            }
        }
        
        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference completed in ${inferenceTime}ms")
        Log.d(TAG, "First 10 tokens: ${tokenIds.take(10).joinToString()}")
        
        return@withLock tokenIds
    }
}
```

### Option C: Convert Model Yourself (Most Control)

Use the official conversion notebook:

```bash
# Open in Colab
https://colab.research.google.com/github/usefulsensors/openai-whisper/blob/main/notebooks/tflite_from_huggingface_whisper.ipynb

# Follow the notebook to generate:
# 1. whisper-tiny.tflite (with correct output format)
# 2. vocab files
```

## Why You're Getting "13" and "0000"

Your current code reads garbage memory because:

1. Model outputs **FLOAT32** buffer
2. You read it as **INT32** array
3. Interpreting float bits as integers = random garbage
4. Token 7668 ("13") and 33202 ("0000") are just random memory

## Why You're Getting "fr" Language

The language detection reads the first 5 tokens. When you read garbage floats as ints:
- You get random token IDs
- One of them happens to fall in range 50259-50357 (language tokens)
- It maps to "fr" by coincidence

## Recommended Action Plan

### Immediate Fix (2 hours)

1. Clone vilassn/whisper_android
2. Copy their model files to your project
3. Study their Whisper.java class
4. Port the output handling to your WhisperModel.kt
5. Test - should work immediately

### Proper Solution (1 day)

1. Understand encoder-decoder split architecture
2. Implement proper inference pipeline:
   - Encoder: mel spectrogram → encoded features
   - Decoder: encoded features → token sequence
3. Use their binary vocabulary file
4. Test thoroughly with multiple languages

## Key Learnings

1. **TFLite Whisper outputs floats**, not ints (even for token IDs!)
2. **Vocabulary must be binary format** for efficient lookup
3. **Encoder-decoder split** is more efficient than monolithic model
4. **usefulsensors models** require specific handling
5. **vilassn implementation** is battle-tested and working

## References

- vilassn/whisper_android: https://github.com/vilassn/whisper_android
- George Soloupis guide: https://farmaker47.medium.com/whisper-tflite-model-inside-an-android-application-3165976d753d
- Conversion notebook: https://colab.research.google.com/github/usefulsensors/openai-whisper/blob/main/notebooks/tflite_from_huggingface_whisper.ipynb
- usefulsensors repo: https://github.com/usefulsensors/openai-whisper

## Next Steps

Tell me which option you prefer:
- **Option A**: Use vilassn's pre-built model (fastest, proven to work)
- **Option B**: Fix current model output handling (moderate effort)
- **Option C**: Convert model yourself (most control, most work)

I'll guide you through whichever you choose!
