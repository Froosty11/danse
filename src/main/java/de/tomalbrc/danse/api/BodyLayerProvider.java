package de.tomalbrc.danse.api;

import de.tomalbrc.danse.util.MinecraftSkinParser.BodyPart;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.item.ItemStack;

/**
 * Puts a textured item model on a stand-in's body part. {@code inner}/{@code outer} follow Danse's
 * armour passes: body inner = legs slot, body outer = chest slot; leg inner = legs slot, leg
 * outer = feet slot; arms and head = their own slot for both. The inner layer draws just inside
 * the inner armour shell, the outer just inside the outer one, so real armour still covers it.
 */
public interface BodyLayerProvider {
    /** A stack (item model + components) to draw, or {@link ItemStack#EMPTY}. */
    ItemStack layer(EntityEquipment equipment, BodyPart part, boolean inner);

    /** True when Danse's own pixel armour should be left blank for this equipment stack. */
    default boolean replacesArmor(ItemStack stack) {
        return false;
    }
}
