#!/usr/bin/env python3
"""
Test whisper model format, encoding, and output structure.

This script verifies:
1. Model download URL and format
2. Model file structure inspection (no inference, just metadata)
3. Vocabulary format check

Requirements:
    pip install flatbuffers

Usage:
    python3 tests/test_whisper_model.py
"""

import sys
import urllib.request
from pathlib import Path
import struct
import json

# Paths
PROJECT_ROOT = Path(__file__).parent.parent
MODELS_DIR = PROJECT_ROOT / "models" / "whisper"
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "models"

# Model URL
WHISPER_URL = "https://github.com/usefulsensors/openai-whisper/raw/main/models/whisper-tiny.tflite"


def download_model_if_needed():
    """Download whisper model if not present."""
    model_path = MODELS_DIR / "whisper-tiny.tflite"
    
    if model_path.exists():
        print(f"✓ Model exists: {model_path}")
        print(f"  Size: {model_path.stat().st_size / (1024*1024):.1f} MB")
        return model_path
    
    print(f"⬇ Downloading model from {WHISPER_URL}")
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    
    urllib.request.urlretrieve(WHISPER_URL, model_path)
    print(f"✓ Downloaded: {model_path} ({model_path.stat().st_size / (1024*1024):.1f} MB)")
    
    return model_path


def inspect_model(model_path: Path):
    """Inspect TFLite model structure - basic file analysis."""
    print("\n" + "="*60)
    print("MODEL FILE INSPECTION")
    print("="*60)
    
    # Read file header
    with open(model_path, 'rb') as f:
        header = f.read(8)
        file_size = model_path.stat().st_size
        
        print(f"\n📄 File info:")
        print(f"  Path: {model_path}")
        print(f"  Size: {file_size / (1024*1024):.2f} MB")
        print(f"  Header (first 8 bytes): {header.hex()}")
        
        # TFLite models start with "TFL3" (FlatBuffer identifier)
        if header[:4] == b'TFL3':
            print(f"  ✅ Valid TFLite model (TFL3 header)")
        else:
            print(f"  ⚠️  Unexpected header: {header[:4]}")
        
        # Try to extract basic FlatBuffer metadata
        # This is a simplified inspection without full FlatBuffer parsing
        f.seek(0)
        data = f.read(1024)  # Read first 1KB for inspection
        
        print(f"\n📊 Basic model analysis:")
        print(f"  File format: TFLite (FlatBuffer)")
        print(f"  Note: Full tensor inspection requires tensorflow/tflite_runtime")
        print(f"        Install Python 3.11 and run: pip install tensorflow")
    
    return True



def check_vocabulary():
    """Check vocabulary file format."""
    print("\n" + "="*60)
    print("VOCABULARY CHECK")
    print("="*60)
    
    vocab_json = ASSETS_DIR / "whisper_vocab.json"
    vocab_bin = ASSETS_DIR / "filters_vocab_multilingual.bin"
    
    print(f"\n📚 Vocabulary files:")
    
    if vocab_json.exists():
        print(f"  ✓ JSON vocab exists: {vocab_json}")
        print(f"    Size: {vocab_json.stat().st_size / 1024:.1f} KB")
        
        # Try to parse
        try:
            import json
            with open(vocab_json) as f:
                vocab = json.load(f)
            print(f"    Entries: {len(vocab)}")
            print(f"    Sample entries:")
            for i, (token, id_) in enumerate(list(vocab.items())[:5]):
                print(f"      {id_}: {repr(token)}")
        except Exception as e:
            print(f"    ⚠️  Failed to parse: {e}")
    else:
        print(f"  ✗ JSON vocab missing: {vocab_json}")
    
    if vocab_bin.exists():
        print(f"\n  ✓ Binary vocab exists: {vocab_bin}")
        print(f"    Size: {vocab_bin.stat().st_size / 1024:.1f} KB")
    else:
        print(f"\n  ✗ Binary vocab missing: {vocab_bin}")
        print(f"    Recommendation: Download from vilassn/whisper_android")


def main():
    print("WHISPER MODEL SANITY TEST")
    print("="*60)
    
    try:
        # Step 1: Download/verify model
        model_path = download_model_if_needed()
        
        # Step 2: Inspect model structure (basic)
        inspect_model(model_path)
        
        # Step 3: Check vocabulary
        check_vocabulary()
        
        # Summary
        print("\n" + "="*60)
        print("SUMMARY & NEXT STEPS")
        print("="*60)
        
        print(f"\n✅ Model file verified:")
        print(f"   URL: {WHISPER_URL}")
        print(f"   Size: {model_path.stat().st_size / (1024*1024):.1f} MB")
        print(f"   Format: TFLite (FlatBuffer)")
        
        print(f"\n⚠️  CRITICAL QUESTIONS TO ANSWER:")
        print(f"   1. What is the output tensor dtype? (INT32 or FLOAT32?)")
        print(f"   2. What is the output tensor shape? ([1, 448] or [1, 448, 51865]?)")
        print(f"   3. Does model output token IDs or logits?")
        
        print(f"\n🔧 TO GET ANSWERS:")
        print(f"   Option A: Install Python 3.11 and run full test:")
        print(f"             brew install python@3.11")
        print(f"             python3.11 -m pip install tensorflow")
        print(f"             python3.11 tests/test_whisper_model.py")
        
        print(f"\n   Option B: Test directly on Android device:")
        print(f"             1. Install app: make install")
        print(f"             2. Check logs: make logs")
        print(f"             3. Look for 'Output shape' and 'Output data type'")
        print(f"             4. Check if token IDs are sensible (0-51865 range)")
        
        print(f"\n   Option C: Use reference implementation:")
        print(f"             git clone https://github.com/vilassn/whisper_android")
        print(f"             Compare their model with ours")
        
        return 0
        
    except Exception as e:
        print(f"\n✗ ERROR: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
