package de.tomalbrc.danse.bbmodel;

import com.google.common.collect.ImmutableMap;
import de.tomalbrc.bil.core.model.Node;
import de.tomalbrc.bil.core.model.Pose;
import de.tomalbrc.bil.file.bbmodel.BbAnimation;
import de.tomalbrc.bil.file.bbmodel.BbGroup;
import de.tomalbrc.bil.file.bbmodel.BbModel;
import de.tomalbrc.bil.file.bbmodel.BbOutliner;
import de.tomalbrc.bil.file.extra.BbModelUtils;
import de.tomalbrc.bil.file.extra.BbResourcePackGenerator;
import de.tomalbrc.bil.file.importer.AjBlueprint5Importer;
import de.tomalbrc.libs.molang.molangcompiler.api.MolangEnvironment;
import de.tomalbrc.libs.molang.molangcompiler.api.exception.MolangRuntimeException;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The player-model importer for Animated Java 1.11 / Blockbench 5 blueprints: what
 * {@link PlayerModelImporter} does for 1.7.3 files, on top of BIL's Blockbench 5 importer.
 *
 * <p>Blockbench 5 keeps the bones in a separate {@code groups} list (the outliner holds uuids), so
 * a part is recognised by its group's name; each of the six parts gets Danse's stand-in item model
 * instead of the blueprint's cubes. Keyframes are read as Blockbench 5 stores them (no sign flips)
 * and sampled the way Blockbench plays them ({@link BlockbenchSampler}).
 */
public class PlayerModel5Importer extends AjBlueprint5Importer {
    public PlayerModel5Importer(BbModel model) {
        super(model);
    }

    @Override
    protected Identifier generateModel(BbOutliner outliner) {
        BbGroup group = BbModelUtils.getGroup(this.model, outliner);
        String name = group == null || group.name == null ? "" : group.name;
        return switch (name) {
            case "head", "body", "arm_r", "arm_l", "leg_r", "leg_l" -> PlayerModelImporter.addItemModel(name,
                    ImmutableMap.of("head", PartTransforms.of(name).toBil()));
            default -> super.generateModel(outliner);
        };
    }

    @Override
    protected Object2ObjectOpenHashMap<UUID, Node> makeNodeMap() {
        Object2ObjectOpenHashMap<UUID, Node> nodeMap = new Object2ObjectOpenHashMap<>();
        this.createBones(null, null, this.model.outliner, nodeMap);

        // the stand-in draws the player's skin; only a texture named "texture" is the model's own
        BbResourcePackGenerator.makeTextures(this.model, this.model.textures.stream().filter(x -> "texture".equals(x.name)).toList());
        return nodeMap;
    }

    @Override
    @NotNull
    protected Reference2ObjectOpenHashMap<UUID, Pose> poses(BbAnimation animation, Object2ObjectOpenHashMap<UUID, Node> nodeMap, MolangEnvironment environment, float time) throws MolangRuntimeException {
        return BlockbenchSampler.poses(animation, this.model.animationVariablePlaceholders, nodeMap, environment, time);
    }

    /** The plain textured model (the gesture HUD's preview), sampled the same way. */
    public static class Textured extends AjBlueprint5Importer {
        public Textured(BbModel model) {
            super(model);
        }

        @Override
        @NotNull
        protected Reference2ObjectOpenHashMap<UUID, Pose> poses(BbAnimation animation, Object2ObjectOpenHashMap<UUID, Node> nodeMap, MolangEnvironment environment, float time) throws MolangRuntimeException {
            return BlockbenchSampler.poses(animation, this.model.animationVariablePlaceholders, nodeMap, environment, time);
        }
    }
}
