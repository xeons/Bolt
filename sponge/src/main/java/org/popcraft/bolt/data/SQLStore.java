package org.popcraft.bolt.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.popcraft.bolt.access.AccessList;
import org.popcraft.bolt.data.sql.Statements;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.util.BlockLocation;
import org.popcraft.bolt.util.Group;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;

/**
 * JDBC-backed {@link Store} for the Sponge module. Ported from the Bukkit implementation and
 * lowered to Java 8; bStats metrics are dropped. Writes are buffered and flushed on a
 * single-thread executor every 30 seconds. Supports SQLite (bundled driver) and MySQL (driver
 * must be provided on the server classpath).
 */
public class SQLStore implements Store {
    private static final Gson GSON = new Gson();
    private static final Type ACCESS_LIST_TYPE = new TypeToken<HashMap<String, String>>() {
    }.getType();
    private static final Type PLAYER_LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Map<UUID, BlockProtection> saveBlocks = new HashMap<>();
    private final Map<UUID, BlockProtection> removeBlocks = new HashMap<>();
    private final Map<UUID, EntityProtection> saveEntities = new HashMap<>();
    private final Map<UUID, EntityProtection> removeEntities = new HashMap<>();
    private final Map<String, Group> saveGroups = new HashMap<>();
    private final Map<String, Group> removeGroups = new HashMap<>();
    private final Map<UUID, AccessList> saveAccessLists = new HashMap<>();
    private final Map<UUID, AccessList> removeAccessLists = new HashMap<>();
    private final Configuration configuration;
    private final String connectionUrl;
    private Connection connection;

