# Echowire - Speech Diarization & Emotion Recognition

## Overview
Audio analysis tool that detects speakers and their emotions from audio input.

## POC Goal
Script that takes audio file, identifies all speakers, and outputs diarization + emotion data to stdout.

## Models Stack

| Component | Model | Format | Size |
|-----------|-------|--------|------|
| VAD | Silero VAD | ONNX (→TFLite) | ~2MB |
| Speaker Embeddings | SpeechBrain ECAPA-TDNN | PyTorch | ~100MB |
| Clustering | Agglomerative (cosine) | scikit-learn | - |
| Emotion | SpeechBrain wav2vec2-IEMOCAP | PyTorch | ~400MB |

All models stored locally in `./models/` directory.

## Pipeline

```
[Audio File] 
    ↓
[Preprocess: 16kHz mono]
    ↓
[Silero VAD] → speech segments with timestamps
    ↓
[ECAPA-TDNN] → 192-dim embedding per segment
    ↓
[Agglomerative Clustering] → speaker labels
    ↓
[wav2vec2 Emotion] → emotion per segment
    ↓
[JSON Output]
```

## CLI Interface

```bash
python diarize.py <audio_file> [--num-speakers N] [--threshold 0.5]
```

## Output Format (JSON to stdout)

```json
{
  "file": "input.wav",
  "duration": 120.5,
  "speakers": 2,
  "segments": [
    {"start": 0.5, "end": 3.2, "speaker": 0, "emotion": "neutral", "confidence": 0.87},
    {"start": 3.8, "end": 7.1, "speaker": 1, "emotion": "happy", "confidence": 0.65}
  ]
}
```

## Emotions Detected
IEMOCAP model outputs: `angry`, `happy`, `neutral`, `sad`

## Audio Requirements
- Sample rate: 16kHz (auto-resampled)
- Channels: mono (auto-converted)
- Formats: wav, mp3, flac, ogg, m4a

## Setup

```bash
make deps     # Create venv, install dependencies
make models   # Download models to ./models/
make test     # Run on test audio
make tflite   # Optional: convert ONNX→TFLite
```

## Next Steps
1. POC implementation
2. Test suite with audio samples
3. Model evaluation and selection
