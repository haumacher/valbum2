#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""Writes golden.json: the vectors of the reference implementation for the inputs of inputs.py.

The reference is what the model card names: sentence-transformers on the original weights of
google/embeddinggemma-2, in float32 on the CPU, the audio encoder left out. The service's
test (test_golden.py) holds the ONNX route to these vectors, cosine > 0.999 per item, see #257.

Run in a scratch environment of its own, never the service's (it needs PyTorch):

    python3 -m venv /tmp/ref && /tmp/ref/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
    /tmp/ref/bin/pip install "sentence-transformers==6.1.0" "transformers==5.19.0" torchvision pillow
    /tmp/ref/bin/hf download google/embeddinggemma-2 --revision <REVISION> --local-dir /tmp/eg2
    /tmp/ref/bin/python -I make_golden.py /tmp/eg2 golden.json

Images are decoded as the service decodes them (embedder.decode_image) and handed to the
reference as PIL images, so the comparison is about the processor and the model, not the JPEG
decoder.
"""

import base64
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import inputs  # noqa: E402
from embedder import decode_image  # noqa: E402

# The revision of google/embeddinggemma-2 the vectors were made with.
REVISION = "914f7f89142e33e77833254d9c9b90c3cef7303b"

DIMENSION = 256


def main(model_dir, out):
    import numpy as np
    import sentence_transformers
    import torch
    import transformers
    from sentence_transformers import SentenceTransformer

    torch.manual_seed(0)
    model = SentenceTransformer(model_dir, device="cpu", config_kwargs={"audio_config": None},
                                model_kwargs={"torch_dtype": torch.float32})

    def vector(v):
        v = np.asarray(v, dtype=np.float32)[:DIMENSION]
        v = v / np.linalg.norm(v)
        return base64.b64encode(v.astype("<f4").tobytes()).decode("ascii")

    items = []
    for text in inputs.TEXTS:
        v = model.encode([text], normalize_embeddings=True, convert_to_numpy=True)[0]
        items.append({"kind": "text", "input": text, "vector": vector(v)})
    for name, data in inputs.images():
        v = model.encode([{"image": decode_image(data)}], normalize_embeddings=True, convert_to_numpy=True)[0]
        items.append({"kind": "image", "input": name, "vector": vector(v)})

    golden = {
        "model": "google/embeddinggemma-2",
        "revision": REVISION,
        "reference": "sentence-transformers %s, transformers %s, torch %s, float32, CPU"
                     % (sentence_transformers.__version__, transformers.__version__, torch.__version__),
        "dimension": DIMENSION,
        "encoding": "base64 of little-endian float32, L2-normalised after truncation",
        "items": items,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(golden, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote %d vectors to %s" % (len(items), out))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
