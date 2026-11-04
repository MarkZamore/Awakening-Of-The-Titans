package dev.lanminecraft.aotlock;

import java.io.File;
import net.fabricmc.loader.api.FabricLoader;

/** Mutable MCEF data belongs to this game instance, outside launcher-managed mods. */
public final class McefCachePaths {
    private McefCachePaths() { }
    public static File librariesDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("launcher-runtime").resolve("mcef").resolve("libraries").toFile();
    }
}
