#!/bin/bash
# Download ML models for UH speech recognition app

set -e

MODELS_DIR="app/src/main/assets/models"
mkdir -p "$MODELS_DIR"

echo "Downloading models to $MODELS_DIR..."

# Whisper tiny multilingual (TFLite)
# Using usefulsensors converted models
echo "Downloading Whisper tiny multilingual TFLite model..."
curl -L "https://github.com/usefulsensors/openai-whisper/raw/main/models/whisper-tiny.tflite" \
  -o "$MODELS_DIR/whisper_tiny.tflite" || echo "WARNING: Whisper download failed - will need manual download"

# Whisper vocabulary (GPT-2 BPE, 51865 tokens)
# From OpenAI Whisper HuggingFace repository
echo "Downloading Whisper vocabulary..."
curl -L "https://huggingface.co/openai/whisper-tiny/resolve/main/vocab.json" \
  -o "$MODELS_DIR/whisper_vocab.json" || \
echo "WARNING: Whisper vocab download failed - will need manual download"

# all-MiniLM-L6-v2 ONNX model for embeddings (384 dimensions)
echo "Downloading all-MiniLM-L6-v2 embedding model..."
curl -L "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx" \
  -o "$MODELS_DIR/embedding.onnx" || echo "WARNING: Embedding model download failed"

# Tokenizer for embeddings
echo "Downloading tokenizer..."
curl -L "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json" \
  -o "$MODELS_DIR/tokenizer.json" || echo "WARNING: Tokenizer download failed"

echo ""
echo "Download complete! Model files:"
ls -lh "$MODELS_DIR"
echo ""
echo "Total size:"
du -sh "$MODELS_DIR"
