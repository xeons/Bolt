package org.popcraft.bolt;

import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityType;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppingServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.popcraft.bolt.access.Access;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.access.AccessRegistry;
import org.popcraft.bolt.access.DefaultAccess;
import org.popcraft.bolt.command.BoltCommands;
import org.popcraft.bolt.data.MemoryStore;
import org.popcraft.bolt.data.SQLStore;
import org.popcraft.bolt.data.SimpleProtectionCache;
import org.popcraft.bolt.data.Store;
import org.popcraft.bolt.lang.Translator;
import org.popcraft.bolt.listeners.BoltBlockListener;
import org.popcraft.bolt.listeners.BoltEntityListener;
import org.popcraft.bolt.listeners.BoltPlayerListener;
import org.popcraft.bolt.matcher.Matchers;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;
import org.popcraft.bolt.source.Source;
import org.popcraft.bolt.source.SourceResolver;
import org.popcraft.bolt.source.SourceTypes;
import org.popcraft.bolt.util.BoltPlayer;
import org.popcraft.bolt.util.BlockLocation;
import org.popcraft.bolt.util.Group;
import org.popcraft.bolt.util.Mode;
import org.popcraft.bolt.util.ProtectableConfig;
import org.popcraft.bolt.util.SpongePlayerResolver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

@Plugin(id = "bolt", name = "Bolt", version = "1.2", description = "Modern protection solution for individual blocks and entities")
public class BoltPlugin {
    private static final Source ADMIN_PERMISSION_SOURCE = Source.of(SourceTypes.PERMISSION, "bolt.admin");
    private static final Source MOD_PERMISSION_SOURCE = Source.of(SourceTypes.PERMISSION, "bolt.mod");

    // Default protectable catalog ids (Minecraft 1.12.2), written to the config if absent. Users
    // can add/remove entries and set autoProtect per block/entity.
    private static final String[] DEFAULT_CONTAINER_BLOCKS = {
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:furnace", "minecraft:lit_furnace",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:hopper", "minecraft:brewing_stand",
            "minecraft:beacon"
    };
    private static final String[] DEFAULT_LOCKABLE_BLOCKS = {
            "minecraft:wooden_door", "minecraft:spruce_door", "minecraft:birch_door", "minecraft:jungle_door",
            "minecraft:acacia_door", "minecraft:dark_oak_door", "minecraft:iron_door", "minecraft:trapdoor",
            "minecraft:iron_trapdoor", "minecraft:standing_sign", "minecraft:wall_sign"
    };
    private static final String[] DEFAULT_PRIVATE_ENTITIES = {
            "minecraft:item_frame", "minecraft:armor_stand", "minecraft:painting", "minecraft:chest_minecart",
            "minecraft:hopper_minecart", "minecraft:furnace_minecart", "minecraft:leash_knot"
    };

    @Inject
    private Logger logger;
    @Inject
    private PluginContainer container;
    @Inject
    @ConfigDir(sharedRoot = false)
    private Path configDir;
    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configLoader;

    private final Map<BlockType, ProtectableConfig> protectableBlocks = new HashMap<>();
    private final Map<EntityType, ProtectableConfig> protectableEntities = new HashMap<>();
    private String defaultProtectionType = "private";
    private String defaultAccessType = "normal";
    private boolean useActionBar = false;
    private String language = "en";
    private boolean perPlayerLocale = true;
    private Bolt bolt;
    private SQLStore sqlStore;

    @Listener
    public void onInitialization(final GameInitializationEvent event) {
        final CommentedConfigurationNode root = loadConfig();
        applySettings(root);
        this.bolt = new Bolt(createStore(root));
        loadTranslations();
        registerTypes(root);
        registerProtectables(root);
        saveConfig(root);
        Sponge.getEventManager().registerListeners(this, new BoltBlockListener(this));
        Sponge.getEventManager().registerListeners(this, new BoltEntityListener(this));
        Sponge.getEventManager().registerListeners(this, new BoltPlayerListener(this));
        BoltCommands.register(this);
    }

