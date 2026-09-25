package de.tomalbrc.danse.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.tomalbrc.bil.util.Permissions;
import de.tomalbrc.danse.GestureController;
import de.tomalbrc.danse.ModConfig;
import de.tomalbrc.danse.registry.PlayerModelRegistry;
import de.tomalbrc.danse.util.GestureDialog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;

import static net.minecraft.commands.Commands.literal;

public class GestureCommand {
    public static final String SOURCE_URL = "https://github.com/Froosty11/danse";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> gestureNode = Commands
                .literal("gesture").requires(Permissions.require("danse.animation", 1).or((s) -> !ModConfig.getInstance().permissionCheck))
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayer();
                    if (player != null) {
                        if (ModConfig.getInstance().fancyHud) {
                            GestureHudCommand.openHud(player);
                        } else {
                            player.openDialog(Holder.direct(GestureDialog.DIALOG));
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();

        dispatcher.getRoot().addChild(gestureNode);

        // AGPL §13: every player of a network server can find the source they are running against —
        // /danse source has no requirement, where /gesture (and so /gesture source) may have one.
        gestureNode.addChild(literal("source").executes(GestureCommand::source).build());
        dispatcher.getRoot().addChild(literal("danse").then(literal("source").executes(GestureCommand::source)).build());

        for (String animation : PlayerModelRegistry.getAnimations()) {
            var name = animation
                    .replace(" ", "-")
                    .replace("(", "")
                    .replace(")", "");
            gestureNode.addChild(literal(name).requires(Permissions.require("danse.animation." + name, 1).or((s) -> !ModConfig.getInstance().permissionCheck)).executes(ctx -> execute(ctx.getSource().getPlayerOrException(), animation)).build());
        }
    }

    private static int source(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("danse (Metacraft fork, AGPL-3.0) — source: ")
                .append(Component.literal(SOURCE_URL).withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(SOURCE_URL))))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int execute(ServerPlayer player, String animationName) {
        if ((!player.onGround() && !player.isCreative()) || GestureController.GESTURE_CAMS.containsKey(player.getUUID())) {
            return 0;
        }

        GestureController.onStart(player, animationName);

        return Command.SINGLE_SUCCESS;
    }
}
