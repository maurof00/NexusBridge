package dev.crossbridge.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persists servers created through /xeonProxy server create. */
final class ManagedServerRegistry {
    record Definition(String name, String host, int port, String motd, boolean restricted) {}

    private final File file;
    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    ManagedServerRegistry(File dataFolder) {
        this.file = new File(dataFolder, "servers.properties");
    }

    void load() {
        definitions.clear();
        if (!file.isFile()) return;
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + file, ex);
        }
        Set<String> names = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("server.") && key.endsWith(".host")) names.add(key.substring(7, key.length() - 5));
        }
        for (String name : names) {
            try {
                String host = properties.getProperty("server." + name + ".host");
                int port = Integer.parseInt(properties.getProperty("server." + name + ".port", "25565"));
                String motd = properties.getProperty("server." + name + ".motd", "CrossBridge server");
                boolean restricted = Boolean.parseBoolean(properties.getProperty("server." + name + ".restricted", "false"));
                definitions.put(name.toLowerCase(Locale.ROOT), new Definition(name, host, port, motd, restricted));
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Invalid managed server entry '" + name + "'", ex);
            }
        }
    }

    void save() {
        file.getParentFile().mkdirs();
        Properties properties = new Properties();
        List<Definition> values = new ArrayList<>(definitions.values());
        values.sort(Comparator.comparing(Definition::name, String.CASE_INSENSITIVE_ORDER));
        for (Definition d : values) {
            String prefix = "server." + d.name() + ".";
            properties.setProperty(prefix + "host", d.host());
            properties.setProperty(prefix + "port", Integer.toString(d.port()));
            properties.setProperty(prefix + "motd", d.motd());
            properties.setProperty(prefix + "restricted", Boolean.toString(d.restricted()));
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            properties.store(new OutputStreamWriter(out, StandardCharsets.UTF_8), "XeonProxy managed servers");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write " + tmp, ex);
        }
        if (!tmp.renameTo(file)) {
            if (!tmp.delete()) { /* best effort */ }
            throw new IllegalStateException("Could not replace " + file);
        }
    }

    Collection<Definition> definitions() {
        return List.copyOf(definitions.values());
    }

    Definition get(String name) { return definitions.get(name.toLowerCase(Locale.ROOT)); }

    boolean create(Definition definition) {
        if (definitions.putIfAbsent(definition.name().toLowerCase(Locale.ROOT), definition) != null) return false;
        save();
        return true;
    }

    boolean delete(String name) {
        if (definitions.remove(name.toLowerCase(Locale.ROOT)) == null) return false;
        save();
        return true;
    }

    ServerInfo materialize(Definition definition) {
        return ProxyServer.getInstance().constructServerInfo(
                definition.name(),
                InetSocketAddress.createUnresolved(definition.host(), definition.port()),
                definition.motd(),
                definition.restricted());
    }
}
