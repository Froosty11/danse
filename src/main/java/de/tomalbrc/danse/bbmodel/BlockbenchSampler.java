package de.tomalbrc.danse.bbmodel;

import de.tomalbrc.bil.core.model.Node;
import de.tomalbrc.bil.core.model.Pose;
import de.tomalbrc.bil.file.bbmodel.BbAnimation;
import de.tomalbrc.bil.file.bbmodel.BbAnimator;
import de.tomalbrc.bil.file.bbmodel.BbKeyframe;
import de.tomalbrc.bil.file.extra.BbVariablePlaceholders;
import de.tomalbrc.bil.file.extra.easing.EasingType;
import de.tomalbrc.bil.file.extra.interpolation.Interpolation;
import de.tomalbrc.bil.util.Utils;
import de.tomalbrc.libs.molang.molangcompiler.api.MolangEnvironment;
import de.tomalbrc.libs.molang.molangcompiler.api.exception.MolangRuntimeException;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Keyframes sampled the way Blockbench 5 plays them in an Animated Java 1.11 project, so a gesture
 * moves in game as it does in the editor.
 *
 * <p>blockbench-import-library's own sampler gets the transform maths right (Blockbench 5 adds
 * rotation keyframes straight onto the bone's ZYX Euler angles, as BIL's {@code BbModel5Importer}
 * does) but picks the interpolation from the later keyframe alone. Blockbench
 * ({@code BoneAnimator.interpolate}, js/animations/timeline_animators.js) looks at both neighbours:
 * the earlier keyframe holds if it is a step, the curve is Catmull-Rom if either is, and linear only
 * when the earlier is linear and the later linear or step. A Catmull-Rom keyframe followed by a
 * linear one is a curve in Blockbench and a straight line in BIL; that and Animated Java's easing
 * (only on the linear path, scalar times 1.70158 for the "back" family) put arms several degrees
 * off. This is a port of Blockbench's version; the result feeds BIL's own pose maths unchanged.
 */
public final class BlockbenchSampler {
    private static final float EPSILON = 1f / 1200f;
    private static final float BACK_SCALAR = 1.70158f;

    private BlockbenchSampler() {}

    /** BbModel5Importer.poses with this sampler: bone matrices for one frame. */
    public static Reference2ObjectOpenHashMap<UUID, Pose> poses(BbAnimation animation, @Nullable BbVariablePlaceholders placeholders, Object2ObjectOpenHashMap<UUID, Node> nodeMap, MolangEnvironment environment, float time) throws MolangRuntimeException {
        Reference2ObjectOpenHashMap<UUID, Pose> poses = new Reference2ObjectOpenHashMap<>();
        for (var entry : nodeMap.entrySet()) {
            Matrix4f matrix4f = new Matrix4f().rotateY(Mth.PI);
            List<Node> nodePath = new ObjectArrayList<>();
            for (Node n = entry.getValue(); n != null; n = n.parent()) nodePath.addFirst(n);

            for (Node node : nodePath) {
                BbAnimator animator = animation.animators != null ? animation.animators.get(node.uuid()) : null;
                List<BbKeyframe> keyframes = animator == null ? List.of() : animator.keyframes;
                boolean loop = animation.loop == BbAnimation.LoopMode.LOOP;

                Vector3f pos = channel(keyframes, BbKeyframe.Channel.POSITION, loop, placeholders, environment, time);
                Vector3f rot = channel(keyframes, BbKeyframe.Channel.ROTATION, loop, placeholders, environment, time);
                Vector3f scale = channel(keyframes, BbKeyframe.Channel.SCALE, loop, placeholders, environment, time);
                if (pos == null) pos = new Vector3f();
                if (rot == null) rot = new Vector3f();
                if (scale == null) scale = new Vector3f(1);
                // displayScale: a zero scale is drawn as 0.00001 (and keeps the matrix decomposable)
                if (scale.x == 0) scale.x = 1e-5f;
                if (scale.y == 0) scale.y = 1e-5f;
                if (scale.z == 0) scale.z = 1e-5f;

                Quaternionf localRot = Utils.createQuaternion(rot.add(node.transform().rotation()));
                Vector3f localPos = pos.div(16).add(node.transform().origin());

                matrix4f.translate(localPos);
                matrix4f.rotate(localRot);
                matrix4f.scale(scale);
            }

            poses.put(entry.getKey(), Pose.of(matrix4f.scale(entry.getValue().transform().localScale())));
        }
        return poses;
    }

