package de.tomalbrc.danse.util;

import de.tomalbrc.danse.Danse;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Note block songs that play while a gesture runs: an Open Note Block Studio file named after the
 * gesture ({@code model/danse/<gesture>.nbs} in the jar, or {@code config/danse/<gesture>.nbs}),
 * played from where the player dances with vanilla note block sounds, so a vanilla client hears it
 * with nothing in the resource pack. Heard by everyone nearby, under the Jukebox/Note Blocks volume.
 */
public final class GestureSongs {
    /** The 16 instruments of Note Block Studio's file format, in its order. */
    private static final NoteBlockInstrument[] NBS_INSTRUMENTS = {
            NoteBlockInstrument.HARP, NoteBlockInstrument.BASS, NoteBlockInstrument.BASEDRUM, NoteBlockInstrument.SNARE,
            NoteBlockInstrument.HAT, NoteBlockInstrument.GUITAR, NoteBlockInstrument.FLUTE, NoteBlockInstrument.BELL,
            NoteBlockInstrument.CHIME, NoteBlockInstrument.XYLOPHONE, NoteBlockInstrument.IRON_XYLOPHONE, NoteBlockInstrument.COW_BELL,
            NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.BIT, NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING
    };
    /** A note block's own volume: heard about 48 blocks away. */
    private static final float VOLUME = 3.0f;

    private static final Map<String, Optional<Song>> SONGS = new Object2ObjectOpenHashMap<>();
    private static final Map<UUID, Playing> PLAYING = new Object2ObjectOpenHashMap<>();

    private GestureSongs() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    /** Starts the gesture's song, if it has one, at {@code pos}; a song already playing for the player stops. */
    public static void start(ServerPlayer player, String gesture, Vec3 pos) {
        stop(player);
        song(gesture).ifPresent(song -> PLAYING.put(player.getUUID(), new Playing(song, (ServerLevel) player.level(), pos)));
    }

    public static void stop(ServerPlayer player) {
        PLAYING.remove(player.getUUID());
    }

    private static void tick() {
        Iterator<Playing> it = PLAYING.values().iterator();
        while (it.hasNext()) {
            Playing playing = it.next();
            int now = playing.ticks++;
            List<Note> notes = playing.song.notes;
            while (playing.next < notes.size() && notes.get(playing.next).serverTick <= now) {
                Note note = notes.get(playing.next++);
                playing.level.playSound(null, playing.pos.x, playing.pos.y, playing.pos.z,
                        note.instrument.getSoundEvent(), SoundSource.RECORDS, VOLUME * note.volume, note.pitch);
            }
            if (playing.next >= notes.size()) {
                it.remove();
            }
        }
    }

    private static Optional<Song> song(String gesture) {
        return SONGS.computeIfAbsent(gesture, GestureSongs::load);
    }

    private static Optional<Song> load(String gesture) {
        String file = gesture + ".nbs";
        try (InputStream in = Danse.class.getResourceAsStream("/model/danse/" + file)) {
            if (in != null) {
                return Optional.of(parse(in.readAllBytes()));
            }
            Path path = FabricLoader.getInstance().getConfigDir().resolve("danse").resolve(file);
            if (Files.isRegularFile(path)) {
                return Optional.of(parse(Files.readAllBytes(path)));
            }
        } catch (IOException | RuntimeException e) {
            Danse.LOGGER.warn("Could not read the note block song {}: {}", file, e.toString());
        }
        return Optional.empty();
    }

    /**
     * An Open Note Block Studio file, versions 0 to 5 (version 0 being the original Note Block
     * Studio's). Only what playback needs is kept: when each note sounds, its instrument, pitch and
     * volume. Notes on custom instruments are skipped.
     */
    static Song parse(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = 0;
        int first = buf.getShort() & 0xFFFF;
        if (first == 0) {
            version = buf.get();
            buf.get(); // vanilla instrument count
            if (version >= 3) buf.getShort(); // song length
        }
        int layerCount = buf.getShort() & 0xFFFF;
        string(buf); // name
        string(buf); // author
        string(buf); // original author
        string(buf); // description
        float ticksPerSecond = (buf.getShort() & 0xFFFF) / 100f;
        buf.get(); // auto-save
        buf.get(); // auto-save minutes
        buf.get(); // time signature
        buf.getInt(); // minutes spent
        buf.getInt(); // left clicks
        buf.getInt(); // right clicks
        buf.getInt(); // blocks added
        buf.getInt(); // blocks removed
        string(buf); // imported file name
        if (version >= 4) {
            buf.get(); // loop
            buf.get(); // max loop count
            buf.getShort(); // loop start
        }

        record Raw(int tick, int layer, int instrument, int key, int velocity, int pitch) {}
        List<Raw> raw = new ObjectArrayList<>();
        int tick = -1;
        for (int jump; (jump = buf.getShort() & 0xFFFF) != 0; ) {
            tick += jump;
            int layer = -1;
            for (int layerJump; (layerJump = buf.getShort() & 0xFFFF) != 0; ) {
                layer += layerJump;
                int instrument = buf.get() & 0xFF;
                int key = buf.get() & 0xFF;
                int velocity = 100, pitch = 0;
                if (version >= 4) {
                    velocity = buf.get() & 0xFF;
                    buf.get(); // panning
                    pitch = buf.getShort();
                }
                raw.add(new Raw(tick, layer, instrument, key, velocity, pitch));
            }
        }

        int[] layerVolume = new int[Math.max(layerCount, 1)];
        java.util.Arrays.fill(layerVolume, 100);
        if (buf.hasRemaining()) {
            for (int i = 0; i < layerCount && buf.hasRemaining(); i++) {
                string(buf); // name
                if (version >= 4) buf.get(); // lock
                layerVolume[i] = buf.get() & 0xFF;
                if (version >= 2) buf.get(); // stereo
            }
        }

        float serverTicksPerSongTick = 20f / (ticksPerSecond > 0 ? ticksPerSecond : 10f);
        List<Note> notes = new ObjectArrayList<>();
        for (Raw r : raw) {
            if (r.instrument >= NBS_INSTRUMENTS.length) continue;
            float volume = (r.velocity / 100f) * ((r.layer < layerVolume.length ? layerVolume[r.layer] : 100) / 100f);
            // Key 45 is F#4, a note block's pitch 1; a semitone a key, fine pitch in cents.
            float pitch = (float) Math.pow(2, (r.key - 45 + r.pitch / 100.0) / 12.0);
            notes.add(new Note(Math.round(r.tick * serverTicksPerSongTick), NBS_INSTRUMENTS[r.instrument], pitch, volume));
        }
        notes.sort(java.util.Comparator.comparingInt(Note::serverTick));
        return new Song(List.copyOf(notes));
    }

    private static String string(ByteBuffer buf) {
        byte[] b = new byte[buf.getInt()];
        buf.get(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    record Song(List<Note> notes) {}

    record Note(int serverTick, NoteBlockInstrument instrument, float pitch, float volume) {}

    private static final class Playing {
        final Song song;
        final ServerLevel level;
        final Vec3 pos;
        int ticks;
        int next;

        Playing(Song song, ServerLevel level, Vec3 pos) {
            this.song = song;
            this.level = level;
            this.pos = pos;
        }
    }
}
