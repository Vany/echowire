#!/usr/bin/env python3
"""
Test what Whisper TFLite model outputs when given silence (zeros).
This will tell us if the model has a "default" output sequence.
"""
import numpy as np
import sys

# Try to import TFLite
try:
    import tensorflow as tf
except ImportError:
    print("ERROR: tensorflow not installed")
    print("Install with: pip3 install tensorflow")
    sys.exit(1)

# Load model
model_path = 'app/src/main/assets/models/whisper_tiny.tflite'
interpreter = tf.lite.Interpreter(model_path=model_path)
interpreter.allocate_tensors()

# Get input/output details
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

print("=== Model Structure ===")
print(f"Input: {input_details[0]['name']}")
print(f"  Shape: {input_details[0]['shape']}")
print(f"  Type: {input_details[0]['dtype']}")
print(f"\nOutput: {output_details[0]['name']}")
print(f"  Shape: {output_details[0]['shape']}")
print(f"  Type: {output_details[0]['dtype']}")

# Create silence input (all zeros)
input_shape = input_details[0]['shape']
silence = np.zeros(input_shape, dtype=np.float32)

print(f"\n=== Testing with Silence ===")
print(f"Input: {input_shape} of zeros")

# Run inference
interpreter.set_tensor(input_details[0]['index'], silence)
interpreter.invoke()

# Get output
output_data = interpreter.get_tensor(output_details[0]['index'])
output_shape = output_data.shape
output_dtype = output_data.dtype

print(f"Output shape: {output_shape}")
print(f"Output dtype: {output_dtype}")

# Check if output is logits or token IDs
if output_dtype in [np.float32, np.float64]:
    print("\n✓ Output is LOGITS (float)")
    print("  Need to apply argmax to get token IDs")
    
    # Apply argmax
    if len(output_shape) == 3:  # [batch, seq, vocab]
        token_ids = np.argmax(output_data[0], axis=-1)
    elif len(output_shape) == 2:  # [batch, seq] - shouldn't happen for logits
        token_ids = output_data[0].astype(int)
    else:
        print(f"ERROR: Unexpected output shape: {output_shape}")
        sys.exit(1)
        
elif output_dtype in [np.int32, np.int64]:
    print("\n✓ Output is TOKEN IDs (int)")
    print("  No argmax needed")
    token_ids = output_data[0]
else:
    print(f"\nERROR: Unknown output dtype: {output_dtype}")
    sys.exit(1)

print(f"\nFirst 20 token IDs: {token_ids[:20].tolist()}")
print(f"Unique tokens in first 20: {len(np.unique(token_ids[:20]))}")
print(f"All same token? {len(np.unique(token_ids[:20])) == 1}")

# Check for language tokens
language_tokens = list(range(50259, 50358))
for i, tok in enumerate(token_ids[:10]):
    if tok in language_tokens:
        print(f"\n✓ Language token found at position {i}: {tok}")
        # Map common ones
        lang_map = {
            50259: "en", 50260: "zh", 50261: "de", 50262: "es",
            50263: "ru", 50264: "ko", 50265: "fr", 50266: "ja"
        }
        if tok in lang_map:
            print(f"  Language: {lang_map[tok]}")
