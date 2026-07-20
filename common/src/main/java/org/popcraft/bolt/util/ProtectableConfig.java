package org.popcraft.bolt.util;

import org.popcraft.bolt.access.Access;

import java.util.Objects;

public final class ProtectableConfig {
    private final Access defaultAccess;
    private final boolean lockPermission;
    private final boolean autoProtectPermission;

    public ProtectableConfig(final Access defaultAccess, final boolean lockPermission, final boolean autoProtectPermission) {
        this.defaultAccess = defaultAccess;
        this.lockPermission = lockPermission;
        this.autoProtectPermission = autoProtectPermission;
    }

    public Access defaultAccess() {
        return defaultAccess;
    }

    public boolean lockPermission() {
        return lockPermission;
    }

    public boolean autoProtectPermission() {
        return autoProtectPermission;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProtectableConfig that = (ProtectableConfig) o;
        return lockPermission == that.lockPermission && autoProtectPermission == that.autoProtectPermission && Objects.equals(defaultAccess, that.defaultAccess);
    }

    @Override
    public int hashCode() {
        return Objects.hash(defaultAccess, lockPermission, autoProtectPermission);
    }

    @Override
    public String toString() {
        return "ProtectableConfig[defaultAccess=" + defaultAccess + ", lockPermission=" + lockPermission + ", autoProtectPermission=" + autoProtectPermission + "]";
    }
}
