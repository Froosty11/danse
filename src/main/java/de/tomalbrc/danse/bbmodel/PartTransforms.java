package de.tomalbrc.danse.bbmodel;

import de.tomalbrc.bil.file.extra.ResourcePackModel;
import org.joml.Vector3f;

/**
 * Where each stand-in part's model sits in its display ("head" context): one table for the
 * per-pixel skin models and for {@link de.tomalbrc.danse.api.BodyLayerModels}, so a body layer
 * always lines up with the part it rides on.
 */
public final class PartTransforms {
    private PartTransforms() {}

    public record Transform(Vector3f translation, Vector3f scale) {
        public ResourcePackModel.DisplayTransform toBil() {
            return new ResourcePackModel.DisplayTransform(null, new Vector3f(translation), new Vector3f(scale));
        }
    }

    static final Vector3f LIMB_SCALE = new Vector3f(0.46875f, 1.40625f, 0.46875f);
    static final Vector3f LIMB_SCALE_SLIM = new Vector3f(0.3225f, 1.40625f, 0.46875f);

    public static Transform of(String partName) {
        return switch (partName) {
            case "head" -> new Transform(new Vector3f(0, 5.90f, 0), new Vector3f(0.9375f, 0.9375f, 0.9375f));
            case "body" -> new Transform(new Vector3f(0, 8.25f + 0.25f, 0), new Vector3f(0.9375f, 1.40625f, 0.46875f));
            case "arm_r" -> new Transform(new Vector3f(0.35f, 0.5f, 0), LIMB_SCALE);
            case "arm_l" -> new Transform(new Vector3f(-0.35f, 0.5f, 0), LIMB_SCALE);
            case "arm_rs" -> new Transform(new Vector3f(0.9575f, 0.5f, 0.f), LIMB_SCALE_SLIM);
            case "arm_ls" -> new Transform(new Vector3f(-0.9575f, 0.5f, 0.f), LIMB_SCALE_SLIM);
            case "leg_r" -> new Transform(new Vector3f(0.125f, -1.9f + 0.16f, 0), LIMB_SCALE);
            case "leg_l" -> new Transform(new Vector3f(-0.125f, -1.9f + 0.16f, 0), LIMB_SCALE);
            default -> throw new IllegalArgumentException("not a stand-in part: " + partName);
        };
    }
}