    /** BoneAnimator.interpolate for one channel, or null when the channel has no keyframes. */
    @Nullable
    public static Vector3f channel(List<BbKeyframe> all, BbKeyframe.Channel channel, boolean loop, @Nullable BbVariablePlaceholders placeholders, MolangEnvironment env, float time) throws MolangRuntimeException {
        List<BbKeyframe> keyframes = new ObjectArrayList<>();
        if (all != null) for (BbKeyframe kf : all) if (kf.channel == channel) keyframes.add(kf);
        if (keyframes.isEmpty()) return null;
        keyframes.sort(Comparator.comparingDouble(kf -> kf.time));

        BbKeyframe before = null, after = null;
        for (BbKeyframe kf : keyframes) {
            if (kf.time < time) {
                if (before == null || kf.time > before.time) before = kf;
            } else if (after == null || kf.time < after.time) {
                after = kf;
            }
        }

        BbKeyframe result;
        if (before != null && Math.abs(before.time - time) < EPSILON) result = before;
        else if (after != null && Math.abs(after.time - time) < EPSILON) result = after;
        else if (before != null && before.interpolation == Interpolation.STEP) result = before;
        else if (before != null && after == null) result = before;
        else if (after != null && before == null) result = after;
        else if (before == null) return null;
        else return between(keyframes, before, after, loop, placeholders, env, time);

        int dataPoint = result.time > time || Math.abs(result.time - time) < EPSILON ? 0 : result.dataPoints.size() - 1;
        return value(result, dataPoint, placeholders, env);
    }

    private static Vector3f between(List<BbKeyframe> sorted, BbKeyframe before, BbKeyframe after, boolean loop, @Nullable BbVariablePlaceholders placeholders, MolangEnvironment env, float time) throws MolangRuntimeException {
        float alpha = (time - before.time) / (after.time - before.time);
        Interpolation b = before.interpolation == null ? Interpolation.LINEAR : before.interpolation;
        Interpolation a = after.interpolation == null ? Interpolation.LINEAR : after.interpolation;

        if (b == Interpolation.LINEAR && (a == Interpolation.LINEAR || a == Interpolation.STEP)) {
            EasingType easing = after.easing == null ? EasingType.LINEAR : after.easing;
            if (easing != EasingType.LINEAR) {
                // Animated Java's Keyframe.getLerp patch: the later keyframe's easing, first data points
                float eased = (float) easing.apply(alpha, easingArgs(easing, after.easingArgs));
                return value(before, 0, placeholders, env).lerp(value(after, 0, placeholders, env), eased);
            }
            Vector3f from = value(before, before.dataPoints.size() > 1 ? 1 : 0, placeholders, env);
            return from.lerp(value(after, 0, placeholders, env), alpha);
        }

        if (b == Interpolation.SMOOTH || a == Interpolation.SMOOTH) {
            int i = sorted.indexOf(before);
            BbKeyframe beforePlus = i - 1 >= 0 ? sorted.get(i - 1) : null;
            BbKeyframe afterPlus = i + 2 < sorted.size() ? sorted.get(i + 2) : null;
            if (loop && sorted.size() >= 3) {
                if (beforePlus == null) beforePlus = sorted.get(sorted.size() - 2);
                if (afterPlus == null) afterPlus = sorted.get(1);
            }

            // Keyframe.getCatmullromLerp: a THREE.SplineCurve through the neighbours, read at a parameter
            List<Vector3f> points = new ObjectArrayList<>(4);
            if (beforePlus != null && before.dataPoints.size() == 1) points.add(value(beforePlus, 1, placeholders, env));
            points.add(value(before, 1, placeholders, env));
            points.add(value(after, 0, placeholders, env));
            if (afterPlus != null && after.dataPoints.size() == 1) points.add(value(afterPlus, 0, placeholders, env));
            float t = (alpha + (beforePlus != null ? 1 : 0)) / (points.size() - 1);
            return spline(points, t);
        }

        if (b == Interpolation.BEZIER || a == Interpolation.BEZIER) {
            Vector3f vb = value(before, 1, placeholders, env), va = value(after, 0, placeholders, env);
            return new Vector3f(
                    bezier(before, after, 0, vb.x, va.x, alpha),
                    bezier(before, after, 1, vb.y, va.y, alpha),
                    bezier(before, after, 2, vb.z, va.z, alpha));
        }

        return value(before, 0, placeholders, env);
    }

