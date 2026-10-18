#!/usr/bin/env python3
"""Orthographic box drawings of a gesture at time t: the blueprint's own cubes posed as Blockbench 5
poses them (ground truth), and Danse's stand-in parts on the same bones.

  python3 tools/bbpose/render.py BLUEPRINT ANIMATION 0,0.5,1,2,4 OUT_DIR [--mode bb|bil5|bilold]

writes OUT_DIR/<animation>_t<time>_{truth,standin}_{front,side}.png. "front" looks at the figure's
face (its right arm on the left of the picture), "side" at its right side (its face to the right):
the two views GestureFramesClientTest photographs in game. Needs Pillow.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bbpose  # noqa: E402

COLOURS = {
    "head": (232, 190, 80),
    "body": (70, 140, 200),
    "arm_r": (220, 60, 60),
    "arm_l": (60, 170, 80),
    "leg_r": (235, 130, 40),
    "leg_l": (140, 90, 190),
}
# Danse's stand-in, from PartTransforms (display translation in pixels, scale) and the per-pixel
# model's box (4..12, 0..8, 4..12); an item display adds a half turn about Y.
STANDIN = {
    "head": ((0, 5.90, 0), (0.9375, 0.9375, 0.9375)),
    "body": ((0, 8.50, 0), (0.9375, 1.40625, 0.46875)),
    "arm_r": ((0.35, 0.5, 0), (0.46875, 1.40625, 0.46875)),
    "arm_l": ((-0.35, 0.5, 0), (0.46875, 1.40625, 0.46875)),
    "leg_r": ((0.125, -1.74, 0), (0.46875, 1.40625, 0.46875)),
    "leg_l": ((-0.125, -1.74, 0), (0.46875, 1.40625, 0.46875)),
}

SIZE = (260, 340)          # panel, pixels
PX = 6.5                   # picture pixels per model pixel
CENTRE_Y = 15              # model height (pixels) at the panel's centre


def standin_box(part):
    (tx, ty, tz), (sx, sy, sz) = STANDIN[part]
    xs = (tx + sx * -4, tx + sx * 4)
    ys = (ty + sy * -8, ty + sy * 0)
    zs = (tz + sz * -4, tz + sz * 4)
    # half turn about Y: x -> -x, z -> -z
    return (-xs[1], ys[0], -zs[1]), (-xs[0], ys[1], -zs[0])


def boxes(bp, mats, kind):
    """[(part, 8 corners in model space)]"""
    out = []
    for part in bbpose.PARTS:
        bone = bp.bone(part)
        if bone is None or part not in mats:
            continue
        m = mats[part]
        if kind == "truth":
            local = [((f[0] - bone.origin[0], f[1] - bone.origin[1], f[2] - bone.origin[2]),
                      (t[0] - bone.origin[0], t[1] - bone.origin[1], t[2] - bone.origin[2])) for f, t in bone.cubes]
        else:
            local = [standin_box(part)]
        for lo, hi in local:
            corners = [bbpose.mat_apply(m, (x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
            out.append((part, corners))
    return out


FACES = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]


def draw(box_list, view, title):
    img = Image.new("RGB", SIZE, (245, 245, 245))
    d = ImageDraw.Draw(img)
    cx, cy = SIZE[0] / 2, SIZE[1] / 2

    def project(p):
        x, y, z = p
        if view == "front":    # camera at -Z looking +Z: screen x = -X, nearer = smaller Z
            return cx - x * PX, cy - (y - CENTRE_Y) * PX, -z
        return cx - z * PX, cy - (y - CENTRE_Y) * PX, x   # camera at +X: screen x = -Z, nearer = larger X

    # ground line
    gy = cy - (0 - CENTRE_Y) * PX
    d.line([(0, gy), (SIZE[0], gy)], fill=(190, 190, 190), width=1)

    polys = []
    for part, corners in box_list:
        pts = [project(c) for c in corners]
        for face in FACES:
            q = [pts[i] for i in face]
            depth = sum(p[2] for p in q) / 4
            # shade by how squarely the face looks at the camera
            (x0, y0, _), (x1, y1, _), (x2, y2, _) = q[0], q[1], q[2]
            area = abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0))
            polys.append((depth, part, [(p[0], p[1]) for p in q], area))
    polys.sort(key=lambda p: p[0])   # far first: smaller "nearness" first
    for depth, part, q, area in polys:
        base = COLOURS[part]
        k = 0.65 + 0.35 * min(1.0, area / (PX * PX * 16))
        fill = tuple(int(c * k) for c in base)
        d.polygon(q, fill=fill, outline=tuple(int(c * 0.45) for c in base))
    d.text((6, 4), title, fill=(30, 30, 30))
    return img


def render(bp, anim, t, mode="bb"):
    mats = bbpose.world_matrices(bp, anim, t, mode)
    out = {}
    for kind in ("truth", "standin"):
        b = boxes(bp, mats, kind)
        for view in ("front", "side"):
            label = "%s %s t=%.2f %s" % ("Blockbench" if kind == "truth" else "stand-in", anim, t, view)
            out[(kind, view)] = draw(b, view, label)
    return out


def clip_times(bp, anim, times):
    length = float(bp.animations[anim]["length"])
    res = []
    for t in times:
        t = min(t, length)
        t = round(t * 20) / 20
        if t not in res:
            res.append(t)
    return res


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    path, anim, times, out = argv[0], argv[1], [float(t) for t in argv[2].split(",")], argv[3]
    mode = argv[argv.index("--mode") + 1] if "--mode" in argv else "bb"
    bp = bbpose.Blueprint(path)
    os.makedirs(out, exist_ok=True)
    for t in clip_times(bp, anim, times):
        for (kind, view), img in render(bp, anim, t, mode).items():
            img.save(os.path.join(out, "%s_t%05.2f_%s_%s.png" % (anim, t, kind, view)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
