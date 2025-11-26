# ML Models

This directory contains bundled ML models extracted on first app run:
- whisper_tiny.tflite (66MB) - Whisper tiny multilingual model
- embedding.onnx (86MB) - all-MiniLM-L6-v2 embedding model
- tokenizer.json (455KB) - Tokenizer for embeddings

## Downloading Models

Run the download script from project root:
```bash
./scripts/download_models.sh
```

Models are downloaded from:
- Whisper: https://github.com/usefulsensors/openai-whisper
- Embeddings: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2

Total size: ~177MB
