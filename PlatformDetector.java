package dev.crossbridge.common.platform;

/**
 * Runtime detector used by the universal build for diagnostics and integrations.
 * Platform plugin loaders still select their entrypoint through plugin.yml, bungee.yml or velocity-plugin.json.
 */
public final class PlatformDetector {
    private PlatformDetector() {}

    public static Platform detect() {
        if (classExists("com.velocitypowered.api.proxy.ProxyServer")) return Platform.VELOCITY;
        if (classExists("net.md_5.bungee.api.ProxyServer")) return Platform.BUNGEE;
        if (classExists("org.bukkit.Bukkit")) return Platform.SPIGOT;
        return Platform.UNKNOWN;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, PlatformDetector.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