    @Listener
    public void onServerStopping(final GameStoppingServerEvent event) {
        if (bolt != null && bolt.getStore() != null) {
            bolt.getStore().flush().join();
        }
        if (sqlStore != null) {
            sqlStore.close();
        }
    }

    private CommentedConfigurationNode loadConfig() {
        try {
            return configLoader.load();
        } catch (IOException e) {
            logger.warn("Failed to load config, using defaults: " + e.getMessage());
            return configLoader.createEmptyNode();
        }
    }

    private void saveConfig(final CommentedConfigurationNode root) {
        try {
            configLoader.save(root);
        } catch (IOException e) {
            logger.warn("Failed to save config: " + e.getMessage());
        }
    }

    private void applySettings(final CommentedConfigurationNode root) {
        final CommentedConfigurationNode settings = root.getNode("settings");
        useActionBar = settings.getNode("use-action-bar").getBoolean(false);
        settings.getNode("use-action-bar").setValue(useActionBar);
        language = settings.getNode("language").getString("en");
        settings.getNode("language").setValue(language);
        perPlayerLocale = settings.getNode("per-player-locale").getBoolean(true);
        settings.getNode("per-player-locale").setValue(perPlayerLocale);
        // Per-server secret mixed into password hashes; generated once and persisted.
        String salt = settings.getNode("password-salt").getString("");
        if (salt.isEmpty()) {
            salt = UUID.randomUUID().toString();
            settings.getNode("password-salt").setValue(salt);
        }
        Source.setPasswordSalt(salt);
    }

    private Store createStore(final CommentedConfigurationNode root) {
        final CommentedConfigurationNode database = root.getNode("database");
        final String type = database.getNode("type").getString("sqlite").toLowerCase();
        final String defaultPath = configDir.resolve("bolt.db").toString();
        final String path = database.getNode("path").getString(defaultPath);
        final String hostname = database.getNode("hostname").getString("");
        final String db = database.getNode("database").getString("");
        final String username = database.getNode("username").getString("");
        final String password = database.getNode("password").getString("");
        final String prefix = database.getNode("prefix").getString("");
        // Materialize defaults so users get a config file to edit.
        database.getNode("type").setValue(type);
        database.getNode("path").setValue(path);
        database.getNode("hostname").setValue(hostname);
        database.getNode("database").setValue(db);
        database.getNode("username").setValue(username);
        database.getNode("password").setValue(password);
        database.getNode("prefix").setValue(prefix);

        if ("none".equals(type) || "memory".equals(type)) {
            logger.info("Bolt storage: in-memory (non-persistent).");
            return new MemoryStore();
        }
        final SQLStore.Configuration configuration = new SQLStore.Configuration(type, path, hostname, db, username, password, prefix, new HashMap<>());
        this.sqlStore = new SQLStore(configuration);
        logger.info("Bolt storage: " + type + (("sqlite".equals(type)) ? " (" + path + ")" : " (" + hostname + "/" + db + ")"));
        return new SimpleProtectionCache(sqlStore);
    }

    /** Reloads config-driven settings, types, protectables, and translations (used by the admin reload command). */
    public void reload() {
        final CommentedConfigurationNode root = loadConfig();
        applySettings(root);
        registerTypes(root);
        registerProtectables(root);
        loadTranslations();
        saveConfig(root);
    }

    private void loadTranslations() {
        try {
            final Path langDir = configDir.resolve("lang");
            Files.createDirectories(langDir);
            // Loads all bundled languages plus any user overrides in the lang directory. Guarded
            // broadly: the bundled-jar filesystem walk can throw unchecked on some classloaders, in
            // which case Bolt falls back to the statically-loaded English strings.
            Translator.loadAllTranslations(langDir, language, perPlayerLocale);
        } catch (Throwable t) {
            logger.warn("Failed to load translations, falling back to English: " + t.getMessage());
        }
    }