    private static double[] easingArgs(EasingType easing, double[] args) {
        String name = easing.name();
        if (name.endsWith("_BACK")) {
            // Animated Java: back(1.70158 * scalar), scalar defaulting to 1; BIL's BACK takes s itself
            double scalar = args != null && args.length > 0 ? args[0] : 1;
            return new double[]{BACK_SCALAR * scalar};
        }
        return args == null ? new double[0] : args;
    }

    /** Keyframe.calc(axis, data_point): the index is clamped to the keyframe's data points. */
    private static Vector3f value(BbKeyframe kf, int dataPoint, @Nullable BbVariablePlaceholders placeholders, MolangEnvironment env) throws MolangRuntimeException {
        int index = Mth.clamp(dataPoint, 0, kf.dataPoints.size() - 1);
        return kf.getVector3f(index, placeholders, env);
    }

    /** THREE.SplineCurve.getPoint(t), per axis. */
    private static Vector3f spline(List<Vector3f> points, float t) {
        int n = points.size();
        float p = (n - 1) * t;
        int i = Mth.floor(p);
        float w = p - i;
        Vector3f p0 = points.get(i == 0 ? i : i - 1);
        Vector3f p1 = points.get(i);
        Vector3f p2 = points.get(i > n - 2 ? n - 1 : i + 1);
        Vector3f p3 = points.get(i > n - 3 ? n - 1 : i + 2);
        return new Vector3f(
                catmullRom(w, p0.x, p1.x, p2.x, p3.x),
                catmullRom(w, p0.y, p1.y, p2.y, p3.y),
                catmullRom(w, p0.z, p1.z, p2.z, p3.z));
    }

    private static float catmullRom(float t, float p0, float p1, float p2, float p3) {
        float v0 = (p2 - p0) * 0.5f;
        float v1 = (p3 - p1) * 0.5f;
        float t2 = t * t;
        float t3 = t * t2;
        return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
    }

    /** Keyframe.getBezierLerp: the curve sampled at 200 steps, the two points nearest in time lerped. */
    private static float bezier(BbKeyframe before, BbKeyframe after, int axis, float valueBefore, float valueAfter, float alpha) {
        float gap = after.time - before.time;
        float handleBefore = Mth.clamp(component(before.bezierRightTime, axis), 0, gap);
        float handleAfter = Mth.clamp(component(after.bezierLeftTime, axis), -gap, 0);
        float[] xs = {before.time, before.time + handleBefore, after.time + handleAfter, after.time};
        float[] ys = {valueBefore, valueBefore + component(before.bezierRightValue, axis),
                valueAfter + component(after.bezierLeftValue, axis), valueAfter};
        float time = before.time + gap * alpha;

        float bestX = 0, bestY = 0, secondX = 0, secondY = 0;
        float bestDiff = Float.MAX_VALUE, secondDiff = Float.MAX_VALUE;
        for (int k = 0; k <= 200; k++) {
            float s = k / 200f, u = 1 - s;
            float c0 = u * u * u, c1 = 3 * u * u * s, c2 = 3 * u * s * s, c3 = s * s * s;
            float x = c0 * xs[0] + c1 * xs[1] + c2 * xs[2] + c3 * xs[3];
            float y = c0 * ys[0] + c1 * ys[1] + c2 * ys[2] + c3 * ys[3];
            float diff = Math.abs(x - time);
            if (diff < bestDiff) {
                secondDiff = bestDiff; secondX = bestX; secondY = bestY;
                bestDiff = diff; bestX = x; bestY = y;
            } else if (diff < secondDiff) {
                secondDiff = diff; secondX = x; secondY = y;
            }
        }
        float f = secondX == bestX ? 0 : Mth.clamp((time - bestX) / (secondX - bestX), 0, 1);
        return Mth.lerp(f, bestY, secondY);
    }

    private static float component(@Nullable Vector3f v, int axis) {
        return v == null ? 0 : v.get(axis);
    }
}
