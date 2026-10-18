#!/usr/bin/env python3
"""Where each bone of an Animated Java 1.11 / Blockbench 5 blueprint is at time t, exactly as
Blockbench draws it, and what Danse's player loader makes of the same keyframes.

Ground truth ("bb") follows Blockbench 5.2 and Animated Java 1.11 line by line:
  - js/animations/timeline_animators.js  BoneAnimator.interpolate / displayRotation / displayPosition /
    displayScale: rotation keyframes are *added* to the bone's Euler angles (Blockbench 5 dropped the
    old -x/-y flip; bbmodel.js negates rotation x/y and position x when it opens a pre-5.0 file),
    position is added to the bone's offset from its parent, scale multiplies.
  - js/animations/keyframe.js  getLerp / getCatmullromLerp (THREE.SplineCurve, parameter space) /
    getBezierLerp; which interpolation runs is decided by *both* neighbours: step if the earlier
    keyframe is step, Catmull-Rom if either is, linear only if the earlier is linear and the later
    linear or step.
  - Euler order: Format.euler_order, default 'ZYX' (the Animated Java format does not change it), so
    R = Rz * Ry * Rx. Bones are THREE.Object3D: local = T(origin - parent.origin + pos) * R * S.
  - Animated Java 1.11 src/mods/keyframeEasing.ts: easing applies on the linear path only, with the
    later keyframe's easing; src/mods/boneAnimatorMod.ts samples on 20 Hz ticks and lerps between
    them (so at multiples of 0.05 s it is exactly Blockbench's value).

The "bil5" and "bilold" modes reproduce blockbench-import-library 2.2.0's Sampler and poses()
(BbModel5Importer / BbModelImporter) so the importer can be checked against the truth without a
game: interpolation chosen by the later keyframe alone, easing applied to every interpolation, and
for 1.7.3 files rotation multiplied by (-1, -1, 1) and position x by -1.

Everything is returned in Blockbench's frame, in pixels (BIL's root rotateY(pi) and /16 undone).

  python3 tools/bbpose/bbpose.py dump BLUEPRINT ANIMATION 0,0.5,1 [--mode bb|bil5|bilold]
"""
import json
import math
import sys

PARTS = ("head", "body", "arm_r", "arm_l", "leg_r", "leg_l")

# ---------------------------------------------------------------- 4x4 matrices (row-major lists)


def mat_identity():
    return [[1.0 if i == j else 0.0 for j in range(4)] for i in range(4)]


def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def mat_translate(x, y, z):
    m = mat_identity()
    m[0][3], m[1][3], m[2][3] = x, y, z
    return m


def mat_scale(x, y, z):
    m = mat_identity()
    m[0][0], m[1][1], m[2][2] = x, y, z
    return m


def mat_rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]]


def mat_rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]]


def mat_rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]


def euler_zyx_deg(x, y, z):
    """THREE.Euler(x, y, z, 'ZYX') == JOML Quaternionf().rotateZ(z).rotateY(y).rotateX(x)."""
    r = math.radians
    return mat_mul(mat_rot_z(r(z)), mat_mul(mat_rot_y(r(y)), mat_rot_x(r(x))))


def mat_apply(m, p):
    x, y, z = p
    return tuple(m[i][0] * x + m[i][1] * y + m[i][2] * z + m[i][3] for i in range(3))


# ---------------------------------------------------------------- blueprint


class Keyframe:
    def __init__(self, raw):
        self.channel = raw["channel"]
        self.time = float(raw["time"])
        self.interpolation = raw.get("interpolation", "linear")
        self.easing = raw.get("easing") or "linear"
        self.easing_args = raw.get("easingArgs") or []
        self.points = [tuple(_num(dp.get(a, 0)) for a in "xyz") for dp in raw["data_points"]]
        self.bezier_left_time = raw.get("bezier_left_time", [-0.1] * 3)
        self.bezier_left_value = raw.get("bezier_left_value", [0] * 3)
        self.bezier_right_time = raw.get("bezier_right_time", [0.1] * 3)
        self.bezier_right_value = raw.get("bezier_right_value", [0] * 3)

    def calc(self, axis, dp=0):
        dp = max(0, min(dp, len(self.points) - 1)) if dp else 0
        return self.points[dp][axis]


