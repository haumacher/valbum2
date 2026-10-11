#!/usr/bin/env python3
"""Restores the order of the placeholders of every key of an ARB file to the committed one.

`flutter gen-l10n` takes the parameters of a generated method in the order of the key's
`placeholders` map, and the translation plugin reorders that map on some keys on every run,
which flips the signature of the generated method while the code still calls it in the
committed order. Run this after `translateArb` and before `flutter gen-l10n`:

    python3 valbum_ui/tool/arb_placeholder_order.py valbum_ui/lib/l10n/app_en.arb

A key the committed file does not have (a new key) is left as it is.
"""
import json
import subprocess
import sys
from collections import OrderedDict


def load(text):
    return json.loads(text, object_pairs_hook=OrderedDict)


def committed(path):
    text = subprocess.run(["git", "show", "HEAD:" + path], check=True, capture_output=True, text=True).stdout
    return load(text)


def restore(path):
    with open(path, encoding="utf-8") as handle:
        arb = load(handle.read())
    reference = committed(path)
    changed = []
    for key, meta in arb.items():
        if not key.startswith("@") or not isinstance(meta, dict):
            continue
        placeholders = meta.get("placeholders")
        before = reference.get(key, {}).get("placeholders") if isinstance(reference.get(key), dict) else None
        if not placeholders or not before:
            continue
        order = [name for name in before if name in placeholders] + [name for name in placeholders if name not in before]
        if list(placeholders) != order:
            meta["placeholders"] = OrderedDict((name, placeholders[name]) for name in order)
            changed.append(key[1:])
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(arb, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return changed


if __name__ == "__main__":
    for path in sys.argv[1:]:
        changed = restore(path)
        print(f"{path}: restored {', '.join(changed) if changed else 'nothing'}")
