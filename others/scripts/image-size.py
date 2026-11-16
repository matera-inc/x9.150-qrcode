#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
"""An image's size, the same on Docker's classic image store and on its containerd store.

    others/scripts/image-size.py IMAGE            # {"sizeBytes": N, "compressedSizeBytes": M}
    others/scripts/image-size.py IMAGE --field sizeBytes

Why not `docker image inspect --format '{{.Size}}'`: its meaning depends on the store. On the
classic store (overlay2) it is the unpacked layers. On the containerd store (the default of
Docker Desktop and of new Docker Engine installs) it is the content size (the compressed blobs) on
some versions and the disk usage (compressed blobs plus unpacked snapshots, several times the
image) on others. A script that reads it reports a different number for the same image depending on
the machine.

This reads what `docker save` writes, which is the image itself on both stores, and counts:

  sizeBytes            the layers unpacked: the sum of each layer's tar, decompressed if its blob
                       is gzip-compressed. What the classic store's {{.Size}} reported.
  compressedSizeBytes  the layers compressed: a gzip blob as it is, an uncompressed one gzipped
                       (level 6, as `docker push` does). Close to what a registry stores and a
                       node pulls.

The config and manifests (a few kilobytes) are not counted in either. A zstd-compressed layer is
refused, naming it: Python's standard library cannot decompress it. Standard library only.
"""

import argparse
import gzip
import json
import subprocess
import sys
import tarfile
import tempfile
import zlib

CHUNK = 1 << 20


def layer_sizes(blob):
    """(unpacked, compressed) bytes of one layer blob, a binary file object."""
    head = blob.read(4)
    blob.seek(0)
    if head[:2] == b"\x1f\x8b":
        compressed = unpacked = 0
        d = zlib.decompressobj(zlib.MAX_WBITS | 16)
        while chunk := blob.read(CHUNK):
            compressed += len(chunk)
            unpacked += len(d.decompress(chunk))
        return unpacked + len(d.flush()), compressed
    if head == b"\x28\xb5\x2f\xfd":
        raise SystemExit("image-size: a zstd-compressed layer; this script reads gzip and plain layers only")
    unpacked = 0
    c = zlib.compressobj(6, zlib.DEFLATED, zlib.MAX_WBITS | 16)
    compressed = 0
    while chunk := blob.read(CHUNK):
        unpacked += len(chunk)
        compressed += len(c.compress(chunk))
    return unpacked, compressed + len(c.flush())


def sizes(archive):
    """Sizes of the one image in a `docker save` archive."""
    with tarfile.open(archive) as tar:
        manifest = json.load(tar.extractfile("manifest.json"))
        if len(manifest) != 1:
            raise SystemExit(f"image-size: the archive holds {len(manifest)} images, expected 1")
        unpacked = compressed = 0
        for layer in manifest[0]["Layers"]:
            u, c = layer_sizes(tar.extractfile(layer))
            unpacked += u
            compressed += c
    return {"sizeBytes": unpacked, "compressedSizeBytes": compressed}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("image", help="an image in the local Docker, or a `docker save` archive with --archive")
    parser.add_argument("--archive", action="store_true", help="IMAGE is a file written by `docker save`")
    parser.add_argument("--field", choices=["sizeBytes", "compressedSizeBytes"], help="print only this number")
    args = parser.parse_args()
    if args.archive:
        result = sizes(args.image)
    else:
        with tempfile.NamedTemporaryFile(suffix=".tar") as tmp:
            subprocess.run(["docker", "save", "-o", tmp.name, args.image], check=True)
            result = sizes(tmp.name)
    print(result[args.field] if args.field else json.dumps(result))


if __name__ == "__main__":
    sys.exit(main())
