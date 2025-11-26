package com.uh.audio

/**
 * Simple energy-based Voice Activity Detection (VAD).
 * 
 * Uses audio energy levels to distinguish speech from silence.
 * Not sophisticated (no spectral analysis), but sufficient for:
 * - Reducing unnecessary speech recognition processing
 * - Detecting when user starts/stops speaking
 * - Triggering transcript segmentation
 * 
 * Algorithm:
 * 1. Compare audio level to threshold
 * 2. Require N consecutive frames above threshold to trigger speech
 * 3. Require N consecutive frames below threshold to trigger silence
 * 4. Provides hysteresis to avoid flickering on brief pauses
 * 
 * Performance:
 * - O(1) per frame (single comparison)
 * - No memory allocation
 * - Thread-safe (no shared state)
 * 
 * Limitations:
 * - Energy-based only (no spectral analysis)
 * - Sensitive to background noise
 * - No adaptation to noise floor
 * - For production: consider WebRTC VAD or Silero VAD
 */
class SimpleVAD(
    /**
     * Energy threshold for speech detection.
     * Range: 0.0 (silence) to 1.0 (max amplitude)
     * 
     * Typical values:
     * - 0.01: Very sensitive (detects whispers, also background noise)
     * - 0.02: Balanced (default, good for normal speech)
     * - 0.05: Conservative (only loud/clear speech)
     * - 0.10: Very conservative (shouting only)
     */
    private val threshold: Float = 0.02f,
    
    /**
     * Minimum consecutive frames required to trigger state change.
     * At 100ms per frame (10 frames/second):
     * - 3 frames = 300ms reaction time
     * - 5 frames = 500ms reaction time
     * 
     * Higher values = less flickering, slower response
     * Lower values = faster response, more flickering
     */
    private val minConsecutiveFrames: Int = 3
) {
    
    // Frame counters for hysteresis
    private var speechFrameCount = 0
    private var silenceFrameCount = 0
    
    /**
     * Process audio frame and determine if speech is present.
     * 
     * State machine:
     * - Silent → Speech: requires minConsecutiveFrames above threshold
     * - Speech → Silent: requires minConsecutiveFrames below threshold
     * - Maintains current state during brief transitions
     * 
     * @param audioLevel Audio level from AudioCaptureManager (0.0-1.0)
     * @return true if speech detected, false if silence
     */
    fun processFrame(audioLevel: Float): Boolean {
        return if (audioLevel > threshold) {
            // Audio above threshold
            speechFrameCount++
            silenceFrameCount = 0
            speechFrameCount >= minConsecutiveFrames
        } else {
            // Audio below threshold
            silenceFrameCount++
            if (silenceFrameCount > minConsecutiveFrames) {
                speechFrameCount = 0
                false
            } else {
                // Maintain speech state for brief pauses (e.g., between words)
                speechFrameCount >= minConsecutiveFrames
            }
        }
    }
    
    /**
     * Reset VAD state to initial (silence).
     * Call when starting new recording session.
     */
    fun reset() {
        speechFrameCount = 0
        silenceFrameCount = 0
    }
    
    /**
     * Get current speech detection status without processing new frame.
     * @return true if currently in speech state
     */
    fun isSpeechActive(): Boolean {
        return speechFrameCount >= minConsecutiveFrames
    }
    
    /**
     * Get number of consecutive speech frames detected.
     * Useful for debugging and tuning.
     */
    fun getSpeechFrameCount(): Int = speechFrameCount
    
    /**
     * Get number of consecutive silence frames detected.
     * Useful for debugging and tuning.
     */
    fun getSilenceFrameCount(): Int = silenceFrameCount
}
