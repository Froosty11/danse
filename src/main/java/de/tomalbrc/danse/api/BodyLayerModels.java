package de.tomalbrc.danse.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.tomalbrc.danse.bbmodel.PartTransforms;
import de.tomalbrc.danse.util.MinecraftSkinParser;
import de.tomalbrc.danse.util.MinecraftSkinParser.BodyPart;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Models for {@link BodyLayers}: one textured cube over the exact box a stand-in part's pixel
 * models fill, with the part's own display transform, UV-mapped in the 64×32 armour layout so a
 * texture shows on the same cells Danse's pixel armour would put it — at the texture's own
 * resolution. UVs are in 0–16 sprite units, so any 2:1 texture in that layout works.
 */
public final class BodyLayerModels {
    private BodyLayerModels() {}

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Model face → the texture-map direction whose texels Danse's pixel models show on it. The pixel
     * generator lays faces out N,S,E,W,U,D and the skin parser writes colours S,N,W,E,U,D; the
     * blocks pair up one to one.
     */
    private static final Map<Direction, Direction> SOURCE = Map.of(
            Direction.NORTH, Direction.SOUTH,
            Direction.SOUTH, Direction.NORTH,
            Direction.EAST, Direction.WEST,
            Direction.WEST, Direction.EAST,
            Direction.UP, Direction.UP,
            Direction.DOWN, Direction.DOWN);

    /**
     * @param part    the stand-in part (wide arms; armour is not slim)
     * @param texture a texture in the {@code items} or {@code blocks} atlas, armour layout
     * @param vShift  skin texels added to every face's v (negative is up)
     * @param inflate model units the cube grows by on every side, so stacked layers never share a plane
     */
    public static String shell(BodyPart part, Identifier texture, int vShift, float inflate) {
        Map<Direction, int[]> regions = MinecraftSkinParser.NOTCH_TEXTURE_MAP.get(part).get(MinecraftSkinParser.Layer.INNER);
        JsonObject faces = new JsonObject();
        for (Direction face : Direction.values()) {
            float[] t = uvTexels(regions.get(SOURCE.get(face)), vShift);
            JsonObject f = new JsonObject();
            f.add("uv", array(t[0] / 4f, t[1] / 2f, t[2] / 4f, t[3] / 2f));
            f.addProperty("texture", "#t");
            faces.add(face.getName(), f);
        }

        JsonObject element = new JsonObject();
        element.add("from", array(4 - inflate, -inflate, 4 - inflate));
        element.add("to", array(12 + inflate, 8 + inflate, 12 + inflate));
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);

        PartTransforms.Transform transform = PartTransforms.of(part.getName());
        JsonObject head = new JsonObject();
        head.add("translation", array(transform.translation()));
        head.add("scale", array(transform.scale()));
        JsonObject display = new JsonObject();
        display.add("head", head);

        JsonObject textures = new JsonObject();
        textures.addProperty("t", texture.toString());
        textures.addProperty("particle", texture.toString());

        JsonObject model = new JsonObject();
        model.add("textures", textures);
        model.add("elements", elements);
        model.add("display", display);
        return GSON.toJson(model);
    }

    /** A region {x, y, w, h} (w negative = mirrored) as [u1, v1, u2, v2] in skin texels. */
    public static float[] uvTexels(int[] region, int vShift) {
        int x = region[0], y = region[1] + vShift, w = region[2], h = region[3];
        float u1 = w > 0 ? x : x + 1;
        float u2 = w > 0 ? x + w : x + 1 + w;
        return new float[]{u1, y, u2, y + h};
    }

    private static JsonArray array(float... values) {
        JsonArray a = new JsonArray();
        for (float v : values) a.add(v);
        return a;
    }

    private static JsonArray array(Vector3f v) {
        return array(v.x, v.y, v.z);
    }
}
