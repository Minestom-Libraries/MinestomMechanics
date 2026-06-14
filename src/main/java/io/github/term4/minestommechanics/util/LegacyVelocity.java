package io.github.term4.minestommechanics.util;

import net.minestom.server.ServerFlag;
import net.minestom.server.coordinate.Vec;

// TODO: Maybe move to compatibility package?
/**
 * Snaps an outgoing velocity (b/s) onto the legacy 1.8 wire grid. Vanilla 1.8 encodes velocity as
 * {@code (int) (motPerTick * 8000)} shorts - an {@code (int)} cast, truncating toward zero. The modern pipeline
 * instead quantizes through the 15-bit LP vector encoding (round-to-nearest) and ViaVersion then re-encodes to
 * 1.8 shorts, so a value just under a short boundary (the {@code 0.42F} jump seed = {@code 3359.9999},
 * {@code 0.33319998...} = {@code 2665.6}) pops one short HIGH for legacy clients ({@code 0.42} instead of
 * vanilla's {@code 0.419875}).
 *
 * <p>{@link #snap} replaces each component with the <em>center</em> of vanilla's truncation bucket
 * ({@code (short + 0.25/sign) / 8000} b/t, after vanilla's {@code +-3.9} clamp). Centered values survive the LP
 * quantization (error {@code <= ~0.12} shorts at combat magnitudes) and land on the vanilla short whether the
 * downstream conversion rounds or truncates.
 *
 * <p><strong>1.8-specific, confirmed against both sources:</strong> 26.1's
 * {@code ClientboundSetEntityMotionPacket} encodes via {@code LpVec3.pack} = {@code Math.round((v*0.5+0.5)*32766)}
 * - round-to-nearest, no {@code x8000} short grid at all. The quantization is a <em>server-emulation</em>
 * concern: a server emulating 1.8 mechanics sends what a 1.8 server's encoding would have produced, to ALL
 * clients. Toggled via {@code KnockbackConfig.quantizeVelocity} (default on; disable for modern-mechanics
 * presets).
 */
public final class LegacyVelocity {

    /** Vanilla 1.8 wire scale: shorts per block-per-tick. */
    private static final double WIRE_SCALE = 8000.0;
    /** Vanilla per-component clamp ({@code PacketPlayOutEntityVelocity}): {@code +-3.9} b/t. */
    private static final double WIRE_CLAMP = 3.9;

    private LegacyVelocity() {}

    /** Snaps a velocity in blocks/second onto the vanilla 1.8 wire grid (see class doc). */
    public static Vec snap(Vec perSecond) {
        double tps = ServerFlag.SERVER_TICKS_PER_SECOND;
        return new Vec(
                snapAxis(perSecond.x() / tps) * tps,
                snapAxis(perSecond.y() / tps) * tps,
                snapAxis(perSecond.z() / tps) * tps);
    }

    /** One component in b/t: vanilla clamp, vanilla {@code (int)} truncation, re-encode. */
    private static double snapAxis(double bt) {
        bt = Math.max(-WIRE_CLAMP, Math.min(WIRE_CLAMP, bt));
        int shorts = (int) (bt * WIRE_SCALE); // vanilla cast: truncation toward zero
        if (shorts == 0) return 0;
        return (shorts + Math.copySign(0.25, shorts)) / WIRE_SCALE;
    }
}