    public SQLStore(final Configuration configuration) {
        this.configuration = configuration;
        final boolean usingMySQL = "mysql".equals(configuration.type());
        if (!usingMySQL) {
            try {
                // Ensure the bundled driver is registered even if ServiceLoader discovery fails.
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            final Path parent = Paths.get(".").resolve(configuration.path()).getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        this.connectionUrl = usingMySQL
                ? String.format("jdbc:mysql://%s/%s", configuration.hostname(), configuration.database())
                : String.format("jdbc:sqlite:%s", configuration.path());
        reconnect();
        try (final PreparedStatement createBlocksTable = connection.prepareStatement(String.format(Statements.CREATE_TABLE_BLOCKS.get(configuration.type()), configuration.prefix()));
             final PreparedStatement createEntitiesTable = connection.prepareStatement(String.format(Statements.CREATE_TABLE_ENTITIES.get(configuration.type()), configuration.prefix()));
             final PreparedStatement createGroupsTable = connection.prepareStatement(String.format(Statements.CREATE_TABLE_GROUPS.get(configuration.type()), configuration.prefix()));
             final PreparedStatement createAccessTable = connection.prepareStatement(String.format(Statements.CREATE_TABLE_ACCESS.get(configuration.type()), configuration.prefix()))) {
            createBlocksTable.execute();
            createEntitiesTable.execute();
            createGroupsTable.execute();
            createAccessTable.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (!usingMySQL) {
            try (final PreparedStatement createBlocksOwnerIndex = connection.prepareStatement(String.format(Statements.CREATE_INDEX_BLOCK_OWNER.get(configuration.type()), configuration.prefix()));
                 final PreparedStatement createBlocksLocationIndex = connection.prepareStatement(String.format(Statements.CREATE_INDEX_BLOCK_LOCATION.get(configuration.type()), configuration.prefix()));
                 final PreparedStatement createEntitiesOwnerIndex = connection.prepareStatement(String.format(Statements.CREATE_INDEX_ENTITY_OWNER.get(configuration.type()), configuration.prefix()));
                 final PreparedStatement createGroupsOwnerIndex = connection.prepareStatement(String.format(Statements.CREATE_INDEX_GROUP_OWNER.get(configuration.type()), configuration.prefix()))) {
                createBlocksOwnerIndex.execute();
                createBlocksLocationIndex.execute();
                createEntitiesOwnerIndex.execute();
                createGroupsOwnerIndex.execute();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        executor.scheduleWithFixedDelay(this::flush, 30, 30, TimeUnit.SECONDS);
        if (usingMySQL) {
            executor.scheduleWithFixedDelay(this::reconnect, 30, 30, TimeUnit.MINUTES);
        }
    }

    public static final class Configuration {
        private final String type;
        private final String path;
        private final String hostname;
        private final String database;
        private final String username;
        private final String password;
        private final String prefix;
        private final Map<String, String> properties;

        public Configuration(final String type, final String path, final String hostname, final String database,
                             final String username, final String password, final String prefix,
                             final Map<String, String> properties) {
            this.type = type;
            this.path = path;
            this.hostname = hostname;
            this.database = database;
            this.username = username;
            this.password = password;
            this.prefix = prefix;
            this.properties = properties;
        }

        public String type() {
            return type;
        }

        public String path() {
            return path;
        }

        public String hostname() {
            return hostname;
        }

        public String database() {
            return database;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public String prefix() {
            return prefix;
        }

        public Map<String, String> properties() {
            return properties;
        }
    }

    private void reconnect() {
        try {
            if (connection != null) {
                connection.close();
            }
            connection = DriverManager.getConnection(connectionUrl, configuration.username(), configuration.password());
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        this.executor.shutdown();
        try {
            if (this.connection != null) {
                this.connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<BlockProtection> loadBlockProtection(BlockLocation location) {
        final CompletableFuture<BlockProtection> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectBlock = connection.prepareStatement(String.format(Statements.SELECT_BLOCK_BY_LOCATION.get(configuration.type()), configuration.prefix()))) {
                selectBlock.setString(1, location.world());
                selectBlock.setInt(2, location.x());
                selectBlock.setInt(3, location.y());
                selectBlock.setInt(4, location.z());
                final ResultSet blockResultSet = selectBlock.executeQuery();
                if (blockResultSet.next()) {
                    future.complete(blockProtectionFromResultSet(blockResultSet));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(null);
        }, executor);
        return future;
    }

    @Override
    public CompletableFuture<Collection<BlockProtection>> loadBlockProtections() {
        final CompletableFuture<Collection<BlockProtection>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            final long startTimeNanos = System.nanoTime();
            final long[] count = new long[1];
            try (final PreparedStatement selectBlocks = connection.prepareStatement(String.format(Statements.SELECT_ALL_BLOCKS.get(configuration.type()), configuration.prefix()))) {
                final ResultSet blocksResultSet = selectBlocks.executeQuery();
                final List<BlockProtection> protections = new ArrayList<>();
                while (blocksResultSet.next()) {
                    protections.add(blockProtectionFromResultSet(blocksResultSet));
                    ++count[0];
                }
                final long timeNanos = System.nanoTime() - startTimeNanos;
                final double timeMillis = timeNanos / 1e6d;
                LogManager.getLogManager().getLogger("").info(() -> String.format("Loaded %d block protections in %.3f ms", count[0], timeMillis));
                future.complete(protections);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(Collections.emptyList());
        }, executor);
        return future;
    }

    private BlockProtection blockProtectionFromResultSet(final ResultSet resultSet) throws SQLException {
        final String id = resultSet.getString(1);
        final String owner = resultSet.getString(2);
        final String type = resultSet.getString(3);
        final long created = resultSet.getLong(4);
        final long accessed = resultSet.getLong(5);
        final String accessText = resultSet.getString(6);
        final Map<String, String> access = fromJsonMap(accessText);
        final String world = resultSet.getString(7);
        final int x = resultSet.getInt(8);
        final int y = resultSet.getInt(9);
        final int z = resultSet.getInt(10);
        final String block = resultSet.getString(11);
        return new BlockProtection(UUID.fromString(id), UUID.fromString(owner), type, created, accessed, access, world, x, y, z, block);
    }

    @Override
    public void saveBlockProtection(BlockProtection protection) {
        CompletableFuture.runAsync(() -> saveBlocks.put(protection.getId(), protection), executor);
    }

    private void saveBlockProtectionNow(BlockProtection protection) {
        try (final PreparedStatement replaceBlock = connection.prepareStatement(String.format(Statements.REPLACE_BLOCK.get(configuration.type()), configuration.prefix()))) {
            replaceBlock.setString(1, protection.getId().toString());
            replaceBlock.setString(2, protection.getOwner().toString());
            replaceBlock.setString(3, protection.getType());
            replaceBlock.setLong(4, protection.getCreated());
            replaceBlock.setLong(5, protection.getAccessed());
            replaceBlock.setString(6, GSON.toJson(protection.getAccess(), ACCESS_LIST_TYPE));
            replaceBlock.setString(7, protection.getWorld());
            replaceBlock.setInt(8, protection.getX());
            replaceBlock.setInt(9, protection.getY());
            replaceBlock.setInt(10, protection.getZ());
            replaceBlock.setString(11, protection.getBlock());
            replaceBlock.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeBlockProtection(BlockProtection protection) {
        CompletableFuture.runAsync(() -> {
            final UUID id = protection.getId();
            saveBlocks.remove(id);
            removeBlocks.put(id, protection);
        }, executor);
    }

    private void removeBlockProtectionNow(BlockProtection protection) {
        try (final PreparedStatement deleteBlock = connection.prepareStatement(String.format(Statements.DELETE_BLOCK.get(configuration.type()), configuration.prefix()))) {
            deleteBlock.setString(1, protection.getId().toString());
            deleteBlock.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<EntityProtection> loadEntityProtection(UUID id) {
        final CompletableFuture<EntityProtection> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectEntity = connection.prepareStatement(String.format(Statements.SELECT_ENTITY_BY_UUID.get(configuration.type()), configuration.prefix()))) {
                selectEntity.setString(1, id.toString());
                final ResultSet entityResultSet = selectEntity.executeQuery();
                if (entityResultSet.next()) {
                    future.complete(entityProtectionFromResultSet(entityResultSet));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(null);
        }, executor);
        return future;
    }

    @Override
    public CompletableFuture<Collection<EntityProtection>> loadEntityProtections() {
        final CompletableFuture<Collection<EntityProtection>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            final long startTimeNanos = System.nanoTime();
            final long[] count = new long[1];
            try (final PreparedStatement selectEntities = connection.prepareStatement(String.format(Statements.SELECT_ALL_ENTITIES.get(configuration.type()), configuration.prefix()))) {
                final ResultSet entitiesResultSet = selectEntities.executeQuery();
                final List<EntityProtection> protections = new ArrayList<>();
                while (entitiesResultSet.next()) {
                    protections.add(entityProtectionFromResultSet(entitiesResultSet));
                    ++count[0];
                }
                final long timeNanos = System.nanoTime() - startTimeNanos;
                final double timeMillis = timeNanos / 1e6d;
                LogManager.getLogManager().getLogger("").info(() -> String.format("Loaded %d entity protections in %.3f ms", count[0], timeMillis));
                future.complete(protections);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(Collections.emptyList());
        }, executor);
        return future;
    }

    private EntityProtection entityProtectionFromResultSet(final ResultSet resultSet) throws SQLException {
        final String id = resultSet.getString(1);
        final String owner = resultSet.getString(2);
        final String type = resultSet.getString(3);
        final long created = resultSet.getLong(4);
        final long accessed = resultSet.getLong(5);
        final String accessText = resultSet.getString(6);
        final Map<String, String> access = fromJsonMap(accessText);
        final String entity = resultSet.getString(7);
        return new EntityProtection(UUID.fromString(id), UUID.fromString(owner), type, created, accessed, access, entity);
    }

    @Override
    public void saveEntityProtection(EntityProtection protection) {
        CompletableFuture.runAsync(() -> saveEntities.put(protection.getId(), protection), executor);
    }

    private void saveEntityProtectionNow(EntityProtection protection) {
        try (final PreparedStatement replaceEntity = connection.prepareStatement(String.format(Statements.REPLACE_ENTITY.get(configuration.type()), configuration.prefix()))) {
            replaceEntity.setString(1, protection.getId().toString());
            replaceEntity.setString(2, protection.getOwner().toString());
            replaceEntity.setString(3, protection.getType());
            replaceEntity.setLong(4, protection.getCreated());
            replaceEntity.setLong(5, protection.getAccessed());
            replaceEntity.setString(6, GSON.toJson(protection.getAccess(), ACCESS_LIST_TYPE));
            replaceEntity.setString(7, protection.getEntity());
            replaceEntity.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeEntityProtection(EntityProtection protection) {
        CompletableFuture.runAsync(() -> {
            saveEntities.remove(protection.getId());
            removeEntities.put(protection.getId(), protection);
        }, executor);
    }

    private void removeEntityProtectionNow(EntityProtection protection) {
        try (final PreparedStatement deleteEntity = connection.prepareStatement(String.format(Statements.DELETE_ENTITY.get(configuration.type()), configuration.prefix()))) {
            deleteEntity.setString(1, protection.getId().toString());
            deleteEntity.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<Group> loadGroup(String group) {
        final CompletableFuture<Group> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectGroup = connection.prepareStatement(String.format(Statements.SELECT_GROUP_BY_NAME.get(configuration.type()), configuration.prefix()))) {
                selectGroup.setString(1, group);
                final ResultSet groupResultSet = selectGroup.executeQuery();
                if (groupResultSet.next()) {
                    future.complete(groupFromResultSet(groupResultSet));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(null);
        }, executor);
        return future;
    }

    @Override
    public CompletableFuture<Collection<Group>> loadGroups() {
        final CompletableFuture<Collection<Group>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectGroups = connection.prepareStatement(String.format(Statements.SELECT_ALL_GROUPS.get(configuration.type()), configuration.prefix()))) {
                final ResultSet groupResultSet = selectGroups.executeQuery();
                final List<Group> groups = new ArrayList<>();
                while (groupResultSet.next()) {
                    groups.add(groupFromResultSet(groupResultSet));
                }
                future.complete(groups);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(Collections.emptyList());
        }, executor);
        return future;
    }

    private Group groupFromResultSet(final ResultSet resultSet) throws SQLException {
        final String name = resultSet.getString(1);
        final String owner = resultSet.getString(2);
        final String membersText = resultSet.getString(3);
        List<String> membersRaw = GSON.fromJson(membersText, PLAYER_LIST_TYPE);
        if (membersRaw == null) {
            membersRaw = new ArrayList<>();
        }
        final List<UUID> members = new ArrayList<>();
        membersRaw.forEach(memberRaw -> members.add(UUID.fromString(memberRaw)));
        return new Group(name, UUID.fromString(owner), members);
    }

    @Override
    public void saveGroup(Group group) {
        CompletableFuture.runAsync(() -> saveGroups.put(group.getName(), group), executor);
    }

    private void saveGroupNow(Group group) {
        try (final PreparedStatement replaceGroup = connection.prepareStatement(String.format(Statements.REPLACE_GROUP.get(configuration.type()), configuration.prefix()))) {
            replaceGroup.setString(1, group.getName());
            replaceGroup.setString(2, group.getOwner().toString());
            replaceGroup.setString(3, GSON.toJson(group.getMembers(), PLAYER_LIST_TYPE));
            replaceGroup.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeGroup(Group group) {
        CompletableFuture.runAsync(() -> removeGroups.put(group.getName(), group), executor);
    }

    private void removeGroupNow(Group group) {
        try (final PreparedStatement deleteGroup = connection.prepareStatement(String.format(Statements.DELETE_GROUP.get(configuration.type()), configuration.prefix()))) {
            deleteGroup.setString(1, group.getName());
            deleteGroup.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<AccessList> loadAccessList(UUID owner) {
        final CompletableFuture<AccessList> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectAccessList = connection.prepareStatement(String.format(Statements.SELECT_ACCESS_LIST_BY_UUID.get(configuration.type()), configuration.prefix()))) {
                selectAccessList.setString(1, owner.toString());
                final ResultSet accessListResultSet = selectAccessList.executeQuery();
                if (accessListResultSet.next()) {
                    future.complete(accessListFromResultSet(accessListResultSet));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(null);
        }, executor);
        return future;
    }

    @Override
    public CompletableFuture<Collection<AccessList>> loadAccessLists() {
        final CompletableFuture<Collection<AccessList>> future = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try (final PreparedStatement selectAccessLists = connection.prepareStatement(String.format(Statements.SELECT_ALL_ACCESS_LISTS.get(configuration.type()), configuration.prefix()))) {
                final ResultSet accessListsResultSet = selectAccessLists.executeQuery();
                final List<AccessList> accessLists = new ArrayList<>();
                while (accessListsResultSet.next()) {
                    accessLists.add(accessListFromResultSet(accessListsResultSet));
                }
                future.complete(accessLists);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            future.complete(Collections.emptyList());
        }, executor);
        return future;
    }

    private AccessList accessListFromResultSet(final ResultSet resultSet) throws SQLException {
        final String owner = resultSet.getString(1);
        final String accessListText = resultSet.getString(2);
        final Map<String, String> access = fromJsonMap(accessListText);
        return new AccessList(UUID.fromString(owner), access);
    }

    @Override
    public void saveAccessList(AccessList accessList) {
        CompletableFuture.runAsync(() -> saveAccessLists.put(accessList.getOwner(), accessList), executor);
    }

    private void saveAccessListNow(AccessList accessList) {
        try (final PreparedStatement replaceAccessList = connection.prepareStatement(String.format(Statements.REPLACE_ACCESS_LIST.get(configuration.type()), configuration.prefix()))) {
            replaceAccessList.setString(1, accessList.getOwner().toString());
            replaceAccessList.setString(2, GSON.toJson(accessList.getAccess(), ACCESS_LIST_TYPE));
            replaceAccessList.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void removeAccessList(AccessList accessList) {
        CompletableFuture.runAsync(() -> removeAccessLists.put(accessList.getOwner(), accessList), executor);
    }

    private void removeAccessListNow(AccessList accessList) {
        try (final PreparedStatement deleteAccessList = connection.prepareStatement(String.format(Statements.DELETE_ACCESS_LIST.get(configuration.type()), configuration.prefix()))) {
            deleteAccessList.setString(1, accessList.getOwner().toString());
            deleteAccessList.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fromJsonMap(final String json) {
        final Map<String, String> map = GSON.fromJson(json, ACCESS_LIST_TYPE);
        return map == null ? new HashMap<>() : map;
    }

    @Override
    public long pendingSave() {
        return CompletableFuture.supplyAsync(() -> saveBlocks.size() + removeBlocks.size() + saveEntities.size() + removeEntities.size(), executor).join();
    }

    @Override
    public CompletableFuture<Void> flush() {
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                flushMap(saveBlocks.values().iterator(), this::saveBlockProtectionNow);
                flushMap(removeBlocks.values().iterator(), this::removeBlockProtectionNow);
                flushMap(saveEntities.values().iterator(), this::saveEntityProtectionNow);
                flushMap(removeEntities.values().iterator(), this::removeEntityProtectionNow);
                flushMap(saveGroups.values().iterator(), this::saveGroupNow);
                flushMap(removeGroups.values().iterator(), this::removeGroupNow);
                flushMap(saveAccessLists.values().iterator(), this::saveAccessListNow);
                flushMap(removeAccessLists.values().iterator(), this::removeAccessListNow);
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                completionFuture.complete(null);
            }
        }, executor);
        return completionFuture;
    }

    private <T> void flushMap(final Iterator<T> iterator, final java.util.function.Consumer<T> writer) throws SQLException {
        if (!iterator.hasNext()) {
            return;
        }
        connection.setAutoCommit(false);
        while (iterator.hasNext()) {
            writer.accept(iterator.next());
            iterator.remove();
        }
        connection.setAutoCommit(true);
    }
}
