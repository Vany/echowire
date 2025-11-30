# Microphone Volume Solutions

## Current Problem
- Peak level: -22 dB (too quiet)
- Target: -12 dB to -6 dB
- Need: +10 dB to +16 dB boost

## Applied Changes (Ready to Test)

### 1. **Increased Software Gain: 3.0x → 6.0x** ✅ DONE
- Should give ~6 dB boost
- Changed in: `AudioCaptureManager.DEFAULT_GAIN = 6.0f`

### 2. **Changed Audio Source: VOICE_RECOGNITION → MIC** ✅ DONE
- VOICE_RECOGNITION has AGC (Automatic Gain Control) that may suppress volume
- MIC gives raw microphone input without processing
- May be louder but with more background noise

## Additional Options (If Still Too Quiet)

### **Option 3: Try UNPROCESSED Audio Source**
```kotlin
MediaRecorder.AudioSource.UNPROCESSED  // Requires Android 7.0+
```
- Bypasses ALL Android audio processing
- Loudest possible, but may have noise
- Change line 113 in AudioCaptureManager.kt

### **Option 4: Increase Gain to 10.0x**
```kotlin
private const val DEFAULT_GAIN = 10.0f  // Maximum recommended
```
- Will be VERY sensitive
- May pick up background noise
- Risk of clipping on loud sounds

### **Option 5: Use AudioManager to Boost System Volume**

Add to UhService before starting audio capture:
```kotlin
val audioManager = getSystemService(AudioManager::class.java)
val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
```

**Note**: This changes system volume - affects other apps!

### **Option 6: Hardware Microphone Gain (Samsung-Specific)**

Samsung devices have hidden audio parameters:
```kotlin
val audioManager = getSystemService(AudioManager::class.java)
audioManager.setParameters("voice_call_mic_mute=off")
audioManager.setParameters("mic_volume=15")  // 0-15 scale
```

**Warning**: Non-standard, may not work on all Samsung devices.

### **Option 7: Use External Microphone**
- Bluetooth headset microphone
- USB-C microphone adapter
- Wired headset with inline mic
- These typically have better gain than built-in mic

## Recommended Testing Order

1. **Test current changes first** (6.0x gain + MIC source)
   - Install and check DB meter
   - Should see ~-16 dB to -12 dB now

2. **If still quiet → increase to 10.0x gain**
   - Change DEFAULT_GAIN to 10.0f
   - Rebuild and test

3. **If still quiet → try UNPROCESSED source**
   - Change AudioSource.MIC to AudioSource.UNPROCESSED
   - Rebuild and test

4. **If STILL quiet → hardware/external mic needed**
   - Built-in mic may be damaged
   - Or Android is limiting gain for safety

## Quick Test Instructions

```bash
# 1. Rebuild with new settings
make install

# 2. Start service and speak normally
# 3. Observe DB meter - should see:
#    - Silence: -60 dB to -40 dB
#    - Normal speech: -20 dB to -12 dB  ← TARGET
#    - Loud speech: -12 dB to -6 dB
#    - Shouting: -6 dB to 0 dB (red zone)

# 4. If DB meter shows good levels but recognition fails:
#    → Gain is working, problem is elsewhere (model/processing)
# 5. If DB meter still shows -22 dB:
#    → Need hardware solution or external mic
```

## Audio Source Comparison

| Source | Android Processing | Volume | Noise | Use Case |
|--------|-------------------|--------|-------|----------|
| **VOICE_RECOGNITION** | AGC, noise reduction | Low | Low | Voice assistants (default) |
| **MIC** | Minimal | Medium | Medium | General recording |
| **UNPROCESSED** | None | High | High | Professional audio |
| **VOICE_COMMUNICATION** | Echo cancel, AGC | Low | Very Low | Phone calls |
| **CAMCORDER** | Scene-dependent | Variable | Low | Video recording |

**Current setting**: MIC (good balance)

## Expected Results After Changes

With 6.0x gain + MIC source:
- **Quiet speech**: -25 dB → -16 dB ✓
- **Normal speech**: -22 dB → -13 dB ✓
- **Loud speech**: -18 dB → -9 dB ✓

This should be enough for good recognition.

## If Whisper Still Fails with Good DB Levels

If DB meter shows -12 dB but recognition is poor:
1. **Not a volume problem** - audio is loud enough
2. **Possible causes**:
   - Background noise (try quieter environment)
   - Speech not clear enough
   - Model needs fine-tuning
   - VAD threshold too high (not triggering)

## Safety Notes

- Gain >10.0x may cause:
  - Clipping (distortion) on loud sounds
  - Excessive background noise amplification
  - Reduced speech recognition quality
  
- Always monitor DB meter:
  - Red zone = too loud (reduce gain)
  - Green zone at -30 dB = too quiet (increase gain)
  - Yellow zone at -15 dB = perfect ✓

## Next Steps

1. Test with current changes (6.0x + MIC)
2. Report new DB level during normal speech
3. We can adjust from there