def _num(v):
    if isinstance(v, (int, float)):
        return float(v)
    v = str(v).strip()
    if v in ("", "-", "+", ".", ","):
        return 0.0
    try:
        return float(v)
    except ValueError:
        raise SystemExit("molang expression in keyframe, not supported here: %r" % v)


class Bone:
    def __init__(self, uuid, name, origin, rotation, parent):
        self.uuid, self.name, self.parent = uuid, name, parent
        self.origin = tuple(float(c) for c in origin)
        self.rotation = tuple(float(c) for c in (rotation or (0, 0, 0)))
        self.cubes = []   # (from, to) in absolute model pixels


class Blueprint:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            raw = json.load(f)
        self.raw = raw
        self.format_version = raw["meta"].get("format_version", "")
        self.bones = {}
        elements = {e["uuid"]: e for e in raw["elements"]}
        groups = {g["uuid"]: g for g in raw.get("groups", [])}

        def walk(entries, parent):
            for c in entries:
                if isinstance(c, str):
                    e = elements.get(c)
                    if e and parent and e.get("type", "cube") == "cube" and "from" in e:
                        inf = float(e.get("inflate", 0) or 0)
                        fr = tuple(float(v) - inf for v in e["from"])
                        to = tuple(float(v) + inf for v in e["to"])
                        if e.get("rotation") and any(float(v) for v in e["rotation"]):
                            print("warning: rotated cube %s drawn unrotated" % e["name"], file=sys.stderr)
                        parent.cubes.append((fr, to))
                    continue
                g = groups.get(c["uuid"], c)   # Blockbench 5: data in "groups"; 1.7.3: inline
                bone = Bone(c["uuid"], g["name"], g.get("origin", (0, 0, 0)), g.get("rotation"), parent)
                self.bones[bone.uuid] = bone
                walk(c.get("children", []), bone)

        walk(raw["outliner"], None)
        self.animations = {a["name"]: a for a in raw.get("animations", [])}

    def bone(self, name):
        for b in self.bones.values():
            if b.name == name:
                return b
        return None

    def keyframes(self, anim, bone_uuid, channel):
        an = (anim.get("animators") or {}).get(bone_uuid)
        if not an:
            return []
        return [Keyframe(k) for k in an.get("keyframes", []) if k["channel"] == channel]


# ---------------------------------------------------------------- easing (Animated Java util/easing.ts)


def _ease(name, args, t):
    if name == "linear":
        return t
    arg = args[0] if args else 1

    def back(s):
        return lambda x: x * x * ((s + 1) * x - s)

    def out(f):
        return lambda x: 1 - f(1 - x)

    def inout(f):
        return lambda x: f(x * 2) / 2 if x < 0.5 else 1 - f((1 - x) * 2) / 2

    poly = {"Quad": lambda x: x * x, "Cubic": lambda x: x ** 3, "Quart": lambda x: x ** 4,
            "Quint": lambda x: x ** 5, "Sine": lambda x: 1 - math.cos(x * math.pi / 2),
            "Expo": lambda x: 2 ** (10 * (x - 1)), "Circ": lambda x: 1 - math.sqrt(1 - x * x),
            "Back": back(1.70158 * arg)}
    for kind, f in poly.items():
        if name == "easeIn" + kind:
            return f(t)
        if name == "easeOut" + kind:
            return out(f)(t)
        if name == "easeInOut" + kind:
            return inout(f)(t)
    print("warning: easing %s treated as linear" % name, file=sys.stderr)
    return t


# ---------------------------------------------------------------- Blockbench 5 interpolation


def _catmull(t, p0, p1, p2, p3):
    v0 = (p2 - p0) * 0.5
    v1 = (p3 - p1) * 0.5
    t2 = t * t
    t3 = t * t2
    return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1


def _spline_y(points, t):
    """THREE.SplineCurve(points).getPoint(t).y"""
    p = (len(points) - 1) * t
    i = int(math.floor(p))
    w = p - i
    n = len(points)
    p0 = points[i if i == 0 else i - 1]
    p1 = points[i]
    p2 = points[n - 1 if i > n - 2 else i + 1]
    p3 = points[n - 1 if i > n - 3 else i + 2]
    return _catmull(w, p0[1], p1[1], p2[1], p3[1])


