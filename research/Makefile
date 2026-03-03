.PHONY: deps models clean test tflite

# Use Python 3.13 for compatibility with onnxruntime
PYTHON3 := python3.13
VENV := .venv
PIP := $(VENV)/bin/pip
PYTHON := $(VENV)/bin/python

# Create venv and install all dependencies
deps: $(VENV)/bin/activate

$(VENV)/bin/activate: requirements.txt
	$(PYTHON3) -m venv $(VENV)
	$(PIP) install --upgrade pip
	$(PIP) install -r requirements.txt
	touch $(VENV)/bin/activate

# Download all models to ./models/
models: deps models/silero_vad.onnx models/ecapa-tdnn models/emotion-wav2vec2

models/silero_vad.onnx:
	mkdir -p models
	curl -L -o models/silero_vad.onnx \
		https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx

models/ecapa-tdnn: deps
	mkdir -p models/ecapa-tdnn
	$(PYTHON) -c "from speechbrain.inference.speaker import EncoderClassifier; \
		EncoderClassifier.from_hparams(source='speechbrain/spkrec-ecapa-voxceleb', savedir='models/ecapa-tdnn')"

models/emotion-wav2vec2: deps
	mkdir -p models/emotion-wav2vec2
	$(PYTHON) -c "from speechbrain.inference.interfaces import foreign_class; \
		foreign_class(source='speechbrain/emotion-recognition-wav2vec2-IEMOCAP', \
		savedir='models/emotion-wav2vec2', pymodule_file='custom_interface.py', \
		classname='CustomEncoderWav2vec2Classifier')"

# Convert ONNX to TFLite (optional)
tflite: models/silero_vad.tflite

models/silero_vad.tflite: models/silero_vad.onnx deps
	$(PYTHON) -c "\
import onnx; \
from onnx_tf.backend import prepare; \
import tensorflow as tf; \
onnx_model = onnx.load('models/silero_vad.onnx'); \
tf_rep = prepare(onnx_model); \
tf_rep.export_graph('models/silero_vad_tf'); \
converter = tf.lite.TFLiteConverter.from_saved_model('models/silero_vad_tf'); \
open('models/silero_vad.tflite', 'wb').write(converter.convert())"
	rm -rf models/silero_vad_tf

# Run test
test: deps
	$(PYTHON) diarize.py tests/samples/test.wav

# Clean all generated files
clean:
	rm -rf models/ $(VENV)/