    private void registerTypes(final CommentedConfigurationNode root) {
        final AccessRegistry accessRegistry = bolt.getAccessRegistry();
        accessRegistry.unregisterAll();

        final CommentedConfigurationNode protections = root.getNode("protections");
        if (protections.isVirtual() || protections.getChildrenMap().isEmpty()) {
            writeAccessType(protections, "private", true, false, "redstone");
            writeAccessType(protections, "display", false, false, "redstone", "interact", "open");
            writeAccessType(protections, "deposit", false, false, "redstone", "interact", "open", "deposit");
            writeAccessType(protections, "withdrawal", false, false, "redstone", "interact", "open", "withdraw");
            writeAccessType(protections, "public", false, false, "redstone", "interact", "open", "deposit", "withdraw", "mount");
        }
        for (final Map.Entry<Object, ? extends CommentedConfigurationNode> entry : protections.getChildrenMap().entrySet()) {
            final String type = entry.getKey().toString().toLowerCase();
            final CommentedConfigurationNode node = entry.getValue();
            accessRegistry.registerProtectionType(type, node.getNode("require-permission").getBoolean(false),
                    new HashSet<>(node.getNode("allows").getList(Object::toString)));
            if (node.getNode("default").getBoolean(false)) {
                defaultProtectionType = type;
            }
        }

        final CommentedConfigurationNode access = root.getNode("access");
        if (access.isVirtual() || access.getChildrenMap().isEmpty()) {
            writeAccessType(access, "normal", true, false, "redstone", "interact", "open", "deposit", "withdraw", "mount");
            writeAccessType(access, "admin", false, true, "redstone", "interact", "open", "deposit", "withdraw", "mount", "edit");
        }
        for (final Map.Entry<Object, ? extends CommentedConfigurationNode> entry : access.getChildrenMap().entrySet()) {
            final String type = entry.getKey().toString().toLowerCase();
            final CommentedConfigurationNode node = entry.getValue();
            accessRegistry.registerAccessType(type, node.getNode("require-permission").getBoolean(false),
                    new HashSet<>(node.getNode("allows").getList(Object::toString)));
            if (node.getNode("default").getBoolean(false)) {
                defaultAccessType = type;
            }
        }

        final CommentedConfigurationNode sources = root.getNode("sources");
        if (sources.isVirtual() || sources.getChildrenMap().isEmpty()) {
            sources.getNode("player", "require-permission").setValue(false);
            sources.getNode("group", "require-permission").setValue(false);
            sources.getNode("password", "require-permission").setValue(false);
            sources.getNode("permission", "require-permission").setValue(true);
        }
        bolt.getSourceTypeRegistry().unregisterAll();
        for (final Map.Entry<Object, ? extends CommentedConfigurationNode> entry : sources.getChildrenMap().entrySet()) {
            final String name = entry.getKey().toString().toLowerCase();
            final CommentedConfigurationNode node = entry.getValue();
            bolt.getSourceTypeRegistry().registerSourceType(name,
                    node.getNode("require-permission").getBoolean(false),
                    node.getNode("unique").getBoolean(false));
        }
    }

    private void writeAccessType(final CommentedConfigurationNode section, final String type, final boolean isDefault,
                                 final boolean requirePermission, final String... allows) {
        if (isDefault) {
            section.getNode(type, "default").setValue(true);
        }
        section.getNode(type, "require-permission").setValue(requirePermission);
        section.getNode(type, "allows").setValue(Arrays.asList(allows));
    }

