package io.github.term4.minestommechanics.platform.fixes.world;

import io.github.term4.minestommechanics.platform.fixes.FixToggle;
import org.jetbrains.annotations.Nullable;

/**
 * Config for the block-break progress broadcast - the {@code blockBreakProgress} member of
 * {@link io.github.term4.minestommechanics.platform.fixes.FixesConfig}. When enabled, other players see the vanilla
 * crack overlay on a block being mined (Minestom never sends {@code BlockBreakAnimationPacket}). See
 * {@link BlockBreakProgressFix}.
 *
 * <p>Server-wide (the tick loop and digging events are not scoped), so this is an install-level toggle ({@code null} = off).
 */
public final class BlockBreakProgressFixConfig implements FixToggle {

    private final @Nullable Boolean enabled;

    private BlockBreakProgressFixConfig(Builder b) { this.enabled = b.enabled; }

    /** Whether block-break progress is broadcast to other players; {@code null} = off. */
    public @Nullable Boolean enabled() { return enabled; }

    /** Merges this config over {@code base}: this config's set knob wins, unset falls back to {@code base}. */
    public BlockBreakProgressFixConfig fromBase(BlockBreakProgressFixConfig base) {
        return new Builder().enabled(enabled != null ? enabled : base.enabled).build();
    }

    public Builder toBuilder() { return new Builder(this); }
    public static Builder builder() { return new Builder(); }
    public static Builder builder(@Nullable BlockBreakProgressFixConfig base) { return base != null ? new Builder(base) : new Builder(); }

    public static final class Builder {
        private @Nullable Boolean enabled;

        Builder() {}
        Builder(BlockBreakProgressFixConfig c) { enabled = c.enabled; }

        public Builder enabled(@Nullable Boolean v) { this.enabled = v; return this; }

        public BlockBreakProgressFixConfig build() { return new BlockBreakProgressFixConfig(this); }
    }
}
