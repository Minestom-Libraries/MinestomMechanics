package io.github.term4.minestommechanics.mechanics.attack.reach;

import io.github.term4.minestommechanics.tracking.ClientInfoTracker;

/**
 * The eye heights a client could perceive itself at, by protocol version - the "client-believed" eye used for reach. The
 * reach check takes the MIN over these candidates, so the client's exact pose never has to be known: a 1.8 client only ever
 * sees 1.62 standing / 1.54 sneaking (it has no lower pose), while a modern client adds the 1.27 crouch eye and the 0.4
 * crawl/swim/elytra eye. Swimming in particular is NOT signalled to the server (no client packet sets it - verified in the
 * dep), so it MUST be a candidate rather than a server-side lookup; crouch/standing are covered for free by the same set.
 */
final class ClientEye {

    private ClientEye() {}

    /** Candidate client-perceived eye heights for {@code protocol} (unknown protocol = treated as modern). */
    static double[] candidates(int protocol) {
        boolean legacy = protocol != ClientInfoTracker.UNKNOWN_PROTOCOL && protocol <= ClientInfoTracker.LEGACY_PROTOCOL_MAX;
        return legacy ? new double[]{1.62, 1.54} : new double[]{1.62, 1.27, 0.4};
    }
}