    private void registerProtectables(final CommentedConfigurationNode root) {
        protectableBlocks.clear();
        protectableEntities.clear();

        final CommentedConfigurationNode blocks = root.getNode("blocks");
        if (blocks.isVirtual() || blocks.getChildrenMap().isEmpty()) {
            for (final String id : DEFAULT_CONTAINER_BLOCKS) {
                blocks.getNode(id, "autoProtect").setValue("private");
            }
            blocks.getNode("minecraft:ender_chest", "autoProtect").setValue("public");
            for (final String id : DEFAULT_LOCKABLE_BLOCKS) {
                blocks.getNode(id, "autoProtect").setValue("false");
            }
        }
        for (final Map.Entry<Object, ? extends CommentedConfigurationNode> entry : blocks.getChildrenMap().entrySet()) {
            final String id = entry.getKey().toString();
            final Optional<BlockType> blockType = Sponge.getRegistry().getType(BlockType.class, id);
            if (!blockType.isPresent()) {
                logger.warn("Unknown block in config, skipping: " + id);
                continue;
            }
            protectableBlocks.put(blockType.get(), protectableConfig(entry.getValue()));
        }

        final CommentedConfigurationNode entities = root.getNode("entities");
        if (entities.isVirtual() || entities.getChildrenMap().isEmpty()) {
            for (final String id : DEFAULT_PRIVATE_ENTITIES) {
                entities.getNode(id, "autoProtect").setValue("private");
            }
        }
        for (final Map.Entry<Object, ? extends CommentedConfigurationNode> entry : entities.getChildrenMap().entrySet()) {
            final String id = entry.getKey().toString();
            final Optional<EntityType> entityType = Sponge.getRegistry().getType(EntityType.class, id);
            if (!entityType.isPresent()) {
                logger.warn("Unknown entity in config, skipping: " + id);
                continue;
            }
            protectableEntities.put(entityType.get(), protectableConfig(entry.getValue()));
        }
    }

    private ProtectableConfig protectableConfig(final CommentedConfigurationNode node) {
        final String autoProtect = node.getNode("autoProtect").getString("false");
        final boolean lockPermission = node.getNode("lockPermission").getBoolean(false);
        final boolean autoProtectPermission = node.getNode("autoProtectPermission").getBoolean(false);
        final Access defaultAccess = bolt.getAccessRegistry().getProtectionByType(autoProtect).orElse(null);
        return new ProtectableConfig(defaultAccess, lockPermission, autoProtectPermission);
    }

    public Bolt getBolt() {
        return bolt;
    }

    public Logger getLogger() {
        return logger;
    }

