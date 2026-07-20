package org.popcraft.bolt.access;

import java.util.Objects;
import java.util.Set;

public final class Access {
    private final String type;
    private final boolean restricted;
    private final Set<String> permissions;

    public Access(final String type, final boolean restricted, final Set<String> permissions) {
        this.type = type;
        this.restricted = restricted;
        this.permissions = permissions;
    }

    public String type() {
        return type;
    }

    public boolean restricted() {
        return restricted;
    }

    public Set<String> permissions() {
        return permissions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Access access = (Access) o;
        return restricted == access.restricted && Objects.equals(type, access.type) && Objects.equals(permissions, access.permissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, restricted, permissions);
    }

    @Override
    public String toString() {
        return "Access[type=" + type + ", restricted=" + restricted + ", permissions=" + permissions + "]";
    }
}
