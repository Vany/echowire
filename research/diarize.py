#!/usr/bin/env python3
"""
Speaker diarization and emotion recognition POC.

Usage:
    python diarize.py <audio_file> [--num-speakers N] [--threshold 0.5]
"""

import argparse
import json
import logging
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torchaudio
from sklearn.cluster import AgglomerativeClustering
from sklearn.metrics.pairwise import cosine_distances
from sklearn.preprocessing import normalize

MODELS_DIR = Path(__file__).parent / "models"
SAMPLE_RATE = 16000

logging.basicConfig(
    level=logging.INFO, format="%(levelname)s: %(message)s", stream=sys.stderr
)
log = logging.getLogger(__name__)


def load_audio(path: str) -> tuple[torch.Tensor, int]:
    """Load audio and convert to 16kHz mono."""
    waveform, sr = torchaudio.load(path)

    # Convert to mono
    if waveform.shape[0] > 1:
        waveform = waveform.mean(dim=0, keepdim=True)

    # Resample to 16kHz
    if sr != SAMPLE_RATE:
        resampler = torchaudio.transforms.Resample(sr, SAMPLE_RATE)
        waveform = resampler(waveform)

    return waveform, SAMPLE_RATE


class SileroVAD:
    """Silero VAD using silero-vad package."""

    def __init__(self, model_path: Path = None):
        # Use silero-vad package (ignores model_path, uses bundled model)
        from silero_vad import get_speech_timestamps, load_silero_vad

        self.model = load_silero_vad()
        self.get_speech_timestamps = get_speech_timestamps
        self.sample_rate = SAMPLE_RATE

    def detect_segments(
        self,
        waveform: torch.Tensor,
        threshold: float = 0.5,
        min_speech_ms: int = 250,
        min_silence_ms: int = 100,
    ) -> list[dict]:
        """Detect speech segments in audio."""
        audio = waveform.squeeze()

        timestamps = self.get_speech_timestamps(
            audio,
            self.model,
            sampling_rate=self.sample_rate,
            threshold=threshold,
            min_speech_duration_ms=min_speech_ms,
            min_silence_duration_ms=min_silence_ms,
            return_seconds=True,
        )

        return [{"start": t["start"], "end": t["end"]} for t in timestamps]


class SpeakerEncoder:
    """ECAPA-TDNN speaker encoder using SpeechBrain."""

    def __init__(self, model_dir: Path):
        from speechbrain.inference.speaker import EncoderClassifier

        self.encoder = EncoderClassifier.from_hparams(
            source="speechbrain/spkrec-ecapa-voxceleb", savedir=str(model_dir)
        )

    def encode(self, waveform: torch.Tensor) -> np.ndarray:
        """Extract speaker embedding from audio."""
        embedding = self.encoder.encode_batch(waveform)
        return embedding.squeeze().cpu().numpy()

    def encode_segments(
        self, waveform: torch.Tensor, segments: list[dict], sample_rate: int
    ) -> np.ndarray:
        """Extract embeddings for all segments."""
        embeddings = []
        for seg in segments:
            start = int(seg["start"] * sample_rate)
            end = int(seg["end"] * sample_rate)
            segment_audio = waveform[:, start:end]
            emb = self.encode(segment_audio)
            embeddings.append(emb)
        return np.stack(embeddings)


