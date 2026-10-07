#!/usr/bin/env python3
"""Create the disposable world the Caldera screenshot gate needs.

`GraphGpuSmoke.setupDemoScene` refuses to run its demo scene unless the
singleplayer world's display name is exactly "Caldera QA", because the demo
scene issues commands (time, weather, gamemode, teleport) and must therefore
only ever run against a throwaway world.

Copying an existing world rather than generating a fresh one is the point: the
terrain, the player's position and the inventory are then identical across the
before/after runs, which is what makes the screenshots comparable at all. The
player's own saved state is deliberately *not* carried over deterministically —
pass the same `--username` to the client (see `caldera.clientUsername` in
build.gradle) and delete the gate user's entry from `players/data/`, which this
script does for you by re-copying from the source.

The directory name has no space, because it is handed to quick-play on the
command line; the *display* name inside level.dat has one, because that is the
string the smoke harness compares against.

Usage:
    python scripts/make-qa-world.py [source-world] [destination-directory]

Defaults: source "新的世界", destination "CalderaQA", both under run/saves.
"""
import gzip
import os
import shutil
import sys

SAVES = os.path.join("run", "saves")
MARKER = b"\x00\x09LevelName"
DISPLAY_NAME = "Caldera QA"


def patch_level_name(path):
    """Rewrite Data.LevelName in a level.dat, in place.

    level.dat is gzipped NBT. A TAG_String stores its byte length only in its own
    two-byte prefix, and NBT containers record element counts rather than byte
    lengths, so splicing a different-length name in does not need any offsets
    fixed up.
    """
    if not os.path.exists(path):
        print(f"  skipped (absent): {path}")
        return
    with gzip.open(path, "rb") as handle:
        raw = handle.read()
    index = raw.find(MARKER)
    if index < 0:
        raise SystemExit(f"no LevelName tag in {path}")
    length_at = index + len(MARKER)
    old_length = int.from_bytes(raw[length_at:length_at + 2], "big")
    new_value = DISPLAY_NAME.encode("utf-8")
    patched = (
        raw[:length_at]
        + len(new_value).to_bytes(2, "big")
        + new_value
        + raw[length_at + 2 + old_length:]
    )
    with gzip.open(path, "wb") as handle:
        handle.write(patched)
    print(f"  {os.path.basename(path)}: name {old_length}B -> {len(new_value)}B")


def main(argv):
    source = os.path.join(SAVES, argv[1] if len(argv) > 1 else "\u65b0\u7684\u4e16\u754c")
    destination = os.path.join(SAVES, argv[2] if len(argv) > 2 else "CalderaQA")

    if not os.path.isdir(source):
        raise SystemExit(f"source world not found: {source}")
    if os.path.exists(destination):
        shutil.rmtree(destination)
        print(f"removed stale {destination}")
    shutil.copytree(source, destination)
    print(f"copied {source} -> {destination}")

    print("patching level name:")
    for name in ("level.dat", "level.dat_old"):
        patch_level_name(os.path.join(destination, name))

    # Verify rather than assume: the gate silently produces incomparable
    # screenshots if the display name is wrong, and the harness just kills the
    # client with CALDERA_WORLD_SMOKE_FAIL long after the cause.
    with gzip.open(os.path.join(destination, "level.dat"), "rb") as handle:
        raw = handle.read()
    index = raw.find(MARKER)
    length_at = index + len(MARKER)
    length = int.from_bytes(raw[length_at:length_at + 2], "big")
    value = raw[length_at + 2:length_at + 2 + length].decode("utf-8")
    if value != DISPLAY_NAME:
        raise SystemExit(f"level name is {value!r}, expected {DISPLAY_NAME!r}")

    gate_user = os.path.join(destination, "players", "data")
    if os.path.isdir(gate_user):
        print(f"players/data entries carried over: {len(os.listdir(gate_user))}")
    print(f"ok: display name {value!r}")


if __name__ == "__main__":
    main(sys.argv)
