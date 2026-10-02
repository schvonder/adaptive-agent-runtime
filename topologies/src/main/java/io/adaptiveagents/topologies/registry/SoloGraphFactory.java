package io.adaptiveagents.topologies.registry;

public final class SoloGraphFactory implements GraphFactory {
    @Override
    public Object build() {
        throw new UnsupportedOperationException("SOLO graph assembly lands in T12");
    }
}
