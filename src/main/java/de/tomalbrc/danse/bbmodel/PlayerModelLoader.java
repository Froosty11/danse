package de.tomalbrc.danse.bbmodel;

import com.google.gson.JsonParseException;
import de.tomalbrc.bil.core.model.Model;
import de.tomalbrc.bil.file.bbmodel.BbElement;
import de.tomalbrc.bil.file.bbmodel.BbModel;
import de.tomalbrc.bil.file.importer.AjBlueprintImporter;
import de.tomalbrc.bil.file.loader.AjBlueprintLoader;
import de.tomalbrc.bil.file.loader.ModelLoader;
import de.tomalbrc.bil.util.VersionCheck;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;

/**
 * Loads a gesture blueprint as Danse's player model: the six named parts become the stand-in's
 * skin models. Animated Java 1.11 / Blockbench 5 files ({@code format_version} 1.10.1 and up) go
 * through {@link PlayerModel5Importer} as saved; the older 1.7.3 files through
 * {@link PlayerModelImporter}.
 */
public class PlayerModelLoader extends AjBlueprintLoader {
    private final boolean textured;

    public PlayerModelLoader() {
        this(false);
    }

    /** @param textured the blueprint's own cubes and textures (the gesture HUD's preview) instead of the stand-in */
    public PlayerModelLoader(boolean textured) {
        this.textured = textured;
    }

    @Override
    public Model load(InputStream input, String name) throws JsonParseException {
        try (Reader reader = new InputStreamReader(input)) {
            BbModel model = GSON.fromJson(reader, BbModel.class);

            if (name != null && !name.isEmpty()) model.modelIdentifier = name;
            if (model.modelIdentifier == null) model.modelIdentifier = model.name;
            model.modelIdentifier = ModelLoader.normalizedModelId(model.modelIdentifier);

            if (isBlockbench5(model)) {
                withoutNullFaces(model);
                return this.textured ? new PlayerModel5Importer.Textured(model).importModel() : new PlayerModel5Importer(model).importModel();
            }
            return this.textured ? new AjBlueprintImporter(model).importModel() : new PlayerModelImporter(model).importModel();
        } catch (Throwable throwable) {
            throw new JsonParseException("Failed to parse: " + name, throwable);
        }
    }

    /** A blueprint file from the config folder, as a player model (AjBlueprintLoader.load(String) would import it plain). */
    public static Model load(String path) {
        try (InputStream input = new FileInputStream(path)) {
            String name = "config_" + Path.of(path).getFileName().toString().replaceFirst("\\.ajblueprint$", "");
            return new PlayerModelLoader().load(input, name);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Model doesn't exist: " + path);
        }
    }

    static boolean isBlockbench5(BbModel model) {
        return model.meta != null && model.meta.formatVersion != null
                && VersionCheck.isAtLeastVersion(model.meta.formatVersion, "1.10.1");
    }

    /**
     * Locators and other non-cube elements have no faces; BIL's Blockbench 5 importer walks every
     * element's faces before it filters by type.
     */
    private static void withoutNullFaces(BbModel model) {
        if (model.elements == null) return;
        for (BbElement element : model.elements) {
            if (element.faces == null) element.faces = new Object2ObjectOpenHashMap<>();
        }
    }
}
