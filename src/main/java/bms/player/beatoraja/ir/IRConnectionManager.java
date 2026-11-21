package bms.player.beatoraja.ir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import bms.player.beatoraja.AppPaths;

/**
 * Loads IR implementations from plugin archives placed in the game data directory.
 *
 * <p>Plugins are Java archives whose classes have been converted to DEX at build time. They are loaded
 * in-process: a plugin class is instantiated through {@link IRConnection} and used directly, so the
 * request/response contract stays synchronous as documented by the interface.</p>
 *
 * <p>TODO(unimplemented): isolated IR worker processes are not available; a crashing or misbehaving
 * plugin therefore affects the game process.</p>
 */
public class IRConnectionManager {

    private static final Logger LOG = Logger.getGlobal();
    private static final String PLUGIN_DIRECTORY = "ir";
    private static final String CLASS_INDEX = "META-INF/beatoraja-ir/classes.txt";

    private record Plugin(String name, String home, String fileName, Class<?> type) {
    }

    private static volatile List<Plugin> plugins;

    private IRConnectionManager() {
    }

    /** Available IR connection names in plugin order. */
    public static String[] getAllAvailableIRConnectionName() {
        List<Plugin> loaded = plugins();
        String[] names = new String[loaded.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = loaded.get(i).name();
        }
        return names;
    }

    /** Returns a new connection instance for the given IR name, or null when it is unavailable. */
    public static IRConnection getIRConnection(String name) {
        Plugin plugin = find(name);
        if (plugin == null) return null;
        try {
            Constructor<?> constructor = plugin.type().getDeclaredConstructor();
            constructor.setAccessible(true);
            return (IRConnection) constructor.newInstance();
        } catch (Throwable e) {
            LOG.warning("IR connection instantiation failed : " + name + " (" + e + ")");
            return null;
        }
    }

    /** Returns the IR home page for the given IR name, or null. */
    public static String getHomeURL(String name) {
        Plugin plugin = find(name);
        return plugin != null ? plugin.home() : null;
    }

    public static boolean hasIRConnection(String name) {
        return find(name) != null;
    }

    /** Diagnostics: "name1,name2" or "none". */
    public static String describeAvailableConnections() {
        String[] names = getAllAvailableIRConnectionName();
        return names.length == 0 ? "none" : join(names);
    }

    /** Name/home pairs for configuration screens. */
    public static Map<String, String> getHomes() {
        Map<String, String> homes = new LinkedHashMap<>();
        for (Plugin plugin : plugins()) {
            homes.put(plugin.name(), plugin.home());
        }
        return homes;
    }

    private static Plugin find(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Plugin plugin : plugins()) {
            if (name.equals(plugin.name())) return plugin;
        }
        return null;
    }

    private static List<Plugin> plugins() {
        List<Plugin> snapshot = plugins;
        if (snapshot != null) return snapshot;
        synchronized (IRConnectionManager.class) {
            if (plugins != null) return plugins;
            plugins = loadPlugins();
            return plugins;
        }
    }

    private static List<Plugin> loadPlugins() {
        final List<Plugin> result = new ArrayList<>();
        final Path directory = AppPaths.path(PLUGIN_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            LOG.info("IR plugins : none (directory " + directory + " does not exist)");
            return result;
        }
        final Path dexCache = AppPaths.path(PLUGIN_DIRECTORY, "dex-cache");
        clearDirectory(dexCache);
        try {
            Files.createDirectories(dexCache);
        } catch (IOException e) {
            LOG.warning("IR plugin cache directory cannot be created: " + e.getMessage());
            return result;
        }
        List<Path> archives = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(archives::add);
        } catch (IOException e) {
            LOG.warning("IR plugin directory cannot be listed: " + e.getMessage());
            return result;
        }
        for (Path archive : archives) {
            try {
                loadArchive(archive, dexCache, result);
            } catch (Throwable e) {
                LOG.warning("IR plugin cannot be loaded : " + archive.getFileName() + " (" + e + ")");
            }
        }
        if (result.isEmpty()) {
            LOG.info("IR plugins : none available");
        } else {
            for (Plugin plugin : result) {
                LOG.info("IR plugin loaded : " + plugin.name() + " (" + plugin.fileName() + ")");
            }
        }
        writePluginMetadata(result);
        return result;
    }

    /** Publishes the discovered plugins so the start screen can offer their accounts. */
    private static void writePluginMetadata(List<Plugin> plugins) {
        try {
            final org.json.JSONArray array = new org.json.JSONArray();
            for (Plugin plugin : plugins) {
                final org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("name", plugin.name());
                if (plugin.home() != null) entry.put("home", plugin.home());
                entry.put("file", plugin.fileName());
                array.put(entry);
            }
            final org.json.JSONObject root = new org.json.JSONObject();
            root.put("plugins", array);
            final Path file = AppPaths.path(PLUGIN_DIRECTORY, "plugins.json");
            Files.createDirectories(file.getParent());
            Files.write(file, root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable e) {
            LOG.warning("IR plugin metadata cannot be written: " + e);
        }
    }

    private static void loadArchive(Path archive, Path dexCache, List<Plugin> result) throws Exception {
        final List<String> classNames = readClassIndex(archive);
        if (classNames.isEmpty()) {
            LOG.warning("IR plugin has no class index (was it prepared for this build?) : " + archive.getFileName());
            return;
        }
        final ClassLoader parent = IRConnection.class.getClassLoader();
        final ClassLoader loader = new dalvik.system.DexClassLoader(archive.toString(), dexCache.toString(),
                null, parent);
        for (String className : classNames) {
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (Throwable e) {
                LOG.warning("IR plugin class cannot be loaded : " + className + " (" + e + ")");
                continue;
            }
            if (!IRConnection.class.isAssignableFrom(type)
                    || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            String name = readStaticString(type, "NAME");
            if (name == null || name.trim().isEmpty()) name = type.getSimpleName();
            String home = readStaticString(type, "HOME");
            result.add(new Plugin(name, home, archive.getFileName().toString(), type));
        }
    }

    private static List<String> readClassIndex(Path archive) throws IOException {
        final List<String> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry index = zip.getEntry(CLASS_INDEX);
            if (index == null) return classes;
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(index)) {
                byte[] chunk = new byte[4096];
                for (int n; (n = input.read(chunk)) != -1; ) buffer.write(chunk, 0, n);
            }
            for (String line : new String(buffer.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (!line.trim().isEmpty()) classes.add(line.trim());
            }
        }
        return classes;
    }

    private static String readStaticString(Class<?> type, String fieldName) {
        try {
            var field = type.getField(fieldName);
            Object value = field.get(null);
            return value != null ? value.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearDirectory(Path directory) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String join(String[] values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }
}
