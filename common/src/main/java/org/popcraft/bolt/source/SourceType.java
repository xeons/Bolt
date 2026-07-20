package org.popcraft.bolt.source;

import java.util.Objects;

public final class SourceType {
    private final String name;
    private final boolean restricted;
    private final boolean unique;

    public SourceType(final String name, final boolean restricted, final boolean unique) {
        this.name = name;
        this.restricted = restricted;
        this.unique = unique;
    }

    public String name() {
        return name;
    }

    public boolean restricted() {
        return restricted;
    }

    public boolean unique() {
        return unique;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceType that = (SourceType) o;
        return restricted == that.restricted && unique == that.unique && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, restricted, unique);
    }

    @Override
    public String toString() {
        return "SourceType[name=" + name + ", restricted=" + restricted + ", unique=" + unique + "]";
    }
}
