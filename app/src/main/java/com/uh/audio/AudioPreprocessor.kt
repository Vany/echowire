package com.uh.audio

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.*

/**
 * Converts PCM audio samples to mel spectrogram for Whisper inference
 * 
 * Whisper requirements:
 * - 16kHz sample rate
 * - 80 mel bins
 * - 25ms window (400 samples)
 * - 10ms hop (160 samples)
 * - Log mel scale normalized to [-1, 1]
 */
class AudioPreprocessor {
    
    companion object {
        private const val TAG = "AudioPreprocessor"
        
        // Audio parameters (must match Whisper training)
        const val SAMPLE_RATE = 16000
        const val N_FFT = 512
        const val HOP_LENGTH = 160  // 10ms at 16kHz
        const val WIN_LENGTH = 400  // 25ms at 16kHz
        const val N_MELS = 80       // Whisper standard
        
        // Mel scale parameters
        private const val MIN_MEL_FREQ = 0.0
        private const val MAX_MEL_FREQ = 8000.0  // Nyquist for 16kHz
        
        // Normalization constants (empirically determined for Whisper)
        private const val MEL_MEAN = -4.2677393f
        private const val MEL_STD = 4.5689974f
    }
    
    // Mel filter banks (computed once, reused)
    private val melFilterBanks: Array<FloatArray> by lazy {
        createMelFilterBanks()
    }
    
    // Hamming window coefficients (computed once, reused)
    private val hammingWindow: DoubleArray by lazy {
        createHammingWindow()
    }
    
    // FFT engine (reusable, thread-safe if not shared)
    private val fft = DoubleFFT_1D(N_FFT.toLong())
    
    /**
     * Convert PCM samples to mel spectrogram
     * 
     * @param pcmSamples Raw 16-bit PCM samples from microphone
     * @return Mel spectrogram [80 × numFrames] normalized to [-1, 1]
     */
    fun pcmToMelSpectrogram(pcmSamples: ShortArray): Array<FloatArray> {
        // 1. Normalize PCM to float [-1.0, 1.0]
        val normalizedSamples = pcmSamples.map { it / 32768.0 }.toDoubleArray()
        
        // DEBUG: Check if we have actual audio data
        val maxSample = normalizedSamples.maxOrNull() ?: 0.0
        val minSample = normalizedSamples.minOrNull() ?: 0.0
        val avgAbsSample = normalizedSamples.map { abs(it) }.average()
        Log.d(TAG, "PCM stats: samples=${pcmSamples.size}, " +
                "range=[${minSample}, ${maxSample}], " +
                "avgAbs=${avgAbsSample}")
        
        if (avgAbsSample < 0.001) {
            Log.w(TAG, "WARNING: Audio is very quiet (avgAbs=${avgAbsSample}), " +
                    "might be silence or mic issue!")
        }
        
        // 2. Compute STFT (Short-Time Fourier Transform)
        val stft = computeSTFT(normalizedSamples)
        
        // 3. Convert to power spectrogram (magnitude squared)
        val powerSpec = stft.map { frame ->
            FloatArray(frame.size) { i -> (frame[i] * frame[i]).toFloat() }
        }.toTypedArray()
        
        // 4. Apply mel filter banks
        val melSpec = applyMelFilters(powerSpec)
        
        // 5. Convert to log scale
        val logMelSpec = melSpec.map { frame ->
            frame.map { value ->
                // Add small epsilon to avoid log(0)
                ln(maxOf(value, 1e-10f))
            }.toFloatArray()
        }.toTypedArray()
        
        // 6. Normalize (mean=0, std=1 based on Whisper training)
        val normalizedMel = logMelSpec.map { frame ->
            frame.map { value ->
                (value - MEL_MEAN) / MEL_STD
            }.toFloatArray()
        }.toTypedArray()
        
        return normalizedMel
    }
    
