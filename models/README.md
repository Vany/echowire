# Models Directory

This directory contains ML models for the EchoWire speech recognition service.
Models are **not committed to git** due to large file sizes.

## Structure

```
models/
├── whisper/
│   ├── tiny.tflite           # Whisper tiny multilingual (~39MB)
│   ├── base.tflite           # Whisper base (optional, ~74MB)
│   └── small.tflite          # Whisper small (optional, ~244MB)
├── embeddings/
│   ├── all-MiniLM-L6-v2.onnx # Sentence embeddings (~86MB)
│   └── tokenizer.json         # HuggingFace tokenizer (~455KB)
└── README.md
```

## Download & Convert Models

Use the Makefile target to download and convert all required models:

```bash
make models
```

This will:
1. Download Whisper tiny from HuggingFace (ONNX format)
2. Convert ONNX → TFLite with optimizations
3. Download all-MiniLM-L6-v2 ONNX model
4. Download tokenizer configuration

## Manual Download

If the automated script fails, download manually:

### Whisper Tiny (TFLite)
- Source: https://huggingface.co/openai/whisper-tiny
- Conversion: Use `scripts/convert_whisper_to_tflite.py`

### all-MiniLM-L6-v2 (ONNX)
- Source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- Files needed: `model.onnx`, `tokenizer.json`

## Model Details

### Whisper Tiny Multilingual
- Parameters: 39M
- Languages: 99 (including English, Russian)
- Sample rate: 16kHz
- Format: TFLite with FP32 weights
- Mel bins: 80
- Max audio length: 30 seconds

### all-MiniLM-L6-v2
- Parameters: 23M
- Embedding dimensions: 384
- Format: ONNX FP32
- Max sequence length: 256 tokens

## Storage Requirements

- Whisper tiny: ~39MB
- all-MiniLM-L6-v2: ~86MB
- Tokenizer: ~455KB
- **Total**: ~126MB
