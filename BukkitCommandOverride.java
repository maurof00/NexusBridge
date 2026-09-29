package dev.crossbridge.spigot;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Replaces selected Bukkit/vanilla command registrations with CrossBridge commands.
 * The runtime command map is reached reflectively to avoid a compile-time CraftBukkit dependency.
 */
final class BukkitCommandOverride {
    private final CrossBridgeSpigot plugin;
    private final CommandMap commandMap;
    private final Set<Command> originals = new LinkedHashSet<>();

    BukkitCommandOverride(CrossBridgeSpigot plugin) {
        this.plugin = plugin;
        this.commandMap = findCommandMap();
    }

    boolean override(String primary, String... aliases) {
        PluginCommand command = plugin.getCommand(primary);
        if (command == null) {
            plugin.getLogger().warning("Command /" + primary + " is missing from plugin.yml; cannot override Bukkit registration.");
            return false;
        }

        List<String> labels = new ArrayList<>();
        labels.add(primary);
        for (String alias : aliases) labels.add(alias);

        for (String label : labels) captureAndUnregisterVanilla(label);
        command.unregister(commandMap);
        boolean registered = commandMap.register(plugin.getName().toLowerCase(Locale.ROOT), command);
        if (!registered || commandMap.getCommand(primary) != command) {
            plugin.getLogger().severe("Could not claim the native command /" + primary + ". Bukkit may keep its original implementation.");
            return false;
        }

        for (String alias : aliases) {
            if (commandMap.getCommand(alias) != command) {
                commandMap.register(alias, plugin.getName().toLowerCase(Locale.ROOT), command);
            }
        }
        plugin.getLogger().info("Overrode Bukkit command /" + primary + (aliases.length == 0 ? "" : " (aliases: " + String.join(", ", aliases) + ")"));
        return true;
    }

    private void captureAndUnregisterVanilla(String label) {
        Command existing = commandMap.getCommand(label);
        if (existing == null) existing = commandMap.getCommand("minecraft:" + label);
        if (existing == null) return;
        if (existing instanceof PluginCommand pc && pc.getPlugin() == plugin) return;

        String className = existing.getClass().getName();
        boolean vanilla = className.startsWith("org.bukkit.command.defaults.")
                || className.contains("VanillaCommandWrapper")
                || className.contains("BukkitCommand");
        if (!vanilla) {
            plugin.getLogger().warning("/" + label + " is provided by " + className + ". CrossBridge will not replace another plugin's command.");
            return;
        }

        originals.add(existing);
        existing.unregister(commandMap);
    }

    void restore() {
        Command ourCommand;
        for (String primary : List.of("gamemode", "teleport", "fly", "tphere", "heal", "feed", "speed", "clear", "kill", "vanish")) {
            ourCommand = commandMap.getCommand(primary);
            if (ourCommand instanceof PluginCommand pc && pc.getPlugin() == plugin) {
                ourCommand.unregister(commandMap);
            }
        }
        for (Command original : originals) {
            try {
                original.unregister(commandMap);
                commandMap.register("minecraft", original);
            } catch (Throwable ex) {
                plugin.getLogger().warning("Could not restore original command " + original.getName() + ": " + ex.getMessage());
            }
        }
        originals.clear();
    }

    private CommandMap findCommandMap() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
            Object result = method.invoke(Bukkit.getServer());
            if (result instanceof CommandMap map) return map;
            throw new IllegalStateException("getCommandMap() returned an unexpected type: " + result);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("CrossBridge requires a CraftServer-compatible Bukkit command map.", ex);
        }
    }
}