def _bezier_lerp(before, after, axis, alpha):
    vb, va = before.calc(axis, 1), after.calc(axis, 0)
    gap = after.time - before.time
    hb = min(max(before.bezier_right_time[axis] or 0, 0), gap)
    ha = min(max(after.bezier_left_time[axis] or 0, -gap), 0)
    pts = [(before.time, vb), (before.time + hb, vb + (before.bezier_right_value[axis] or 0)),
           (after.time + ha, va + (after.bezier_left_value[axis] or 0)), (after.time, va)]
    time = before.time + gap * alpha
    curve = []
    for k in range(201):
        s = k / 200
        x = sum(c * pt[0] for c, pt in zip(((1 - s) ** 3, 3 * (1 - s) ** 2 * s, 3 * (1 - s) * s * s, s ** 3), pts))
        y = sum(c * pt[1] for c, pt in zip(((1 - s) ** 3, 3 * (1 - s) ** 2 * s, 3 * (1 - s) * s * s, s ** 3), pts))
        curve.append((x, y))
    curve.sort(key=lambda q: abs(q[0] - time))
    (x1, y1), (x2, y2) = curve[0], curve[1]
    f = 0 if x2 == x1 else min(max((time - x1) / (x2 - x1), 0), 1)
    return y1 + (y2 - y1) * f


def bb_interpolate(kfs, time, loop_mode):
    """BoneAnimator.interpolate (Format.animation_loop_wrapping and quaternion interpolation are off
    in the Animated Java format)."""
    if not kfs:
        return None
    eps = 1 / 1200
    before = after = None
    for kf in kfs:
        if kf.time < time:
            if before is None or kf.time > before.time:
                before = kf
        else:
            if after is None or kf.time < after.time:
                after = kf
    result = None
    if before is not None and abs(before.time - time) < eps:
        result = before
    elif after is not None and abs(after.time - time) < eps:
        result = after
    elif before is not None and before.interpolation == "step":
        result = before
    elif before is not None and after is None:
        result = before
    elif after is not None and before is None:
        result = after
    elif before is None and after is None:
        return None
    else:
        alpha = (time - before.time) / (after.time - before.time)
        if before.interpolation == "linear" and after.interpolation in ("linear", "step"):
            if after.easing != "linear":   # Animated Java's getLerp patch
                e = _ease(after.easing, after.easing_args, alpha)
                return tuple(before.calc(a) + (after.calc(a) - before.calc(a)) * e for a in range(3))
            bdp = 1 if len(before.points) > 1 else 0
            return tuple(before.calc(a, bdp) + (after.calc(a, 0) - before.calc(a, bdp)) * alpha for a in range(3))
        if before.interpolation == "catmullrom" or after.interpolation == "catmullrom":
            srt = sorted(kfs, key=lambda k: k.time)
            bi = srt.index(before)
            before_plus = srt[bi - 1] if bi - 1 >= 0 else None
            after_plus = srt[bi + 2] if bi + 2 < len(srt) else None
            if loop_mode == "loop" and len(srt) >= 3:
                before_plus = before_plus or srt[-2]
                after_plus = after_plus or srt[1]
            out = []
            for a in range(3):
                pts = []
                if before_plus is not None and len(before.points) == 1:
                    pts.append((before_plus.time, before_plus.calc(a, 1)))
                pts.append((before.time, before.calc(a, 1)))
                pts.append((after.time, after.calc(a, 0)))
                if after_plus is not None and len(after.points) == 1:
                    pts.append((after_plus.time, after_plus.calc(a, 0)))
                tt = (alpha + (1 if before_plus is not None else 0)) / (len(pts) - 1)
                out.append(_spline_y(pts, tt))
            return tuple(out)
        if before.interpolation == "bezier" or after.interpolation == "bezier":
            return tuple(_bezier_lerp(before, after, a, alpha) for a in range(3))
        return None
    dp = 0 if (result.time > time or abs(result.time - time) < eps) else len(result.points) - 1
    return tuple(result.calc(a, dp) for a in range(3))


# ---------------------------------------------------------------- blockbench-import-library 2.2.0 Sampler


