package dev.crossbridge.spigot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct command implementation used for the public Bukkit-style commands.
 * CrossBridge registers these commands on the normal labels (gamemode/gm, teleport/tp, ...)
 * so users do not have to learn a second command namespace.
 */
final class NativeBukkitCommandExecutor implements TabExecutor {
    private final CrossBridgeSpigot plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    NativeBukkitCommandExecutor(CrossBridgeSpigot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String root = command.getName().toLowerCase(Locale.ROOT);
        try {
            switch (root) {
                case "gamemode" -> gamemode(sender, args);
                case "teleport" -> teleport(sender, args);
                case "fly" -> fly(sender, args);
                case "tphere" -> tpHere(sender, args);
                case "heal" -> heal(sender, args);
                case "feed" -> feed(sender, args);
                case "speed" -> speed(sender, args);
                case "clear" -> clear(sender, args);
                case "kill" -> kill(sender, args);
                case "vanish" -> vanish(sender, args);
                default -> {
                    sender.sendMessage(ChatColor.RED + "Comando CrossBridge non configurato: /" + label);
                    return true;
                }
            }
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + ex.getMessage());
        }
        return true;
    }

    private void gamemode(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.gamemode");
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Uso: /gamemode <survival|creative|adventure|spectator> [giocatore]");
        GameMode mode = parseGameMode(args[0]);
        Player target = args.length == 2 ? findPlayer(args[1]) : playerSender(sender);
        target.setGameMode(mode);
        msg(sender, ChatColor.GREEN + "Modalità di " + target.getName() + " impostata su " + mode.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void fly(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.fly");
        if (args.length > 1) throw new IllegalArgumentException("Uso: /fly [giocatore]");
        Player target = args.length == 1 ? findPlayer(args[0]) : playerSender(sender);
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        if (!enable && !target.isOnGround()) target.setFlying(false);
        else if (enable) target.setFlying(true);
        msg(sender, ChatColor.GREEN + "Volo di " + target.getName() + (enable ? " abilitato." : " disabilitato."));
    }

    private void teleport(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.tp");
        if (args.length == 1) {
            Player target = findPlayer(args[0]);
            Player source = playerSender(sender);
            if (source.teleport(target.getLocation())) msg(sender, ChatColor.GREEN + "Teletrasportato da " + target.getName() + ".");
            return;
        }
        if (args.length == 2 && isPlayerName(args[0]) && isPlayerName(args[1])) {
            Player source = findPlayer(args[0]);
            Player target = findPlayer(args[1]);
            if (source.teleport(target.getLocation())) msg(sender, ChatColor.GREEN + source.getName() + " teletrasportato da " + target.getName() + ".");
            return;
        }
        if (args.length == 3 || args.length == 5) {
            Player source = playerSender(sender);
            Location loc = source.getLocation().clone();
            loc.setX(parseDouble(args[0], "x"));
            loc.setY(parseDouble(args[1], "y"));
            loc.setZ(parseDouble(args[2], "z"));
            if (args.length == 5) {
                loc.setYaw((float) parseDouble(args[3], "yaw"));
                loc.setPitch((float) parseDouble(args[4], "pitch"));
            }
            if (source.teleport(loc)) msg(sender, ChatColor.GREEN + "Teletrasporto eseguito.");
            return;
        }
        if (args.length == 4 && isPlayerName(args[0])) {
            Player source = findPlayer(args[0]);
            Location loc = source.getLocation().clone();
            loc.setX(parseDouble(args[1], "x"));
            loc.setY(parseDouble(args[2], "y"));
            loc.setZ(parseDouble(args[3], "z"));
            if (source.teleport(loc)) msg(sender, ChatColor.GREEN + "Teletrasportato " + source.getName() + ".");
            return;
        }
        throw new IllegalArgumentException("Uso: /tp <player> | /tp <player> <target> | /tp <x> <y> <z> [yaw pitch]");
    }

    private void tpHere(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.tphere");
        Player source = playerSender(sender);
        if (args.length != 1) throw new IllegalArgumentException("Uso: /tphere <giocatore>");
        Player target = findPlayer(args[0]);
        if (target.teleport(source.getLocation())) msg(sender, ChatColor.GREEN + target.getName() + " teletrasportato da te.");
    }

    private void heal(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.heal");
        if (args.length > 1) throw new IllegalArgumentException("Uso: /heal [giocatore]");
        Player target = args.length == 1 ? findPlayer(args[0]) : playerSender(sender);
        target.setHealth(target.getMaxHealth());
        target.setFireTicks(0);
        msg(sender, ChatColor.GREEN + target.getName() + " curato.");
    }

    private void feed(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.feed");
        if (args.length > 1) throw new IllegalArgumentException("Uso: /feed [giocatore]");
        Player target = args.length == 1 ? findPlayer(args[0]) : playerSender(sender);
        target.setFoodLevel(20);
        target.setSaturation(20f);
        msg(sender, ChatColor.GREEN + target.getName() + " sfamato.");
    }

    private void speed(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.speed");
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Uso: /speed <walk|fly> <1-10> [giocatore]");
        String type = args[0].toLowerCase(Locale.ROOT);
        int level = parseInt(args[1], "velocità");
        if (level < 1 || level > 10) throw new IllegalArgumentException("La velocità deve essere tra 1 e 10.");
        Player target = args.length == 3 ? findPlayer(args[2]) : playerSender(sender);
        float value = level / 10.0f;
        if (type.equals("walk")) target.setWalkSpeed(value);
        else if (type.equals("fly")) target.setFlySpeed(value);
        else throw new IllegalArgumentException("Tipo velocità non valido: walk o fly.");
        msg(sender, ChatColor.GREEN + "Velocità " + type + " di " + target.getName() + " impostata a " + level + "/10.");
    }

    private void clear(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.clear");
        if (args.length > 1) throw new IllegalArgumentException("Uso: /clear [giocatore]");
        Player target = args.length == 1 ? findPlayer(args[0]) : playerSender(sender);
        target.getInventory().clear();
        target.getInventory().setArmorContents(null);
        target.getInventory().setItemInOffHand(null);
        msg(sender, ChatColor.GREEN + "Inventario di " + target.getName() + " svuotato.");
    }

    private void kill(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.kill");
        if (args.length != 1) throw new IllegalArgumentException("Uso: /kill <giocatore>");
        Player target = findPlayer(args[0]);
        target.setHealth(0);
        msg(sender, ChatColor.GREEN + target.getName() + " eliminato.");
    }

    private void vanish(CommandSender sender, String[] args) {
        require(sender, "crossbridge.command.vanish");
        if (args.length > 1) throw new IllegalArgumentException("Uso: /vanish [giocatore]");
        Player target = args.length == 1 ? findPlayer(args[0]) : playerSender(sender);
        if (vanished.add(target.getUniqueId())) {
            for (Player viewer : Bukkit.getOnlinePlayers()) if (!viewer.equals(target)) viewer.hidePlayer(plugin, target);
            msg(sender, ChatColor.GREEN + target.getName() + " è ora invisibile.");
        } else {
            vanished.remove(target.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) if (!viewer.equals(target)) viewer.showPlayer(plugin, target);
            msg(sender, ChatColor.GREEN + target.getName() + " è di nuovo visibile.");
        }
    }

    private boolean isPlayerName(String raw) {
        return Bukkit.getPlayerExact(raw) != null;
    }

    private void require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) throw new IllegalArgumentException("Non hai il permesso " + permission + ".");
    }

    private Player playerSender(CommandSender sender) {
        if (sender instanceof Player p) return p;
        throw new IllegalArgumentException("Devi specificare un giocatore quando esegui questo comando dalla console.");
    }

    private Player findPlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("Giocatore non trovato: " + name);
        return player;
    }

    private GameMode parseGameMode(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "0", "survival", "s" -> GameMode.SURVIVAL;
            case "1", "creative", "c" -> GameMode.CREATIVE;
            case "2", "adventure", "a" -> GameMode.ADVENTURE;
            case "3", "spectator", "sp" -> GameMode.SPECTATOR;
            default -> throw new IllegalArgumentException("Gamemode non valido.");
        };
    }

    private double parseDouble(String raw, String name) {
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Valore " + name + " non valido: " + raw); }
    }

    private int parseInt(String raw, String name) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Valore " + name + " non valido: " + raw); }
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String root = command.getName().toLowerCase(Locale.ROOT);
        if (root.equals("gamemode") && args.length == 1) return partial(args[0], List.of("survival", "creative", "adventure", "spectator"));
        if (Set.of("fly", "heal", "feed", "clear", "kill", "vanish", "tphere").contains(root) && args.length == 1) return playerNames(args[0]);
        if (root.equals("teleport")) {
            if (args.length == 1) return playerNames(args[0]);
            if (args.length == 2 && isPlayerName(args[0])) return playerNames(args[1]);
        }
        if (root.equals("speed")) {
            if (args.length == 1) return partial(args[0], List.of("walk", "fly"));
            if (args.length == 2) return partial(args[1], List.of("1","2","3","4","5","6","7","8","9","10"));
            if (args.length == 3) return playerNames(args[2]);
        }
        return List.of();
    }

    private List<String> playerNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) out.add(player.getName());
        }
        return out;
    }

    private List<String> partial(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    void clearVanish(Player player) {
        vanished.remove(player.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) viewer.showPlayer(plugin, player);
    }
}
