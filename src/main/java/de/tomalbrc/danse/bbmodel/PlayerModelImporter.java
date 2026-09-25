package de.tomalbrc.danse.bbmodel;

import com.google.common.collect.ImmutableMap;
import de.tomalbrc.bil.core.model.Node;
import de.tomalbrc.bil.file.bbmodel.BbModel;
import de.tomalbrc.bil.file.bbmodel.BbOutliner;
import de.tomalbrc.bil.file.bbmodel.BbTexture;
import de.tomalbrc.bil.file.extra.BbResourcePackGenerator;
import de.tomalbrc.bil.file.extra.ResourcePackModel;
import de.tomalbrc.bil.file.importer.AjBlueprintImporter;
import de.tomalbrc.danse.util.Util;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.UUID;

public class PlayerModelImporter extends AjBlueprintImporter {
    public PlayerModelImporter(BbModel model) {
        super(model);
    }

    public static Identifier addItemModel(String partName, Map<String, ResourcePackModel.DisplayTransform> transformMap) {
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(resourcePackBuilder -> {
            addPart(partName, transformMap, resourcePackBuilder);
            if (Util.isArm(partName)) {
                // slim arms
                var displayMap = ImmutableMap.of("head", PartTransforms.of(partName + "s").toBil());
                addPart(partName + "s", displayMap, resourcePackBuilder);
            }
        });

        return Identifier.fromNamespaceAndPath("danse", partName);
    }

    @Override
    protected Identifier generateModel(BbOutliner outliner) {
        return switch (outliner.name) {
            case "head", "body", "arm_r", "arm_l", "leg_r", "leg_l" -> addItemModel(outliner.name.toLowerCase(),
                    ImmutableMap.of("head", PartTransforms.of(outliner.name.toLowerCase()).toBil()));
            default -> super.generateModel(outliner);
        };
    }

    private static void addPart(String partName, Map<String, ResourcePackModel.DisplayTransform> transformMap, ResourcePackBuilder resourcePackBuilder) {
        var size = Util.sizeFor(partName);
        var dataMap = PerPixelModelGenerator.generatePerPixelModels(size.getX(), size.getY(), size.getZ(), partName, transformMap);
        for (Map.Entry<String, byte[]> entry : dataMap.entrySet()) {
            resourcePackBuilder.addData(entry.getKey(), entry.getValue());
        }
    }

    @Override
    protected Object2ObjectOpenHashMap<UUID, Node> makeNodeMap() {
        Object2ObjectOpenHashMap<UUID, Node> nodeMap = new Object2ObjectOpenHashMap<>();
        ObjectArraySet<BbTexture> textures = new ObjectArraySet<>();
        textures.addAll(this.model.textures);

        for (BbOutliner.ChildEntry entry : this.model.outliner) {
            if (entry.isNode()) {
                this.createBones(null, null, this.model.outliner, nodeMap);
            }
        }

        // filter out unused skin texture
        BbResourcePackGenerator.makeTextures(this.model, textures.stream().filter(x -> x.name.equals("texture")).toList());
        return nodeMap;
    }
}