    /**
     * Compute Short-Time Fourier Transform
     * 
     * @param samples Normalized audio samples
     * @return Complex STFT magnitudes [numFrames × (N_FFT/2 + 1)]
     */
    private fun computeSTFT(samples: DoubleArray): Array<DoubleArray> {
        val numFrames = 1 + (samples.size - WIN_LENGTH) / HOP_LENGTH
        val stft = Array(numFrames) { DoubleArray(N_FFT / 2 + 1) }
        
        for (frameIdx in 0 until numFrames) {
            val frameStart = frameIdx * HOP_LENGTH
            
            // Extract frame and apply window
            val frame = DoubleArray(N_FFT)
            for (i in 0 until WIN_LENGTH) {
                if (frameStart + i < samples.size) {
                    frame[i] = samples[frameStart + i] * hammingWindow[i]
                }
            }
            
            // Perform FFT (in-place, real FFT)
            // JTransforms expects: [re_0, re_1, ..., re_n/2, im_n/2-1, ..., im_1]
            fft.realForward(frame)
            
            // Compute magnitudes from complex FFT output
            // Real components: frame[0], frame[2], frame[4], ...
            // Imaginary components: frame[1], frame[3], frame[5], ...
            stft[frameIdx][0] = abs(frame[0])  // DC component (real only)
            
            for (i in 1 until N_FFT / 2) {
                val real = frame[2 * i]
                val imag = frame[2 * i + 1]
                stft[frameIdx][i] = sqrt(real * real + imag * imag)
            }
            
            // Nyquist frequency (real only)
            stft[frameIdx][N_FFT / 2] = abs(frame[1])
        }
        
        return stft
    }
    
    /**
     * Apply mel filter banks to power spectrogram
     * 
     * @param powerSpec Power spectrogram [numFrames × (N_FFT/2 + 1)]
     * @return Mel spectrogram [numFrames × N_MELS]
     */
    private fun applyMelFilters(powerSpec: Array<FloatArray>): Array<FloatArray> {
        return powerSpec.map { frame ->
            FloatArray(N_MELS) { melIdx ->
                // Dot product: mel filter bank × power spectrum
                melFilterBanks[melIdx].indices.sumOf { freqIdx ->
                    (melFilterBanks[melIdx][freqIdx] * frame[freqIdx]).toDouble()
                }.toFloat()
            }
        }.toTypedArray()
    }
    
    /**
     * Create mel filter banks
     * 
     * Converts linear frequency bins to mel scale with triangular filters
     * @return Mel filter banks [N_MELS × (N_FFT/2 + 1)]
     */
    private fun createMelFilterBanks(): Array<FloatArray> {
        val numFreqBins = N_FFT / 2 + 1
        val melFilters = Array(N_MELS) { FloatArray(numFreqBins) }
        
        // Convert frequency range to mel scale
        val minMel = hzToMel(MIN_MEL_FREQ)
        val maxMel = hzToMel(MAX_MEL_FREQ)
        
        // Create N_MELS + 2 equally spaced points in mel scale
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            minMel + i * (maxMel - minMel) / (N_MELS + 1)
        }
        
        // Convert back to Hz
        val hzPoints = melPoints.map { melToHz(it) }
        
        // Convert Hz to FFT bin indices
        val fftBins = hzPoints.map { hz ->
            ((N_FFT + 1) * hz / SAMPLE_RATE).toInt()
        }
        
        // Create triangular filters
        for (melIdx in 0 until N_MELS) {
            val leftBin = fftBins[melIdx]
            val centerBin = fftBins[melIdx + 1]
            val rightBin = fftBins[melIdx + 2]
            
            // Rising slope (left to center)
            for (bin in leftBin until centerBin) {
                if (bin < numFreqBins) {
                    melFilters[melIdx][bin] = 
                        ((bin - leftBin).toFloat() / (centerBin - leftBin))
                }
            }
            
            // Falling slope (center to right)
            for (bin in centerBin until rightBin) {
                if (bin < numFreqBins) {
                    melFilters[melIdx][bin] = 
                        ((rightBin - bin).toFloat() / (rightBin - centerBin))
                }
            }
        }
        
        return melFilters
    }
    
    /**
     * Create Hamming window coefficients
     * 
     * Reduces spectral leakage in FFT
     * @return Hamming window [WIN_LENGTH]
     */
    private fun createHammingWindow(): DoubleArray {
        return DoubleArray(WIN_LENGTH) { i ->
            0.54 - 0.46 * cos(2.0 * PI * i / (WIN_LENGTH - 1))
        }
    }
    
    /**
     * Convert frequency in Hz to mel scale
     * 
     * Mel scale is approximately linear below 1kHz, logarithmic above
     */
    private fun hzToMel(hz: Double): Double {
        return 2595.0 * log10(1.0 + hz / 700.0)
    }
    
    /**
     * Convert mel scale to frequency in Hz
     */
    private fun melToHz(mel: Double): Double {
        return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
    }
}
