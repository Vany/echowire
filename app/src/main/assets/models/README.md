# ML Models

This directory contains bundled ML models extracted on first app run:
- whisper_tiny.tflite (66MB) - Whisper tiny multilingual model
- whisper_vocab.json (NEEDED) - Whisper tokenizer vocabulary (51865 tokens)
- embedding.onnx (86MB) - all-MiniLM-L6-v2 embedding model
- tokenizer.json (455KB) - Tokenizer for embeddings

## Downloading Models

Run the download script from project root:
```bash
./scripts/download_models.sh
```

Models are downloaded from:
- Whisper model: https://github.com/usefulsensors/openai-whisper
- Whisper vocabulary: https://huggingface.co/openai/whisper-tiny (vocab.json)
- Embeddings: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2

**TODO:** Add Whisper vocabulary file download to script.

Total size: ~177MB (+ ~1MB for whisper vocab when added)