def bil_interpolate(kfs, time):
    if not kfs:
        return None
    srt = sorted(kfs, key=lambda k: k.time)
    before = before_before = after = after_after = None
    it = iter(srt)
    for kf in it:
        if kf.time <= time:
            if before is None or kf.time > before.time:
                before_before = before
                before = kf
        else:
            after = kf
            after_after = next(it, None)
            break
    if before is None:
        return after.points[0] if after else None
    if after is None:
        return before.points[0]
    if before.time > 0 and before_before is None:
        before_before = before
    t = (time - before.time) / (after.time - before.time)
    if after.easing != "linear":
        t = _ease(after.easing, after.easing_args, t)
    kind = after.interpolation
    if kind == "linear":
        return tuple(before.points[0][a] + (after.points[0][a] - before.points[0][a]) * t for a in range(3))
    if kind == "step":
        return after.points[0] if t >= 1 else before.points[0]
    if kind == "catmullrom":
        arr = ([before_before] if before_before else []) + [before, after] + ([after_after] if after_after else [])
        f = (t + (1 if before_before else 0)) / (len(arr) - 1)
        factor = (len(arr) - 1) * f
        seg = int(math.floor(factor))
        p0 = arr[max(seg - 1, 0)].points[0]
        p1 = arr[seg].points[0]
        p2 = arr[min(seg + 1, len(arr) - 1)].points[0]
        p3 = arr[min(seg + 2, len(arr) - 1)].points[0]
        w = factor - seg
        return tuple(_catmull(w, p0[a], p1[a], p2[a], p3[a]) for a in range(3))
    raise SystemExit("bil cannot interpolate %s" % kind)


# ---------------------------------------------------------------- poses


def local_channels(bp, anim, bone, time, mode):
    pos = rot = None
    scale = None
    if anim is not None:
        get = (lambda ch: bb_interpolate(bp.keyframes(anim, bone.uuid, ch), time, anim.get("loop", "once"))) \
            if mode == "bb" else (lambda ch: bil_interpolate(bp.keyframes(anim, bone.uuid, ch), time))
        pos, rot, scale = get("position"), get("rotation"), get("scale")
    pos = pos or (0.0, 0.0, 0.0)
    rot = rot or (0.0, 0.0, 0.0)
    scale = scale or (1.0, 1.0, 1.0)
    if mode == "bilold":
        rot = (-rot[0], -rot[1], rot[2])
        pos = (-pos[0], pos[1], pos[2])
    return pos, rot, scale


def world_matrices(bp, anim_name, time, mode="bb"):
    """{bone name: 4x4 matrix} mapping the bone's local frame (pixels, origin at its pivot) to
    Blockbench model space (pixels)."""
    anim = bp.animations.get(anim_name) if anim_name else None
    out = {}
    for bone in bp.bones.values():
        chain = []
        b = bone
        while b is not None:
            chain.insert(0, b)
            b = b.parent
        m = mat_identity()
        for b in chain:
            pos, rot, scale = local_channels(bp, anim, b, time, mode)
            off = [b.origin[i] - (b.parent.origin[i] if b.parent else 0) + pos[i] for i in range(3)]
            r = euler_zyx_deg(b.rotation[0] + rot[0], b.rotation[1] + rot[1], b.rotation[2] + rot[2])
            m = mat_mul(m, mat_mul(mat_translate(*off), mat_mul(r, mat_scale(*scale))))
        out[bone.name] = m
    return out


def euler_of(m):
    """ZYX Euler angles (degrees) of the rotation part, for printing."""
    sy = -m[2][0]
    y = math.asin(max(-1, min(1, sy)))
    if abs(sy) < 0.9999:
        x = math.atan2(m[2][1], m[2][2])
        z = math.atan2(m[1][0], m[0][0])
    else:
        x = math.atan2(-m[1][2], m[1][1])
        z = 0
    return tuple(math.degrees(v) for v in (x, y, z))


def main(argv):
    if len(argv) < 4 or argv[0] != "dump":
        print(__doc__)
        return 2
    path, anim, times = argv[1], argv[2], [float(t) for t in argv[3].split(",")]
    mode = argv[argv.index("--mode") + 1] if "--mode" in argv else "bb"
    bp = Blueprint(path)
    for t in times:
        mats = world_matrices(bp, anim, t, mode)
        for name in PARTS:
            if name in mats:
                m = mats[name]
                print("%5.2f %-6s pivot=(%7.2f %7.2f %7.2f) rotZYX=(%7.1f %7.1f %7.1f)" % (
                    (t, name, m[0][3], m[1][3], m[2][3]) + euler_of(m)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