    public PluginContainer getContainer() {
        return container;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public List<String> getPlayersOwnedGroups(final Player player) {
        final List<String> names = new ArrayList<>();
        for (final Group group : bolt.getStore().loadGroups().join()) {
            if (group.getOwner().equals(player.getUniqueId())) {
                names.add(group.getName());
            }
        }
        return names;
    }

    /** Persists a player's toggled mode to {@code config/bolt/players/<uuid>.properties}. */
    public void savePlayerMode(final UUID uuid, final Mode mode, final boolean enabled) {
        final Path file = configDir.resolve("players").resolve(uuid + ".properties");
        try {
            Files.createDirectories(file.getParent());
            final Properties properties = new Properties();
            if (Files.exists(file)) {
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
            }
            properties.setProperty(mode.name().toLowerCase(), Boolean.toString(enabled));
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "Bolt player modes");
            }
        } catch (IOException e) {
            logger.warn("Failed to save player modes: " + e.getMessage());
        }
    }

    /** Restores a player's persisted modes onto their {@link BoltPlayer} (called on join). */
    public void loadPlayerModes(final UUID uuid, final BoltPlayer boltPlayer) {
        final Path file = configDir.resolve("players").resolve(uuid + ".properties");
        if (!Files.exists(file)) {
            return;
        }
        final Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException e) {
            logger.warn("Failed to load player modes: " + e.getMessage());
            return;
        }
        for (final Mode mode : Mode.values()) {
            if (Boolean.parseBoolean(properties.getProperty(mode.name().toLowerCase(), "false")) && !boltPlayer.hasMode(mode)) {
                boltPlayer.toggleMode(mode);
            }
        }
    }

    public boolean isUseActionBar() {
        return useActionBar;
    }

    public String getDefaultProtectionType() {
        return defaultProtectionType;
    }

    public String getDefaultAccessType() {
        return defaultAccessType;
    }

    public BoltPlayer player(final Player player) {
        return bolt.getBoltPlayer(player.getUniqueId());
    }

    public BoltPlayer player(final UUID uuid) {
        return bolt.getBoltPlayer(uuid);
    }

    public ProtectableConfig getProtectableConfig(final Location<World> location) {
        return protectableBlocks.get(location.getBlockType());
    }

    public ProtectableConfig getProtectableConfig(final BlockType type) {
        return protectableBlocks.get(type);
    }

    public boolean isProtectable(final BlockType type) {
        return protectableBlocks.containsKey(type);
    }

    public ProtectableConfig getProtectableConfig(final Entity entity) {
        return protectableEntities.get(entity.getType());
    }

    public boolean isProtectable(final Location<World> location) {
        return protectableBlocks.containsKey(location.getBlockType());
    }

    public boolean isProtectable(final Entity entity) {
        return protectableEntities.containsKey(entity.getType());
    }

    public boolean isProtected(final Location<World> location) {
        return findProtection(location) != null;
    }

    public boolean isProtected(final Entity entity) {
        return findProtection(entity) != null;
    }

    public BlockProtection loadProtection(final Location<World> location) {
        final BlockLocation blockLocation = new BlockLocation(location.getExtent().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return bolt.getStore().loadBlockProtection(blockLocation).join();
    }

    public EntityProtection loadProtection(final Entity entity) {
        return bolt.getStore().loadEntityProtection(entity.getUniqueId()).join();
    }

    public Protection findProtection(final Location<World> location) {
        final BlockProtection direct = loadProtection(location);
        if (direct != null) {
            return direct;
        }
        for (final Location<World> match : Matchers.expand(location)) {
            final BlockProtection matched = loadProtection(match);
            if (matched != null) {
                return matched;
            }
        }
        return null;
    }

    public Protection findProtection(final Entity entity) {
        return loadProtection(entity);
    }

    public BlockProtection createProtection(final Location<World> location, final UUID owner, final String type) {
        final long now = System.currentTimeMillis();
        return new BlockProtection(UUID.randomUUID(), owner, type, now, now, new HashMap<>(),
                location.getExtent().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                location.getBlockType().getId());
    }

    public EntityProtection createProtection(final Entity entity, final UUID owner, final String type) {
        final long now = System.currentTimeMillis();
        return new EntityProtection(entity.getUniqueId(), owner, type, now, now, new HashMap<>(), entity.getType().getId());
    }

    public void saveProtection(final Protection protection) {
        if (protection instanceof BlockProtection) {
            bolt.getStore().saveBlockProtection((BlockProtection) protection);
        } else if (protection instanceof EntityProtection) {
            bolt.getStore().saveEntityProtection((EntityProtection) protection);
        }
    }

    public void removeProtection(final Protection protection) {
        if (protection instanceof BlockProtection) {
            bolt.getStore().removeBlockProtection((BlockProtection) protection);
        } else if (protection instanceof EntityProtection) {
            bolt.getStore().removeEntityProtection((EntityProtection) protection);
        }
    }

    /** Whether the protection's type inherently grants a permission (e.g. private allows redstone). */
    public boolean protectionTypeAllows(final Protection protection, final String permission) {
        final Access access = bolt.getAccessRegistry().getProtectionByType(protection.getType()).orElse(null);
        return access != null && access.permissions().contains(permission);
    }

    public boolean canAccess(final Protection protection, final Player player, final String... permissions) {
        return canAccess(protection, player.getUniqueId(), permissions);
    }

    public boolean canAccess(final Protection protection, final UUID uuid, final String... permissions) {
        return canAccess(protection, new SpongePlayerResolver(bolt, uuid), permissions);
    }

    public boolean canAccess(final Protection protection, final SourceResolver sourceResolver, final String... permissions) {
        if (protection == null || permissions.length == 0) {
            return true;
        }
        return permissions.length == 1 ? canAccessSingle(protection, sourceResolver, permissions[0]) : canAccessMulti(protection, sourceResolver, permissions);
    }

    private boolean canAccessMulti(final Protection protection, final SourceResolver sourceResolver, final String... permissions) {
        final Set<String> unresolved = new HashSet<>(Arrays.asList(permissions));
        final Source ownerSource = Source.player(protection.getOwner());
        if (sourceResolver.resolve(ownerSource) || sourceResolver.resolve(ADMIN_PERMISSION_SOURCE)) {
            unresolved.removeAll(DefaultAccess.OWNER);
            if (unresolved.isEmpty()) {
                return true;
            }
        }
        if (sourceResolver.resolve(MOD_PERMISSION_SOURCE)) {
            unresolved.removeAll(DefaultAccess.DISPLAY);
            if (unresolved.isEmpty()) {
                return true;
            }
        }
        final AccessRegistry accessRegistry = bolt.getAccessRegistry();
        final Access protectionType = accessRegistry.getProtectionByType(protection.getType()).orElse(null);
        if (protectionType != null) {
            unresolved.removeAll(protectionType.permissions());
            if (unresolved.isEmpty()) {
                return true;
            }
        }
        for (final Map.Entry<String, String> entry : protection.getAccess().entrySet()) {
            if (sourceResolver.resolve(Source.parse(entry.getKey()))) {
                final Access accessType = accessRegistry.getAccessByType(entry.getValue()).orElse(null);
                if (accessType != null) {
                    unresolved.removeAll(accessType.permissions());
                    if (unresolved.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        final AccessList accessList = bolt.getStore().loadAccessList(protection.getOwner()).join();
        if (accessList != null) {
            for (final Map.Entry<String, String> entry : accessList.getAccess().entrySet()) {
                if (sourceResolver.resolve(Source.parse(entry.getKey()))) {
                    final Access accessType = accessRegistry.getAccessByType(entry.getValue()).orElse(null);
                    if (accessType != null) {
                        unresolved.removeAll(accessType.permissions());
                        if (unresolved.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        unresolved.removeIf(permission -> sourceResolver.resolve(Source.of(SourceTypes.PERMISSION, "bolt.permission." + permission)));
        return unresolved.isEmpty();
    }

    private boolean canAccessSingle(final Protection protection, final SourceResolver sourceResolver, final String permission) {
        final Source ownerSource = Source.player(protection.getOwner());
        if (sourceResolver.resolve(ownerSource) || sourceResolver.resolve(ADMIN_PERMISSION_SOURCE)) {
            if (DefaultAccess.OWNER.contains(permission)) {
                return true;
            }
        }
        if (sourceResolver.resolve(MOD_PERMISSION_SOURCE)) {
            if (DefaultAccess.DISPLAY.contains(permission)) {
                return true;
            }
        }
        final AccessRegistry accessRegistry = bolt.getAccessRegistry();
        final Access protectionType = accessRegistry.getProtectionByType(protection.getType()).orElse(null);
        if (protectionType != null && protectionType.permissions().contains(permission)) {
            return true;
        }
        for (final Map.Entry<String, String> entry : protection.getAccess().entrySet()) {
            if (sourceResolver.resolve(Source.parse(entry.getKey()))) {
                final Access accessType = accessRegistry.getAccessByType(entry.getValue()).orElse(null);
                if (accessType != null && accessType.permissions().contains(permission)) {
                    return true;
                }
            }
        }
        final AccessList accessList = bolt.getStore().loadAccessList(protection.getOwner()).join();
        if (accessList != null) {
            for (final Map.Entry<String, String> entry : accessList.getAccess().entrySet()) {
                if (sourceResolver.resolve(Source.parse(entry.getKey()))) {
                    final Access accessType = accessRegistry.getAccessByType(entry.getValue()).orElse(null);
                    if (accessType != null && accessType.permissions().contains(permission)) {
                        return true;
                    }
                }
            }
        }
        return sourceResolver.resolve(Source.of(SourceTypes.PERMISSION, "bolt.permission." + permission));
    }
}
