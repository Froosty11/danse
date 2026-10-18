#!/usr/bin/env python3
"""Numbers and pictures side by side: what Danse's importer made of a gesture (poses.txt from
GestureFramesClientTest) and its screenshots, against Blockbench 5's pose of the same frames.

  python3 tools/bbpose/compare.py numbers POSES_TXT BLUEPRINT [BLUEPRINT ...]
  python3 tools/bbpose/compare.py sheet SCREENSHOT_DIR OUT_DIR BLUEPRINT [BLUEPRINT ...]

"numbers" prints, per gesture, the largest pivot distance (model pixels) and bone rotation
difference (degrees) between Danse's frames and Blockbench's. "sheet" writes one picture per
gesture: a row per photographed moment with Blockbench's pose, Danse's stand-in on Blockbench's
pose and the game's screenshot, from the front and from the right.
"""
import glob
import math
import os
import re
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bbpose  # noqa: E402
import render  # noqa: E402

ROT_Y_PI = bbpose.mat_rot_y(math.pi)


def blueprints(paths):
    found = {}
    for p in paths:
        bp = bbpose.Blueprint(p)
        for name in bp.animations:
            found.setdefault(name, bp)
    return found


def read_poses(path):
    poses = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) != 19:
            continue
        gesture, frame, bone = parts[0], int(parts[1]), parts[2]
        v = [float(x) for x in parts[3:]]
        m = [[v[c * 4 + r] for c in range(4)] for r in range(4)]   # column-major in the file
        # undo BIL's root half turn, blocks -> pixels
        m = bbpose.mat_mul(ROT_Y_PI, m)
        for r in range(3):
            m[r][3] *= 16
        poses[(gesture, frame, bone)] = m
    return poses


def angle_between(a, b):
    """Rotation angle (degrees) between the orientation parts of two matrices, scale removed."""
    def unit_cols(m):
        cols = []
        for c in range(3):
            col = [m[r][c] for r in range(3)]
            n = math.sqrt(sum(x * x for x in col)) or 1
            cols.append([x / n for x in col])
        return cols
    ca, cb = unit_cols(a), unit_cols(b)
    trace = sum(sum(ca[c][r] * cb[c][r] for r in range(3)) for c in range(3))
    return math.degrees(math.acos(max(-1.0, min(1.0, (trace - 1) / 2))))


def numbers(poses_path, bp_paths):
    bps = blueprints(bp_paths)
    poses = read_poses(poses_path)
    for gesture in sorted({k[0] for k in poses}):
        bp = bps.get(gesture)
        if bp is None:
            print("%s: no blueprint has it" % gesture)
            continue
        worst_p = worst_r = (0.0, None)
        for (g, frame, bone), m in poses.items():
            if g != gesture:
                continue
            truth = bbpose.world_matrices(bp, gesture, frame / 20, "bb")[bone]
            dp = math.dist([m[r][3] for r in range(3)], [truth[r][3] for r in range(3)])
            dr = angle_between(m, truth)
            if dp > worst_p[0]:
                worst_p = (dp, (frame / 20, bone))
            if dr > worst_r[0]:
                worst_r = (dr, (frame / 20, bone))
        print("%-18s pivots within %.3f px (worst %s), rotations within %.2f deg (worst %s)"
              % (gesture, worst_p[0], worst_p[1], worst_r[0], worst_r[1]))


def game_crop(path):
    """The figure out of a 1920x1080 frame of Danse's gesture camera, scaled to a panel."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    # vertical FOV 50 at ~3.5 blocks: ~330 px per block; the camera sits ~0.8 blocks above the feet
    half = int(h * 0.5)
    cx, cy = w // 2, int(h * 0.44)
    box = (cx - half * render.SIZE[0] // render.SIZE[1], cy - half, cx + half * render.SIZE[0] // render.SIZE[1], cy + half)
    return img.crop(box).resize(render.SIZE)


def label(img, text):
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, img.size[0], 14), fill=(245, 245, 245))
    d.text((6, 2), text, fill=(30, 30, 30))
    return img


def sheet(shots, out_dir, bp_paths):
    bps = blueprints(bp_paths)
    os.makedirs(out_dir, exist_ok=True)
    by_gesture = {}
    for f in glob.glob(os.path.join(shots, "*.png")):
        m = re.search(r"^(?:\d+_)?([a-z_]+?)_t(\d\d\.\d\d)_(front|side)\.png$", os.path.basename(f))
        if m:
            by_gesture.setdefault(m.group(1), {})[(float(m.group(2)), m.group(3))] = f
    written = []
    for gesture, files in sorted(by_gesture.items()):
        bp = bps.get(gesture)
        if bp is None:
            continue
        times = sorted({t for t, _ in files})
        cols = 6
        pw, ph = render.SIZE
        img = Image.new("RGB", (pw * cols, ph * len(times) + 24), "white")
        ImageDraw.Draw(img).text((6, 6), "%s: Blockbench 5 | stand-in on Blockbench's pose | in game, "
                                          "front (left three) and right side (right three)" % gesture, fill=(0, 0, 0))
        for row, t in enumerate(times):
            r = render.render(bp, gesture, t)
            panels = [r[("truth", "front")], r[("standin", "front")],
                      label(game_crop(files[(t, "front")]), "game %s t=%.2f front" % (gesture, t)) if (t, "front") in files else Image.new("RGB", render.SIZE, "grey"),
                      r[("truth", "side")], r[("standin", "side")],
                      label(game_crop(files[(t, "side")]), "game %s t=%.2f side" % (gesture, t)) if (t, "side") in files else Image.new("RGB", render.SIZE, "grey")]
            for col, p in enumerate(panels):
                img.paste(p, (col * pw, 24 + row * ph))
        path = os.path.join(out_dir, "%s.png" % gesture)
        img.save(path)
        written.append(path)
    print("\n".join(written))


def main(argv):
    if len(argv) >= 3 and argv[0] == "numbers":
        numbers(argv[1], argv[2:])
        return 0
    if len(argv) >= 4 and argv[0] == "sheet":
        sheet(argv[1], argv[2], argv[3:])
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
