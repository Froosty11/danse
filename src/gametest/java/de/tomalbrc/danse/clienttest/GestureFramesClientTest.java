package de.tomalbrc.danse.clienttest;

import de.tomalbrc.bil.api.Animator;
import de.tomalbrc.bil.core.model.Animation;
import de.tomalbrc.bil.core.model.Model;
import de.tomalbrc.bil.core.model.Node;
import de.tomalbrc.bil.core.model.Pose;
import de.tomalbrc.bil.util.IChunkMap;
import de.tomalbrc.danse.GestureController;
import de.tomalbrc.danse.poly.GestureCameraHolder;
import de.tomalbrc.danse.registry.PlayerModelRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Gestures photographed at known moments, to set beside Blockbench's pose of the same moment
 * (tools/bbpose in this repo draws that).
 *
 * <p>The Tester runs {@code /gesture <name>} like any player. Danse then puts the Tester's camera on
 * an orbit around the stand-in, steered by where the Tester looks: yaw 0 is in front of the figure
 * (it faces where the Tester faced, south), yaw 90 on the figure's right. For each moment the
 * gesture is frozen on exactly that frame — {@link Animator#setAnimationFrame}, one server tick to
 * play it, then {@link Animator#pauseAnimation} once the tick's pose packets are out — and
 * photographed from the front and from the side. Every frame's bone matrices, as Danse's importer
 * made them, go to {@code screenshots/poses.txt} for a numeric comparison.
 *
 * <p>Run: {@code ./gradlew runClientGameTest} (opens a window). Screenshots land in
 * {@code build/run/clientGameTest/screenshots}.
 */
public final class GestureFramesClientTest implements FabricClientGameTest {
    private static final double[] TIMES = {0, 0.5, 1, 2, 4};
    private static final String[] GESTURES = {"fortnite", "floss", "amogus", "rote_pferd"};
    private static final String[] PARTS = {"head", "body", "arm_r", "arm_l", "leg_r", "leg_l"};
    private static final int FOV = 50;
    private static final int SETTLE_TICKS = 12;

    /** Run once at the end of the next server tick, after that tick's bone updates went out. */
    private static final AtomicReference<Consumer<MinecraftServer>> AFTER_TICK = new AtomicReference<>();
    private static volatile int pausedFrame = -1;
    private static volatile String pauseOnStart;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Consumer<MinecraftServer> task = AFTER_TICK.getAndSet(null);
            if (task != null) task.accept(server);
            // A gesture shorter than the camera's swing-out (amogus is 1.2 s) would be over before the
            // first seek: hold it the tick it starts.
            String starting = pauseOnStart;
            GestureCameraHolder camera = starting == null ? null : camera(server);
            if (camera != null) {
                camera.getPlayerModel().getHolder().getAnimator().pauseAnimation(starting);
                pauseOnStart = null;
            }
        });

        Path out = FabricLoader.getInstance().getGameDir().resolve("screenshots");
        writePoses(out.resolve("poses.txt"));

        Properties props = new Properties();
        props.setProperty("online-mode", "false");
        props.setProperty("server-port", "25601");
        props.setProperty("level-type", "minecraft:flat");
        props.setProperty("spawn-monsters", "false");
        props.setProperty("spawn-animals", "false");
        props.setProperty("difficulty", "peaceful");
        // One server for every gesture: bil shuts its update executor down when a server stops.
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
            try (TestDedicatedServerConnection conn = server.connect()) {
                acceptResourcePack(ctx);
                conn.waitForChunksRender();
                server.runCommand("gamerule advance_time false");
                server.runCommand("time set noon");
                server.runCommand("gamemode creative Tester");
                ctx.runOnClient(client -> {
                    client.options.fov().set(FOV);
                    if (!client.gui.hud.isHidden()) client.gui.hud.toggle();   // F1
                });

                for (String gesture : GESTURES) {
                    photograph(ctx, server, gesture);
                }
            }
        }
    }

    private static void photograph(ClientGameTestContext ctx, TestDedicatedServerContext server, String gesture) {
        server.runCommand("tp Tester 0.5 -60 0.5 0 0");
        look(ctx, 0);
        ctx.waitTicks(10);

        pauseOnStart = gesture;
        ctx.runOnClient(client -> client.player.connection.sendCommand("gesture " + gesture));
        server.waitFor(mc -> camera(mc) != null && pauseOnStart == null, 20 * 30);
        ctx.waitTicks(20);   // the camera swings out, the stand-in spawns and dresses

        Animation animation = PlayerModelRegistry.getModel(gesture).animations().get(gesture);
        int lastFrame = animation.frames().length - 1;
        List<Integer> frames = new ArrayList<>();
        for (double t : TIMES) {
            int frame = Math.min((int) Math.round(t * 20), lastFrame);
            if (!frames.contains(frame)) frames.add(frame);
        }

        for (int frame : frames) {
            seek(server, gesture, frame);
            String time = String.format(Locale.ROOT, "%05.2f", frame / 20.0);
            look(ctx, 0);
            ctx.waitTicks(SETTLE_TICKS);
            ctx.takeScreenshot(TestScreenshotOptions.of(gesture + "_t" + time + "_front").withSize(1920, 1080));
            look(ctx, 90);
            ctx.waitTicks(SETTLE_TICKS);
            ctx.takeScreenshot(TestScreenshotOptions.of(gesture + "_t" + time + "_side").withSize(1920, 1080));
        }

        server.runOnServer(mc -> GestureController.onStop(mc.getPlayerList().getPlayers().getFirst()));
        server.waitFor(mc -> camera(mc) == null, 20 * 10);
        ctx.waitTicks(30);
    }

    /** The gesture frozen on one frame: played for exactly one tick from there, then paused. */
    private static void seek(TestDedicatedServerContext server, String gesture, int frame) {
        pausedFrame = -1;
        server.runOnServer(mc -> {
            GestureCameraHolder camera = camera(mc);
            if (camera == null) throw new AssertionError("the gesture " + gesture + " is not running");
            Animator animator = camera.getPlayerModel().getHolder().getAnimator();
            animator.setAnimationFrame(gesture, frame);
            animator.playAnimation(gesture, player -> GestureController.onStop(camera));   // resumes it, same callback as Danse's
            AFTER_TICK.set(after -> {
                for (ServerLevel level : after.getAllLevels()) {
                    IChunkMap.blockUntilAsyncTickFinished(level.getChunkSource().chunkMap);
                }
                animator.pauseAnimation(gesture);
                // Played, the frame counter has moved past this frame; on the last one it is spent,
                // and a spent animation ends even while paused. Put it back: the shown pose stays.
                animator.setAnimationFrame(gesture, frame);
                pausedFrame = frame;
            });
        });
        server.waitFor(mc -> pausedFrame == frame, 20 * 10);
    }

    private static GestureCameraHolder camera(MinecraftServer mc) {
        List<ServerPlayer> players = mc.getPlayerList().getPlayers();
        if (players.isEmpty()) return null;
        return GestureController.GESTURE_CAMS.get(players.getFirst().getUUID());
    }

    /** Danse's gesture camera follows the yaw the client sends while it rides the camera. */
    private static void look(ClientGameTestContext ctx, float yaw) {
        ctx.runOnClient(client -> {
            client.player.setYRot(yaw);
            client.player.setXRot(0);
            client.player.setYHeadRot(yaw);
        });
    }

    /** The pack is accepted unasked (ServerPackAutoAcceptMixin); wait for it to download and apply. */
    private static void acceptResourcePack(ClientGameTestContext ctx) {
        ctx.waitTicks(20);
        ctx.waitFor(client -> client.gui.overlay() == null && !(client.gui.screen() instanceof ConfirmScreen), 20 * 90);
        ctx.waitTicks(20);
    }

    /**
     * Each photographed frame's bone matrices as Danse's importer made them: entity space in blocks,
     * column-major, BIL's root rotateY(pi) included. tools/bbpose/compare.py reads this.
     */
    private static void writePoses(Path file) {
        StringBuilder sb = new StringBuilder();
        for (String gesture : GESTURES) {
            Model model = PlayerModelRegistry.getModel(gesture);
            if (model == null) {
                sb.append("missing ").append(gesture).append('\n');
                continue;
            }
            Animation animation = model.animations().get(gesture);
            for (int i = 0; i < animation.frames().length; i++) {
                Map<UUID, Pose> poses = animation.frames()[i].poses();
                for (Node node : model.nodeMap().values()) {
                    if (!List.of(PARTS).contains(node.name())) continue;
                    Pose pose = poses.get(node.uuid());
                    if (pose == null) continue;
                    Matrix4fc m = pose.matrix();
                    sb.append(String.format(Locale.ROOT, "%s %d %s", gesture, i, node.name()));
                    for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) sb.append(String.format(Locale.ROOT, " %.6f", m.get(c, r)));
                    sb.append('\n');
                }
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot write " + file, e);
        }
    }
}
