#!/usr/bin/env python3
"""
Download and convert ML models for UH speech recognition service.

This script downloads Whisper models from HuggingFace, converts them to TFLite format,
and downloads the sentence embedding model.

Requirements:
    pip install transformers torch tensorflow optimum[exporters] huggingface_hub

Usage:
    python3 scripts/download_convert_models.py [--model tiny|base|small]
"""

import os
import sys
import argparse
import urllib.request
from pathlib import Path

# Constants
PROJECT_ROOT = Path(__file__).parent.parent
MODELS_DIR = PROJECT_ROOT / "models"
WHISPER_DIR = MODELS_DIR / "whisper"
EMBEDDINGS_DIR = MODELS_DIR / "embeddings"

# Model URLs
WHISPER_ONNX_URLS = {
    "tiny": "https://huggingface.co/openai/whisper-tiny/resolve/main/model.onnx",
    "base": "https://huggingface.co/openai/whisper-base/resolve/main/model.onnx",
    "small": "https://huggingface.co/openai/whisper-small/resolve/main/model.onnx",
}

EMBEDDINGS_URLS = {
    "model": "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx",
    "tokenizer": "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json",
}


def download_file(url: str, dest_path: Path, description: str = "file"):
    """Download file with progress indicator."""
    if dest_path.exists():
        print(f"✓ {description} already exists: {dest_path}")
        return

    print(f"⬇ Downloading {description} from {url}")
    dest_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        # Simple download without progress bar for now
        urllib.request.urlretrieve(url, dest_path)
        size_mb = dest_path.stat().st_size / (1024 * 1024)
        print(f"✓ Downloaded {description} ({size_mb:.1f} MB)")
    except Exception as e:
        print(f"✗ Failed to download {description}: {e}", file=sys.stderr)
        if dest_path.exists():
            dest_path.unlink()
        raise


def convert_whisper_onnx_to_tflite(onnx_path: Path, tflite_path: Path, model_size: str):
    """
    Convert Whisper ONNX model to TFLite format.
    
    This is a placeholder - actual conversion requires:
    1. Load ONNX model
    2. Convert to TensorFlow SavedModel
    3. Convert to TFLite with optimizations
    
    For now, we'll use pre-converted models or direct HuggingFace download.
    """
    print(f"⚠ ONNX → TFLite conversion not implemented yet")
    print(f"  Please download pre-converted TFLite model or use alternative approach")
    
    # TODO: Implement actual conversion
    # This requires:
    # import tensorflow as tf
    # import onnx
    # from onnx_tf.backend import prepare
    # 
    # onnx_model = onnx.load(str(onnx_path))
    # tf_model = prepare(onnx_model)
    # converter = tf.lite.TFLiteConverter.from_saved_model(tf_model.graph.as_graph_def())
    # converter.optimizations = [tf.lite.Optimize.DEFAULT]
    # tflite_model = converter.convert()
    # tflite_path.write_bytes(tflite_model)
    
    raise NotImplementedError("ONNX to TFLite conversion requires manual setup")


def download_whisper_direct_tflite(model_size: str):
    """
    Download pre-converted Whisper TFLite model from HuggingFace.
    
    Currently using usefulsensors/openai-whisper repository which has
    pre-converted TFLite models.
    """
    tflite_path = WHISPER_DIR / f"{model_size}.tflite"
    
    if tflite_path.exists():
        print(f"✓ Whisper {model_size} TFLite already exists")
        return tflite_path
    
    # Try to download from usefulsensors/openai-whisper (has TFLite versions)
    url = f"https://huggingface.co/usefulsensors/openai-whisper/resolve/main/whisper_{model_size}.tflite"
    
    print(f"⬇ Downloading Whisper {model_size} TFLite from usefulsensors")
    download_file(url, tflite_path, f"Whisper {model_size} TFLite")
    
    return tflite_path


def download_embedding_model():
    """Download sentence embedding model and tokenizer."""
    model_path = EMBEDDINGS_DIR / "all-MiniLM-L6-v2.onnx"
    tokenizer_path = EMBEDDINGS_DIR / "tokenizer.json"
    
    download_file(
        EMBEDDINGS_URLS["model"],
        model_path,
        "all-MiniLM-L6-v2 ONNX model"
    )
    
    download_file(
        EMBEDDINGS_URLS["tokenizer"],
        tokenizer_path,
        "tokenizer.json"
    )
    
    return model_path, tokenizer_path


def verify_models(model_size: str):
    """Verify all required models exist."""
    whisper_path = WHISPER_DIR / f"{model_size}.tflite"
    embedding_path = EMBEDDINGS_DIR / "all-MiniLM-L6-v2.onnx"
    tokenizer_path = EMBEDDINGS_DIR / "tokenizer.json"
    
    all_exist = all([
        whisper_path.exists(),
        embedding_path.exists(),
        tokenizer_path.exists(),
    ])
    
    if all_exist:
        print("\n✅ All models verified:")
        print(f"   Whisper {model_size}: {whisper_path.stat().st_size / (1024*1024):.1f} MB")
        print(f"   Embeddings: {embedding_path.stat().st_size / (1024*1024):.1f} MB")
        print(f"   Tokenizer: {tokenizer_path.stat().st_size / 1024:.1f} KB")
        return True
    else:
        print("\n✗ Missing models:", file=sys.stderr)
        if not whisper_path.exists():
            print(f"   - {whisper_path}", file=sys.stderr)
        if not embedding_path.exists():
            print(f"   - {embedding_path}", file=sys.stderr)
        if not tokenizer_path.exists():
            print(f"   - {tokenizer_path}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(
        description="Download and convert ML models for UH service"
    )
    parser.add_argument(
        "--model",
        choices=["tiny", "base", "small"],
        default="tiny",
        help="Whisper model size to download (default: tiny)"
    )
    parser.add_argument(
        "--skip-whisper",
        action="store_true",
        help="Skip Whisper model download"
    )
    parser.add_argument(
        "--skip-embeddings",
        action="store_true",
        help="Skip embeddings model download"
    )
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("UH Model Download & Conversion Tool")
    print("=" * 60)
    
    # Create directories
    WHISPER_DIR.mkdir(parents=True, exist_ok=True)
    EMBEDDINGS_DIR.mkdir(parents=True, exist_ok=True)
    
    try:
        # Download Whisper TFLite model
        if not args.skip_whisper:
            print(f"\n[1/2] Downloading Whisper {args.model} model...")
            download_whisper_direct_tflite(args.model)
        
        # Download embedding model
        if not args.skip_embeddings:
            print(f"\n[2/2] Downloading embedding model...")
            download_embedding_model()
        
        # Verify all models
        print("\n" + "=" * 60)
        if verify_models(args.model):
            print("\n🎉 All models ready! You can now run: make install")
            return 0
        else:
            print("\n⚠ Some models are missing. Please check errors above.", file=sys.stderr)
            return 1
    
    except Exception as e:
        print(f"\n✗ Error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
