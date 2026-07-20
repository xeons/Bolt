package org.popcraft.bolt.data;

import java.util.Objects;
import java.util.UUID;

public final class Profile {
    private final UUID uuid;
    private final String name;

    public Profile(final UUID uuid, final String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public boolean complete() {
        return uuid != null && name != null;
    }

    public boolean empty() {
        return uuid == null && name == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Profile profile = (Profile) o;
        return Objects.equals(uuid, profile.uuid) && Objects.equals(name, profile.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, name);
    }

    @Override
    public String toString() {
        return "Profile[uuid=" + uuid + ", name=" + name + "]";
    }
}
