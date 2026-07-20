package org.popcraft.bolt.access;

import org.popcraft.bolt.util.Permission;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class DefaultAccess {
    private DefaultAccess() {
    }

    private static Set<String> setOf(final String... permissions) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(permissions)));
    }

    public static final Set<String> PRIVATE = setOf(Permission.REDSTONE);
    public static final Set<String> DISPLAY = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN);
    public static final Set<String> DEPOSIT = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.DEPOSIT);
    public static final Set<String> WITHDRAWAL = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.WITHDRAW);
    public static final Set<String> PUBLIC = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.DEPOSIT, Permission.WITHDRAW, Permission.MOUNT);
    public static final Set<String> NORMAL = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.DEPOSIT, Permission.WITHDRAW, Permission.MOUNT);
    public static final Set<String> ADMIN = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.DEPOSIT, Permission.WITHDRAW, Permission.MOUNT, Permission.EDIT);
    public static final Set<String> OWNER = setOf(Permission.REDSTONE, Permission.INTERACT, Permission.OPEN, Permission.DEPOSIT, Permission.WITHDRAW, Permission.MOUNT, Permission.EDIT, Permission.DESTROY);
}
