package org.popcraft.bolt.source;

import java.util.Objects;

public final class SourceTypeResolver implements SourceResolver {
    private final Source source;

    public SourceTypeResolver(final Source source) {
        this.source = source;
    }

    public Source source() {
        return source;
    }

    @Override
    public boolean resolve(Source source) {
        return this.source.getType().equals(source.getType());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceTypeResolver that = (SourceTypeResolver) o;
        return Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source);
    }

    @Override
    public String toString() {
        return "SourceTypeResolver[source=" + source + "]";
    }
}
