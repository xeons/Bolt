package org.popcraft.bolt;

import com.google.inject.Inject;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.EntityType;
import org.spongepowered.api.entity.EntityTypes;
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
import org.popcraft.bolt.util.ProtectableConfig;
import org.popcraft.bolt.util.SpongePlayerResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Plugin(id = "bolt", name = "Bolt", version = "1.2", description = "Modern protection solution for individual blocks and entities")
public class BoltPlugin {
    private static final Source ADMIN_PERMISSION_SOURCE = Source.of(SourceTypes.PERMISSION, "bolt.admin");
    private static final Source MOD_PERMISSION_SOURCE = Source.of(SourceTypes.PERMISSION, "bolt.mod");

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
        this.bolt = new Bolt(createStore());
        loadTranslations();
        registerTypes();
        registerProtectables();
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

    private Store createStore() {
        CommentedConfigurationNode root = null;
        try {
            root = configLoader.load();
        } catch (IOException e) {
            logger.warn("Failed to load config, using defaults: " + e.getMessage());
        }
        if (root == null) {
            logger.info("Bolt storage: in-memory (non-persistent) — config unavailable.");
            return new MemoryStore();
        }
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
        try {
            configLoader.save(root);
        } catch (IOException e) {
            logger.warn("Failed to save config: " + e.getMessage());
        }

        if ("none".equals(type) || "memory".equals(type)) {
            logger.info("Bolt storage: in-memory (non-persistent).");
            return new MemoryStore();
        }
        final SQLStore.Configuration configuration = new SQLStore.Configuration(type, path, hostname, db, username, password, prefix, new HashMap<>());
        this.sqlStore = new SQLStore(configuration);
        logger.info("Bolt storage: " + type + (("sqlite".equals(type)) ? " (" + path + ")" : " (" + hostname + "/" + db + ")"));
        return new SimpleProtectionCache(sqlStore);
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

    private void registerTypes() {
        final AccessRegistry accessRegistry = bolt.getAccessRegistry();
        accessRegistry.unregisterAll();
        accessRegistry.registerProtectionType("private", false, new HashSet<>(DefaultAccess.PRIVATE));
        accessRegistry.registerProtectionType("display", false, new HashSet<>(DefaultAccess.DISPLAY));
        accessRegistry.registerProtectionType("deposit", false, new HashSet<>(DefaultAccess.DEPOSIT));
        accessRegistry.registerProtectionType("withdrawal", false, new HashSet<>(DefaultAccess.WITHDRAWAL));
        accessRegistry.registerProtectionType("public", false, new HashSet<>(DefaultAccess.PUBLIC));
        accessRegistry.registerAccessType("normal", false, new HashSet<>(DefaultAccess.NORMAL));
        accessRegistry.registerAccessType("admin", true, new HashSet<>(DefaultAccess.ADMIN));
        bolt.getSourceTypeRegistry().unregisterAll();
        bolt.getSourceTypeRegistry().registerSourceType(SourceTypes.PLAYER, false, false);
        bolt.getSourceTypeRegistry().registerSourceType(SourceTypes.PASSWORD, false, false);
        bolt.getSourceTypeRegistry().registerSourceType(SourceTypes.GROUP, false, false);
        bolt.getSourceTypeRegistry().registerSourceType(SourceTypes.PERMISSION, true, false);
    }

    private void registerProtectables() {
        protectableBlocks.clear();
        protectableEntities.clear();
        // Auto-protected containers.
        for (final BlockType type : Arrays.asList(BlockTypes.CHEST, BlockTypes.TRAPPED_CHEST, BlockTypes.FURNACE,
                BlockTypes.LIT_FURNACE, BlockTypes.DISPENSER, BlockTypes.DROPPER, BlockTypes.HOPPER,
                BlockTypes.BREWING_STAND, BlockTypes.BEACON)) {
            protectableBlocks.put(type, protectable("private"));
        }
        protectableBlocks.put(BlockTypes.ENDER_CHEST, protectable("public"));
        // Lockable but not auto-protected (doors).
        for (final BlockType type : Arrays.asList(BlockTypes.WOODEN_DOOR, BlockTypes.SPRUCE_DOOR, BlockTypes.BIRCH_DOOR,
                BlockTypes.JUNGLE_DOOR, BlockTypes.ACACIA_DOOR, BlockTypes.DARK_OAK_DOOR, BlockTypes.IRON_DOOR,
                BlockTypes.TRAPDOOR, BlockTypes.IRON_TRAPDOOR)) {
            protectableBlocks.put(type, protectable("false"));
        }
        // Auto-protected entities.
        for (final EntityType type : Arrays.asList(EntityTypes.ITEM_FRAME, EntityTypes.ARMOR_STAND, EntityTypes.PAINTING)) {
            protectableEntities.put(type, protectable("private"));
        }
    }

    private ProtectableConfig protectable(final String autoProtectType) {
        final Access defaultAccess = bolt.getAccessRegistry().getProtectionByType(autoProtectType).orElse(null);
        return new ProtectableConfig(defaultAccess, false, false);
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