class EmotionClassifier:
    """Emotion classifier using SpeechBrain wav2vec2."""

    EMOTIONS = ["angry", "happy", "neutral", "sad"]

    def __init__(self, model_dir: Path):
        from speechbrain.inference.interfaces import foreign_class

        self.classifier = foreign_class(
            source="speechbrain/emotion-recognition-wav2vec2-IEMOCAP",
            savedir=str(model_dir),
            pymodule_file="custom_interface.py",
            classname="CustomEncoderWav2vec2Classifier",
        )

    def classify(self, waveform: torch.Tensor) -> tuple[str, float]:
        """Classify emotion in audio segment."""
        out_prob, score, index, text_lab = self.classifier.classify_batch(waveform)
        return text_lab[0], float(score[0])

    def classify_segments(
        self, waveform: torch.Tensor, segments: list[dict], sample_rate: int
    ) -> list[dict]:
        """Classify emotions for all segments."""
        results = []
        for seg in segments:
            start = int(seg["start"] * sample_rate)
            end = int(seg["end"] * sample_rate)
            segment_audio = waveform[:, start:end]
            emotion, confidence = self.classify(segment_audio)
            results.append({"emotion": emotion, "confidence": confidence})
        return results


def cluster_speakers(
    embeddings: np.ndarray, n_clusters: int | None = None, threshold: float = 0.5
) -> np.ndarray:
    """Cluster speaker embeddings using agglomerative clustering."""
    if len(embeddings) == 1:
        return np.array([0])

    # Normalize for cosine similarity
    embeddings_norm = normalize(embeddings)
    distance_matrix = cosine_distances(embeddings_norm)

    clustering = AgglomerativeClustering(
        n_clusters=n_clusters,
        distance_threshold=threshold if n_clusters is None else None,
        metric="precomputed",
        linkage="average",
    )
    return clustering.fit_predict(distance_matrix)


def main():
    parser = argparse.ArgumentParser(
        description="Speaker diarization and emotion recognition"
    )
    parser.add_argument("audio_file", help="Path to audio file")
    parser.add_argument(
        "--num-speakers",
        type=int,
        default=None,
        help="Known number of speakers (optional)",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.5,
        help="Clustering distance threshold (default: 0.5)",
    )
    args = parser.parse_args()

    start_time = time.time()

    # Validate input
    audio_path = Path(args.audio_file)
    if not audio_path.exists():
        log.error(f"File not found: {audio_path}")
        sys.exit(1)

    # Load audio
    log.info(f"Loading audio: {audio_path}")
    waveform, sr = load_audio(str(audio_path))
    duration = waveform.shape[1] / sr
    log.info(f"Duration: {duration:.2f}s")

    # VAD
    log.info("Detecting speech segments...")
    vad = SileroVAD(MODELS_DIR / "silero_vad.onnx")
    segments = vad.detect_segments(waveform)

    if not segments:
        log.warning("No speech detected")
        output = {
            "file": str(audio_path),
            "duration": duration,
            "speakers": 0,
            "segments": [],
        }
        print(json.dumps(output, indent=2))
        return

    log.info(f"Found {len(segments)} speech segments")

    # Speaker embeddings
    log.info("Extracting speaker embeddings...")
    encoder = SpeakerEncoder(MODELS_DIR / "ecapa-tdnn")
    embeddings = encoder.encode_segments(waveform, segments, sr)

    # Clustering
    log.info("Clustering speakers...")
    labels = cluster_speakers(
        embeddings, n_clusters=args.num_speakers, threshold=args.threshold
    )
    num_speakers = len(set(labels))
    log.info(f"Identified {num_speakers} speakers")

    # Emotion detection
    log.info("Detecting emotions...")
    emotion_clf = EmotionClassifier(MODELS_DIR / "emotion-wav2vec2")
    emotions = emotion_clf.classify_segments(waveform, segments, sr)

    # Build output
    output_segments = []
    for i, (seg, label, emo) in enumerate(zip(segments, labels, emotions)):
        output_segments.append(
            {
                "start": round(seg["start"], 3),
                "end": round(seg["end"], 3),
                "speaker": int(label),
                "emotion": emo["emotion"],
                "confidence": round(emo["confidence"], 3),
            }
        )

    processing_time = time.time() - start_time
    log.info(f"Processing time: {processing_time:.2f}s")

    output = {
        "file": str(audio_path),
        "duration": round(duration, 3),
        "speakers": num_speakers,
        "segments": output_segments,
    }

    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
