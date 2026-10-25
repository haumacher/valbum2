#
# Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
#
"""The service's vectors against the reference's (golden.json), see issue #257.

A wrong resize, a missing end-of-text token or a forgotten re-normalisation would otherwise only
show as "search is somehow mediocre". Needs the model: set VALBUM_EMBEDDER_MODEL_DIR to a
directory holding the files of model.json (the server's download, or one made by hand), and
VALBUM_EMBEDDER_PRECISION to int8 to check the quantised files against their own, lower bar.
Skipped without it, so that the test runs anywhere.
"""

import base64
import os
import sys

import numpy as np
import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))

import embedder  # noqa: E402
import inputs  # noqa: E402

MODEL_DIR = os.environ.get("VALBUM_EMBEDDER_MODEL_DIR")
PRECISION = os.environ.get("VALBUM_EMBEDDER_PRECISION", "fp32")

# The bar of the issue for the full precision; the quantised model is measured against it and
# reported, its own bar is what the model card states for q8 (0.9997 worst case, at 768 d) less
# the margin truncation costs.
BAR = {"fp32": 0.999, "int8": 0.99}[PRECISION]

pytestmark = pytest.mark.skipif(not MODEL_DIR, reason="VALBUM_EMBEDDER_MODEL_DIR is not set")


@pytest.fixture(scope="module")
def golden():
    import json

    with open(os.path.join(HERE, "golden.json"), encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture(scope="module")
def model():
    return embedder.Model(MODEL_DIR, PRECISION, 0, embedder.read_manifest())


def vector(text):
    return np.frombuffer(base64.b64decode(text), dtype="<f4")


def test_the_inputs_are_those_of_the_golden_vectors(golden):
    names = [t for t in inputs.TEXTS] + [name for name, _ in inputs.images()]
    assert names == [item["input"] for item in golden["items"]]
    assert len(inputs.TEXTS) >= 20 and len(names) - len(inputs.TEXTS) >= 20


def test_every_vector_matches_the_reference(golden, model):
    images = dict(inputs.images())
    dimension = golden["dimension"]
    worst = (2.0, None)
    report = []
    for item in golden["items"]:
        if item["kind"] == "text":
            raw, _ = model.embed_text(item["input"])
        else:
            raw, _ = model.embed_image(embedder.decode_image(images[item["input"]]))
        mine = embedder.truncate(raw, dimension)
        cosine = float(np.dot(mine, vector(item["vector"])))
        report.append("%.6f %s" % (cosine, item["input"][:60]))
        worst = min(worst, (cosine, item["input"]))
    print("\n".join(report))
    print("worst cosine (%s): %.6f for %s" % (PRECISION, worst[0], worst[1]))
    assert worst[0] > BAR, "cosine %.6f for %s" % worst


def test_batched_and_parallel_runs_match_the_reference(golden, model):
    """Issue #311: a request's inputs embedded in batches (padded, masked) and several requests on
    the shared session at once give the vectors of the reference, as single runs do."""
    import threading

    images = dict(inputs.images())
    items = golden["items"]
    dimension = golden["dimension"]
    texts = [item for item in items if item["kind"] == "text"]
    pictures = [item for item in items if item["kind"] != "text"]
    found = {}

    def embed(kind, group):
        prepared = [model.prepare("text", item["input"]) if kind == "text"
                    else model.prepare("image", embedder.decode_image(images[item["input"]])) for item in group]
        for item, (raw, _) in zip(group, model.run(kind, prepared)):
            found[item["input"]] = embedder.truncate(raw, dimension)

    groups = [("text", texts[n:n + embedder.MAX_BATCH]) for n in range(0, len(texts), embedder.MAX_BATCH)]
    groups += [("image", pictures[n:n + embedder.MAX_BATCH]) for n in range(0, len(pictures), embedder.MAX_BATCH)]
    # Four runs at once on the one session, as four slots of the service run them.
    pending = list(groups)
    lock = threading.Lock()

    def work():
        while True:
            with lock:
                if not pending:
                    return
                kind, group = pending.pop()
            embed(kind, group)

    threads = [threading.Thread(target=work) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    worst = min((float(np.dot(found[item["input"]], vector(item["vector"]))), item["input"]) for item in items)
    print("worst cosine of batched and parallel runs (%s): %.6f for %s" % (PRECISION, worst[0], worst[1]))
    assert len(found) == len(items)
    assert worst[0] > BAR, "cosine %.6f for %s" % worst
