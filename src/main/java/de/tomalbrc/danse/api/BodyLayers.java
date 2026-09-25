package de.tomalbrc.danse.api;

import de.tomalbrc.danse.util.MinecraftSkinParser.BodyPart;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BodyLayers {
    private BodyLayers() {}

    private static final List<BodyLayerProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(BodyLayerProvider provider) {
        PROVIDERS.add(provider);
    }

    /** The first provider's non-empty answer, or {@link ItemStack#EMPTY}. */
    public static ItemStack layer(EntityEquipment equipment, BodyPart part, boolean inner) {
        for (BodyLayerProvider provider : PROVIDERS) {
            ItemStack stack = provider.layer(equipment, part, inner);
            if (stack != null && !stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    public static boolean replacesArmor(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (BodyLayerProvider provider : PROVIDERS) {
            if (provider.replacesArmor(stack)) return true;
        }
        return false;
    }
}
