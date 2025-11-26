package com.uh.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Thread-safe circular buffer for accumulating audio samples
 * Supports efficient add/read operations without reallocation
 * 
 * @param capacitySamples Maximum number of samples to store (typically 30 sec * 16000 Hz = 480000)
 */
class CircularAudioBuffer(private val capacitySamples: Int) {
    
    // Circular buffer storage
    private val buffer = ShortArray(capacitySamples)
    
    // Write position (where next sample goes)
    private var writePos = 0
    
    // Number of samples currently stored
    private var sampleCount = 0
    
    // Timestamps
    private var firstSampleTimestamp: Long = 0
    private var lastSampleTimestamp: Long = 0
    
    // Thread safety
    private val lock = ReentrantLock()
    
    /**
     * Add audio samples to buffer
     * If buffer is full, oldest samples are overwritten
     * 
     * @param samples Audio samples to add
     * @param timestamp Timestamp of first sample in milliseconds
     */
    fun add(samples: ShortArray, timestamp: Long) = lock.withLock {
        if (samples.isEmpty()) return@withLock
        
        // Store timestamp of first sample if buffer was empty
        if (sampleCount == 0) {
            firstSampleTimestamp = timestamp
        }
        
        // Update last sample timestamp
        lastSampleTimestamp = timestamp
        
        // Copy samples to circular buffer
        for (sample in samples) {
            buffer[writePos] = sample
            writePos = (writePos + 1) % capacitySamples
            
            // Increment sample count (capped at capacity)
            if (sampleCount < capacitySamples) {
                sampleCount++
            }
        }
    }
    
    /**
     * Get all samples currently in buffer
     * Returns a copy, safe to modify
     * 
     * @return Array of all samples in chronological order
     */
    fun getAll(): ShortArray = lock.withLock {
        if (sampleCount == 0) {
            return ShortArray(0)
        }
        
        val result = ShortArray(sampleCount)
        
        if (sampleCount < capacitySamples) {
            // Buffer not yet full, samples are at [0..sampleCount)
            System.arraycopy(buffer, 0, result, 0, sampleCount)
        } else {
            // Buffer full, samples wrap around
            // Read from writePos (oldest) to end
            val firstChunkSize = capacitySamples - writePos
            System.arraycopy(buffer, writePos, result, 0, firstChunkSize)
            
            // Read from start to writePos (newest)
            if (writePos > 0) {
                System.arraycopy(buffer, 0, result, firstChunkSize, writePos)
            }
        }
        
        return result
    }
    
    /**
     * Get the last N samples
     * Useful for processing overlapping windows
     * 
     * @param n Number of samples to retrieve
     * @return Array of last N samples (or fewer if buffer has less)
     */
    fun getLast(n: Int): ShortArray = lock.withLock {
        val samplesToGet = min(n, sampleCount)
        if (samplesToGet == 0) {
            return ShortArray(0)
        }
        
        val result = ShortArray(samplesToGet)
        
        // Calculate start position for last N samples
        val startPos = if (sampleCount < capacitySamples) {
            // Buffer not full
            sampleCount - samplesToGet
        } else {
            // Buffer full, wrap around
            (writePos - samplesToGet + capacitySamples) % capacitySamples
        }
        
        // Copy samples
        if (startPos + samplesToGet <= capacitySamples) {
            // No wrap-around needed
            System.arraycopy(buffer, startPos, result, 0, samplesToGet)
        } else {
            // Wrap-around needed
            val firstChunkSize = capacitySamples - startPos
            System.arraycopy(buffer, startPos, result, 0, firstChunkSize)
            System.arraycopy(buffer, 0, result, firstChunkSize, samplesToGet - firstChunkSize)
        }
        
        return result
    }
    
    /**
     * Clear all samples from buffer
     */
    fun clear() = lock.withLock {
        writePos = 0
        sampleCount = 0
        firstSampleTimestamp = 0
        lastSampleTimestamp = 0
    }
    
    /**
     * Get number of samples currently stored
     */
    fun size(): Int = lock.withLock { sampleCount }
    
    /**
     * Get duration of audio in buffer (seconds)
     * Assumes 16kHz sample rate
     */
    fun durationSeconds(): Float = lock.withLock {
        sampleCount / 16000f
    }
    
    /**
     * Get timestamp of first sample in buffer
     */
    val startTimestamp: Long
        get() = lock.withLock { firstSampleTimestamp }
    
    /**
     * Get timestamp of last sample in buffer
     */
    val endTimestamp: Long
        get() = lock.withLock { lastSampleTimestamp }
    
    /**
     * Check if buffer is empty
     */
    fun isEmpty(): Boolean = lock.withLock { sampleCount == 0 }
    
    /**
     * Check if buffer is full
     */
    fun isFull(): Boolean = lock.withLock { sampleCount >= capacitySamples }
}
