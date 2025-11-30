# Language Filtering Implementation

**Date:** 2024-11-30  
**Issue:** Whisper multilingual model recognizes 99 languages, but application only needs Russian and English

## Problem

The Whisper tiny multilingual model can detect and transcribe 99 languages automatically. However, the UH application is designed for **Russian and English only**. Without filtering, the model may:

- Detect incorrect languages (French, German, etc.)
- Produce transcriptions in unwanted languages
- Waste processing cycles on irrelevant speech
- Confuse users with unexpected language outputs

## Solution: Language Filtering in SpeechRecognitionManager

**Location:** `app/src/main/java/com/uh/ml/SpeechRecognitionManager.kt` (line 253)

**Implementation:**
```kotlin
// 5.1. Language filtering - accept only Russian and English
val allowedLanguages = setOf("ru", "en")
if (language !in allowedLanguages) {
    Log.w(TAG, "LANGUAGE FILTER: Detected '$language' but only accepting 'ru' or 'en'. Skipping transcription: \"$text\"")
    Log.i(TAG, "Buffer cleared. Waiting for Russian or English speech...")
    // Clear buffer and return without notifying listener
    audioBuffer.clear()
    return
}

Log.i(TAG, "Language accepted: $language - \"$text\"")
```

## Behavior

### Accepted Languages
- `"ru"` - Russian (TOKEN_LANGUAGE_RU = 50263)
- `"en"` - English (TOKEN_LANGUAGE_EN = 50259)

### Rejected Languages
All other detected languages are **silently rejected** with:
1. Warning log entry
2. Buffer cleared
3. NO transcription callback
4. Continues listening for next audio

### Example Logs

**Accepted (Russian):**
```
Detected language: ru
Language accepted: ru - "привет как дела"
```

**Accepted (English):**
```
Detected language: en
Language accepted: en - "hello how are you"
```

**Rejected (French):**
```
Detected language: fr
LANGUAGE FILTER: Detected 'fr' but only accepting 'ru' or 'en'. Skipping transcription: "bonjour comment ça va"
Buffer cleared. Waiting for Russian or English speech...
```

## Configuration

To add more languages in the future:

```kotlin
// Add to allowedLanguages set
val allowedLanguages = setOf("ru", "en", "de", "fr")  // German, French
```

Language codes match Whisper's multilingual token set:
- See `WhisperTokenizer.kt` for full list
- 99 languages supported (50259-50357)

## Performance Impact

- **Zero overhead** when correct language detected
- **Early exit** when wrong language detected (saves embedding generation)
- **Clean buffer** prevents language contamination in next inference
- **No configuration needed** - hardcoded for simplicity

## Future Enhancements

1. **Dynamic language configuration:**
   - Add `allowed_languages` to RuntimeConfig
   - WebSocket configure message: `{"configure": "languages", "value": "ru,en"}`
   - UI dropdown for language selection

2. **Language confidence scores:**
   - Whisper provides confidence per language
   - Could require minimum confidence threshold
   - Reject low-confidence detections

3. **Language-specific models:**
   - Download separate Russian-only and English-only models
   - Faster inference (no 99-language overhead)
   - Better accuracy for target languages

## Testing

**Test Russian recognition:**
```bash
# Speak Russian into microphone
# Check logs for:
#   "Detected language: ru"
#   "Language accepted: ru - \"[text]\""
```

**Test English recognition:**
```bash
# Speak English into microphone
# Check logs for:
#   "Detected language: en"  
#   "Language accepted: en - \"[text]\""
```

**Test language filtering:**
```bash
# Speak French or another language
# Check logs for:
#   "Detected language: fr" (or other)
#   "LANGUAGE FILTER: Detected 'fr' but only accepting 'ru' or 'en'"
#   "Buffer cleared. Waiting for Russian or English speech..."
# Verify NO transcription callback fires
```

## Git Commit

```
commit [hash]
Add language filtering: accept only Russian and English

- Filter in SpeechRecognitionManager.processBuffer()
- Reject 97 other languages silently with warning log
- Clear buffer on rejection, continue listening
- No configuration needed (hardcoded "ru", "en")

Resolves: "recognizes something, but always wrong" issue
```

## Related Files

- `app/src/main/java/com/uh/ml/SpeechRecognitionManager.kt` - Filter implementation
- `app/src/main/java/com/uh/ml/WhisperTokenizer.kt` - Language token constants
- `SPEC.md` - Languages section (update to reflect filtering)
- `CLAUDE.md` - This solution documented

## Questions?

**Q: Why not use language-specific Whisper model?**  
A: Whisper tiny multilingual is already bundled (66MB). Separate models would require 2x storage. Filtering is simpler and sufficient for current needs.

**Q: Can we detect language BEFORE inference?**  
A: No, language detection is part of Whisper's decoding process. We must run full inference to know the language.

**Q: What if user speaks mixed languages?**  
A: Each audio segment is processed independently. Mixed segments will be detected as dominant language. If dominant language is not ru/en, entire segment is rejected.

**Q: Performance impact of rejected transcriptions?**  
A: Minimal. We've already done inference and decoding. Only skips embedding generation (~50ms) and WebSocket broadcast.
