# Projectiles: port plan + fix ledger

Port of the old `minestom-mechanics-lib` projectile implementation (working, but unstructured) into this
library's architecture (config / resolver / snapshot / context / event layer / system / producers).
Old source: `C:\Users\Gabriel\Documents\GitHub\minestom-mechanics-lib\minestom-mechanics-lib\src\main\java\com\minestom\mechanics\`.

## 0. Decisions (ratified) + methodology

- **Knockback**: plain `KnockbackConfig` through `KnockbackSystem.apply` - no separate projectile KB config.
- **Per-item overrides**: config lambdas via `ctx.item()`. The old item-NBT tag system is dropped;
  TODO(attributes): per-item custom configs (custom items carrying their own projectile tuning) belong to
  the future attributes system - revisit there, not in this port.
- **TPS scaling**: not ported yet, but keep it addable: all timing constants stay tick-denominated and
  centralized (no baked-in per-second math scattered through logic), velocities flow through the existing
  b/t vs b/s conventions, so a later TickScaler pass is mechanical.
- **Methodology - SIMPLE FIRST**: the old fixes were not necessarily necessary nor optimal. Section 1 is a
  *ledger*, not a checklist to blindly port: implement the simple/idiomatic path first, then run each
  ledger entry's edge-case test, and only apply (or re-derive) the fix where the simple path actually
  fails. Every applied fix should cite its ledger entry.

## 1. Fix ledger (what the old impl fixed, how, and how to re-test each)

Each entry: the problem, the old fix, and the edge-case test that decides whether the new impl needs it.

### Physics + entity core (`systems/projectile/entities/CustomEntityProjectile.java`)
- **Zero-size bounding box** (`setBoundingBox(0,0,0)`): collision points resolve exactly on block
  boundaries. Even `0.01` overshoots and breaks the modern client's `floor(pos) -> block -> inGround` check.
- **Block collision** via `PhysicsResult.collisionShapes()[axis]` -> hit block / point / axis; the entity is
  placed at `physicsResult.newPosition()` (resolved), NOT the collision point — fixes modern clients seeing
  the arrow float in front of the block face.
- **Stick is "radio silence"**: when stuck, `super.tick()` never runs -> zero periodic syncs. Modern clients
  hold the arrow via `inGround` metadata alone. Subclass `update()` still runs (pickup, despawn timers).
  **⚠️ SUPERSEDED (see status "STUCK-ARROW 1.8 DESYNC"):** radio silence was WRONG - it broke 1.8 self-heal. Vanilla
  never silences a stuck arrow; it re-asserts pos+rotation every `updateInterval`. We now keep movement frozen but do
  a periodic `resyncStuck()` re-assert (+ modern inGround metadata). Movement/scheduler still don't run while stuck.
- **Stick sync is delayed one tick** (Atlas trick): teleport scheduled next tick so a client/server
  hit-disagreement doesn't snap the arrow to the hit position prematurely. **(Now subsumed: the periodic
  `resyncStuck()` naturally fires its first teleport the tick after sticking; the explicit scheduler block was removed.)**
- **Entity collision**: bbox `growSymmetrically(0.3, 0.3, 0.3)` swept from `position` (= 1.8's target `grow(0.3)`
  each side, see §5 - was the `(0.1,0.3,0.1)`/`pos-0.3` desync bug; `expand` ≠ `growSymmetrically`), shooter immune
  for the first ~5 ticks (`SHOOTER_COLLISION_DELAY_TICKS`).
- **`synchronizePosition()` override**: absolute `EntityTeleportPacket` + velocity packet, and
  `lastSyncedPosition` updated manually. Minestom's default relative-move sync is wrong for 1.8 clients
  through Via — they never see the projectile move. This + the spawn-velocity policy is "the smooth vs
  jerky projectile" optimization.
- **`updateNewViewer()`** (relog / chunk-cross viewers): version-branched spawn packets. The spawn packet's
  `data` field must be `> 0` for ViaVersion to include velocity bytes in the 1.8 SpawnObject.
- **Rotation** is displacement-based (yaw/pitch from movement delta); latched at impact when sticking.
- **Unstuck check**: block at `collisionPoint + faceNormal * 0.5` is air. (An intersect-box check
  false-unsticks on fences/slabs.)

### 1.8 stuck-arrow desync fix (`compatibility/legacy_1_8/fix/LegacyProjectileCompat.java`)
> **⚠️ SUPERSEDED - this entire LegacyProjectileCompat approach (F7/F8/F12) is NOT being ported.** It worked around
> our F2 radio silence. The real fix is to match vanilla and never silence a stuck arrow (periodic `resyncStuck()`
> re-assert; see status "STUCK-ARROW 1.8 DESYNC"). The per-tick hints, edge pullback, and edge filter below are kept
> only as historical reference. Re-open ONLY if an in-game test shows a case the periodic re-assert can't heal.
- **Root cause**: the 1.8 client raycasts `pos -> pos + motion` to detect block hits. Stuck in a wall or
  ceiling, gravity points motion away from the surface -> the client never detects the hit -> arrow
  floats/slides.
- **Center hits**: per-tick velocity "hint" packets (flight direction, magnitude 1.0) to legacy viewers
  only. No correction visible — the arrow just stops.
- **Edge hits, two phases**: (1) NO packets — the 1.8 client predicts a natural arc; (2) after
  ~20 ticks, a one-time pullback teleport (0.1 blocks back along flight, original rotation preserved) and
  the hint switches to the face normal.
- Edge detection is shape-relative (works on fences/slabs), threshold 0.35 — **known-broken filter, port
  candidate for a rework** (`// TODO: this doesn't filter properly`).
- Relog: hint embedded in the spawn packet + immediate velocity reinforcement; zero view angles make the
  1.8 client derive rotation from motion.
- `stickGeneration` counter invalidates stale scheduled pullbacks after unstick/restick.
- User assessment: "good ish fix, could need optimizing."

### Fishing bobber (`entities/FishingBobber.java`)
- **Sink-into-floor desync fix** (legacy clients): spawn packet carries ZERO velocity so the client never
  predicts; the server's absolute teleport syncs drive all visible motion; explicit zero-velocity packet on
  landing stops residual prediction.
- **Medium-cast overshoot fix**: the server applies gravity BEFORE moving, the 1.8 client applies the
  received velocity as-is — so the client runs `gravity/TPS = 0.04` blocks/tick fast. Pre-apply the next
  tick's gravity to the velocity packet. (User: "there may be a better / more optimized solution" for the
  bobber overall.)
- Gravity constants: legacy 0.04 / modern 0.03, drag 0.92, gravity added pre-tick.
- **Minemen pseudo-hook** ("bobbers don't stick to players in 1.8"): on player hit -> hook for 1 tick (the
  client renders the line) -> unhook; re-hook for 1 tick whenever the victim MOVES (position, not look)
  until the rod retracts. `PSEUDO_HOOKED_BY` tag set on the victim; LEGACY mode also stops the bobber
  hooking other players after the first pseudo-hook. MODERN mode pins the bobber at the victim's face.
- Hooked non-players get pulled `0.1 * delta * TPS`; player-pulling is a config toggle.

### Launch / items (`features/Bow.java`, `features/FishingRod.java`, `components/ProjectileCreator.java`)
- The ITEM causes the SERVER to spawn the entity: bow uses `PlayerBeginItemUseEvent` /
  `PlayerCancelItemUseEvent` / slot-change; rod toggles cast/retrieve on `PlayerUseItemEvent`.
- Bow power `(s^2 + 2s)/3` capped at 1 (s = use seconds); < 0.1 cancels; >= 1.0 = critical.
- Arrow selection offhand -> main -> inventory scan; infinity/creative; enchants (Power -> base damage,
  Punch -> KB level, Flame -> fire ticks); pickup modes.
- **Spawn positions** (`utils/ProjectileCalculator.java`): arrows eye − 0.1; throwables eye + 0.1 forward
  − 0.05 down; bobber 0.3 in front at eye height (uses the legacy eye-height system).
- Projectile yaw/pitch derived FROM the final velocity (after spread/momentum) so visuals match flight.
- Spread = gaussian × 0.0075 × multiplier; optional shooter-momentum inheritance (horizontal only when
  grounded).
- Cleanup listeners: disconnect clears bow+rod state; death clears bow only (bobbers persist).

### Edge-case test matrix (run per fix before porting it)

| # | Old fix | Edge-case test that justifies it |
|---|---------|----------------------------------|
| F1 | Zero-size bounding box | Modern client: arrow shot into a wall - does it render `inGround` flush with the face, or float/jitter? Repeat with bbox 0.01/0.25. |
| F2 | Radio silence when stuck | Modern client: stuck arrow for 60s+ - any twitch when Minestom's periodic sync fires? |
| F3 | Resolved-position placement (not collision point) | Modern client: shots into walls/ceilings at shallow angles - arrow floating in front of the face? |
| F4 | One-tick delayed stick sync (Atlas) | Point-blank wall shots at high ping - does the arrow visibly snap/jump to the hit position? |
| F5 | Absolute-teleport position sync | 1.8 client via Via: does a flying arrow visibly move with Minestom's default relative sync? (Old result: it never moves.) |
| F6 | Spawn `data > 0` for Via velocity bytes | 1.8 client: does a freshly spawned projectile fly smoothly or sit still until first teleport? |
| F7 | Legacy stuck hints (center) | 1.8 client: arrow into wall/ceiling center - float/slide without per-tick hints? (Root cause is structural: client raycast needs motion into the block. Likely needed.) |
| F8 | Edge pullback two-phase | 1.8 client: arrow into block edges/corners - with only F7 hints, does the client miss the surface? Old filter is known-broken; re-derive threshold. |
| F9 | Bobber zero-velocity spawn + teleport-driven motion | 1.8 client: cast bobber - does it sink into the floor with normal velocity-driven sync? |
| F10 | Bobber gravity pre-application in velocity packets | 1.8 client: medium-range cast - does the bobber visually overshoot the landing point by ~0.04 b/t drift? |
| F11 | Pseudo-hook re-hook on move | Visual: does the line render persistently on a moving victim with plain hook metadata? (This is a feature - Minemen mode - not a fix.) |
| F12 | Stuck-arrow relog spawn (hint-in-spawn, zero view) | 1.8 client: relog / walk far away and return while an arrow is stuck - floats or sticks? |
| F13 | Chunk-cross viewer handling (`updateNewViewer`) | Both clients: shoot across a chunk border into an unseen chunk; walk in - projectile state correct? |

## 2. Target architecture (this library's idiom)

```
mechanics/projectile/
  ProjectileSystem           install(mm, ProjectileConfig) — config decides which launchers run
                             (typeConfigs presence = enabled, same as DamageSystem.install)
  ProjectileConfig           FieldValue knobs + per-type ProjectileTypeConfig map (mirrors DamageConfig)
  ProjectileConfigResolver   ProjectileContext(snapshot, services) -> ResolvedProjectileConfig
  ProjectileSnapshot         shooter, hand, item, type key, power, spawn/velocity overrides, config override
  launchers/                 item -> entity producers (the damage `types/` analog):
    BowLauncher, ThrowableLauncher (snowball/egg/pearl), FishingRodLauncher
  entities/                  ProjectileEntity (CustomEntityProjectile port), ArrowEntity,
                             ThrownItemEntity, FishingBobberEntity
  sync/                      LegacyStuckSync (LegacyProjectileCompat port, gated by ClientInfoTracker),
                             absolute-teleport view sync policy
api/event/                   ProjectileLaunchEvent (pre-spawn: cancellable, velocity/spawn mutable),
                             ProjectileHitEvent (entity/block, cancellable)
```

**Per-type configurability requirement** (parity with the old system, expressed as `FieldValue` knobs on
`ProjectileTypeConfig` - constants or per-context lambdas like every other config in the lib):
bounding box size, spawn offset (forward/vertical, eye-height source), initial speed/power curve,
spread, shooter-momentum inheritance, aerodynamics (gravity + horizontal/vertical drag), shooter-immunity
ticks, sync interval, knockback config, damage amount/type, pickup mode + delay, despawn ticks, and the
legacy-compat toggles (hint magnitude / pullback / edge threshold; bobber fix mode). The entity classes
stay dumb - launchers resolve the config and stamp the entity (aerodynamics, bbox, sync interval) at spawn.
Performance: resolve once per launch, not per tick; per-tick reads (aerodynamics) live on the entity.

Integration decisions (keep responsibilities where they already live):
- **Knockback**: NO separate ProjectileKnockbackConfig. Hits route through `KnockbackSystem.apply` with a
  `KnockbackSnapshot` (origin = projectile position or shooterOriginPos, direction = flight direction,
  melee = false) carrying a per-type `KnockbackConfig`. quantizeVelocity / entityPush / velocity-rule knobs
  come along free. (Old lib's bobber-relative vs shooter-relative KB = snapshot origin choice.)
- **Damage**: arrow/thrown hits emit `DamageSnapshot`s through new `DamageType`s (`minecraft:arrow`,
  `minecraft:thrown` ...), so invul windows, overdamage, hurt broadcast, and the event layer all apply
  unchanged. Punch/Power live in the type's producer like melee's crit/sprint logic.
- **Version branching**: `ClientInfoTracker.getProtocol` replaces the old ClientVersionDetector.
- **Velocity tracking**: projectiles are server-authoritative entities — `mm.velocity()` and MotionTracker
  are NOT involved; the entity's own velocity is the truth (the simulated() non-player path already returns it).
- **No TickScaler port** initially: the lib elsewhere assumes `ServerFlag.SERVER_TICKS_PER_SECOND` (see
  DamageSystem's TPS TODO); TPS scaling is one coherent later pass.
- **Per-item overrides**: prefer config lambdas reading `ctx.item()` (consistent with everything else)
  over the old item-NBT-tag system (`ProjectileTagRegistry`/`VelocityTagValue`) — revisit only if per-item
  runtime data (not config) is actually needed.

## Status (HANDOFF - read this first)

All paths below are relative to repo root `C:\Users\Gabriel\Documents\GitHub\MinestomMechanics`. Build/verify:
`cd` to root, `./gradlew compileJava compileTestJava --console=plain -q` (PowerShell tool, the user is on
Windows). Minestom source (read-only, for API) is the sibling repo `../Minestom`; vanilla 1.8.8 ref is
`C:\Users\Gabriel\Desktop\Development\paper_source_1.8.8` and modern is `...\26.1-src`. The old (messy but
working) projectile impl to port from: `C:\Users\Gabriel\Documents\GitHub\minestom-mechanics-lib\minestom-mechanics-lib\src\main\java\com\minestom\mechanics\`.

### Built and compiling
- **Entity core** - `src/main/java/.../mechanics/projectile/entities/ProjectileEntity.java`: swept block
  physics, block stick (movement frozen, but a vanilla-style periodic `resyncStuck()` re-assert - NOT radio silence,
  see status "STUCK-ARROW 1.8 DESYNC"), entity hits, absolute-teleport sync, viewer spawn. Velocity is **b/t
  internally** (`velocityBt`), mirrored to `super.velocity` in b/s. The legacy stuck-sync (F7/F8/F12) is no longer
  needed - the periodic re-assert + modern inGround metadata cover both clients.
- **Config/event layer** (mirrors the damage system exactly): `mechanics/projectile/ProjectileConfig.java`
  (generic `defaults` config + per-type map, presence-enables), `types/ProjectileTypeConfig.java` (ALL per-type
  knobs as `FieldValue`: bbox, aerodynamics, `spawnOffsetH`/`spawnOffsetV` (+ combined `spawnOffset(h,v)`), speed,
  spread, momentum, immunity, sync, knockback, `knockbackSource` enum {PROJECTILE, SHOOTER}, damage, damageType,
  remove-on-hit; has keyless `builder()` / `builder(base)` / `toBuilder()` for composition), `ProjectileConfigResolver.java`
  (context + resolved + hard fallbacks; **chain = per-type override -> config `defaults` -> type intrinsic -> fallbacks**),
  `ProjectileSnapshot.java`, `ProjectileSystem.java` (launch: resolve->spawn/velocity->`ProjectileLaunchEvent`->stamp+spawn;
  register/enable like `DamageSystem.install`), `types/ProjectileType.java` (abstract; config-free 3-arg ctor + optional
  intrinsic-defaults 4-arg ctor; `createEntity` + `enable`/`disable`), `entities/ManagedProjectile.java`
  (the generic hit handler). API events: `api/event/ProjectileLaunchEvent.java`, `.../ProjectileHitEvent.java`.
- **Throwable types** - `types/{ThrowableItemType,Snowball,Egg,Pearl}.java`: `ThrowableItemType` (shared base)
  wires the item-use throw + consume; the three types are **config-free** (entity + which item only) and share one
  baseline. ALL tuning is in `Vanilla18.projectileDefaults()` (researched vanilla 1.8: `speed 1.5`, `spread 0.0075`,
  `gravity 0.03`, `drag 0.99`, `inheritMomentum=false`, spawn `-0.1`V + `0.16` lateral, `ignoreShooterHit=true`,
  `knockbackSource=SHOOTER`, vanilla KB, `damage 0` via `ProjectileDamage`). Egg/pearl add
  `entities/{EggEntity,PearlEntity}.java` (baby chicken / shooter teleport via the new
  `ManagedProjectile.onImpact(hitEntity)` hook, which fires on entity AND block impact).
- **Momentum**: `ProjectileSystem.launchVelocity` folds `MotionTracker.positionDelta(shooter)` (= 26.1
  `getKnownMovement`), NOT `getVelocity()`; horizontal always, vertical only if airborne. Default `false` (1.8). §5.
- **Hit knobs resolve at IMPACT** (the big restructure): `ProjectileConfigResolver` split into `resolveFlight` (launch:
  spawn/physics) + `resolveHit` (impact: damage/knockback/selfHits/removal) against a `ProjectileContext` carrying
  `target` / `isSelfHit` / `throwOrigin` / `hitPos`. `ManagedProjectile` stores the merged `effectiveConfig` + snapshot
  and resolves the hit knobs each hit. This makes self-vs-other a plain config lambda (`ctx -> ctx.isSelfHit() ? ...`),
  no dedicated `SelfHit` record. `createEntity(shooter, snap, effectiveConfig)` now takes the merged type config.
- **Self-hit**: `selfHit` (`HitResponse` {HIT, PASS_THROUGH, DEFLECT}, default HIT) - PASS_THROUGH ignores + flies on,
  DEFLECT bounces off (reverse+damp); constant-friendly (pearl = PASS_THROUGH). The enum generalizes to any "not
  allowed to hit" case. Richer self behavior is a lambda on the hit knobs. `KnockbackSource.SHOOTER` carries
  `source = shooter` so the KB config's `yawWeight` does shooter->victim vs yaw (dropped `SHOOTER_LOOK`).
  `throwOrigin` exposed on `ctx` + the event. §5.
- **Spawn/velocity research**: §5 has the exact 1.8 spawn (eye, `0.16` lateral, `0.1` down), velocity (`1.5` +
  `0.0075` spread), collision (point raytrace; size `0.25` is render-only -> our F1 box 0 matches), physics order
  (1.8 post-move vs 26.1 pre-move), and the 1.8->26.1 delta table for the future modern preset.
- **Pearl**: teleport + `FallDamage.resetFallDistance(shooter)` (DONE) + flat 5 (vanilla is a CONSTANT, FALL-typed;
  via `GenericDamage` stand-in for now). TODOs: dedicated FALL/enderPearl damage type (the 1.8-vs-26 type delta),
  5% endermite, cross-instance teleport.
- **Bow + arrow (phase 4, first cut)**: `types/Arrow.java` (IS the projectile AND wires the bow draw:
  `PlayerCancelItemUseEvent` -> power `(s^2+2s)/3` -> consume arrow -> launch) + `entities/ArrowEntity.java`
  (velocity-based damage `ceil(speed*2)+crit`, `setCritical` -> meta crit particles, sticks in blocks via the
  inherited machinery, pickup-while-stuck). `Vanilla18.arrow()`: speed 3.0, gravity 0.05, SHOOTER-relative KB, stick.
  (KB is SHOOTER, not PROJECTILE: 1.8 `damageEntity` knocks away from `DamageSource.arrow.getEntity()` = the shooter,
  not the arrow's flight; verified in source. Punch adds a separate motion-direction KB - TODO.)
  Velocity-based damage uses the new `ManagedProjectile.hitDamage(hit, target)` override hook. **TODO**: gate draw
  on having arrows; offhand-first selection + Infinity; Power/Punch/Flame enchants; pickup delay + survival/creative
  mode; dedicated `minecraft:arrow` damage type; deflect refinement. **Verify in-game**: bow release actually fires
  `PlayerCancelItemUseEvent`.
- **API - two lifecycle events** (merged `Launch`+`Spawn` - they were confusingly similar): `ProjectileLaunchEvent`
  now fires with the BUILT-but-not-yet-spawned `projectile()` entity (Bukkit-style) - cancel discards it, mutate
  spawn/velocity to redirect, or keep the reference to attach a trail/behavior (tasks run next tick once it's in the
  instance). `ProjectileHitEvent` (hit/land - `throwOrigin`/`isSelfHit`/`hitPoint`; spawn-entity-on-land etc.). The
  projectile's damage/KB also fire their own `Damage`/`KnockbackEvent` for per-hit tweaks.
- **LIFECYCLE (vanilla, exact)**: throwables (snowball/egg/pearl = `EntityProjectile`) `die()` on ANY hit (block OR
  entity) - they do their effect then despawn, NEVER stick. ONLY arrows (`EntityArrow`) stick in blocks
  (`inGround`, `shake=7`, despawn after 1200 ticks) and break on an entity hit (or DEFLECT off an invuln target).
  So in our config `removeOnBlockHit=true` = break (throwables), `false` = stick (arrows).
- **CRASH FIX + breaker regression**: removing the entity inside `movementTick` nulled `instance` before
  `Entity.tick`'s `touchTick` -> NPE; my first attempt (`scheduleNextProcess`) then entered the stuck state first,
  so the deferred remove never ran in radio silence -> ALL throwables stuck forever. Fixed properly: `stick()` now
  decides break-vs-stick (from `onStuck()`'s `removeOnBlockHit`) BEFORE any stuck state; a breaker sets a
  `pendingRemove` flag and `tick()` removes AFTER `super.tick()` (post-`touchTick`), never going radio-silent. The
  void + entity-hit removals use the same flag. (The old impl avoided the NPE only via immediate `remove()` on an
  older Minestom that deferred the instance-null.)
- **Arrow pickup cooldown**: 1.8 `EntityArrow.shake = 7` on stick gates pickup (`inGround && shake <= 0`).
  `ArrowEntity` now sets a 7-tick `shake` in `onStuck` and blocks pickup until it ends.
- **STUCK-ARROW 1.8 DESYNC (relog rotation + edge-hit overshoot) - ROOT-CAUSED + FIXED (this session). The cause was
  our own F2 RADIO SILENCE, not a 1.8 patch we were missing.** User test that cracked it: a 1.8 client on a *vanilla
  Paper 26 + Via* server does NOT have these bugs - stuck arrows look briefly weird then self-heal, exactly like a
  native 1.8 server. So vanilla's sync model already handles 1.8; ours diverged.
  - **Mechanism (vanilla `ServerEntity.sendChanges`, 26.1):** a stuck arrow is NEVER silent. The broadcaster re-asserts
    position **+ rotation** every `updateInterval` - note the explicit `&& !(this.entity instanceof AbstractArrow)`
    that DISABLES the pos-only/rot-only branch for arrows, forcing them into the `MoveEntity.PosRot` (position +
    rotation) branch - plus a forced position update every 60t (`tickCount % 60`), a full teleport every 400t
    (`teleportDelay > 400`), and a one-time zero-velocity `SetEntityMotion` when the arrow stops. That continuous
    re-assert is the ONLY thing that corrects a 1.8 client: it has no authoritative inGround stop (its `inGround` is
    self-raytraced and its `t_()` re-derives rotation from motion on a fresh spawn), so without periodic re-syncs a
    mispredicted stuck arrow (relog angle, edge-hit overshoot) never recovers.
  - **Our bug:** F2 made a stuck projectile radio-silent (`super.tick()` skipped, `synchronizePosition` early-returns)
    on the theory that the modern client holds via inGround metadata. But (a) we never actually set inGround until this
    session, and (b) radio silence killed the 1.8 self-heal entirely.
  - **Fix:** keep movement frozen, but replace radio silence with a vanilla-style **periodic re-assert**:
    `ProjectileEntity.tick()` now calls `resyncStuck()` (absolute `EntityTeleportPacket` with current pos + rotation +
    zero velocity, plus a zero `EntityVelocityPacket`) every `getSynchronizationTicks()` (=20, matching vanilla's arrow
    `updateInterval`), starting the tick after sticking (which also subsumes the old one-tick-delayed Atlas F4 teleport
    - that scheduler block is removed). `getVelocityForPacket()` now reports ZERO while stuck. The **modern** client
    stays frozen via the inGround metadata (`ArrowEntity.onStuck/onUnstuck`, kept), so each re-assert is a no-op for it
    (same pos+rotation, zero velocity does not unground); the **1.8** client self-heals from the periodic teleports,
    matching vanilla.
  - **This SUPERSEDES the whole legacy stuck-sync plan:** F7 (per-tick center hints), F8 (edge pullback), and F12 (the
    legacy relog spawn hint) are no longer needed - they were all working around the absence of the vanilla re-assert.
    The F12 spawn-hint code added earlier this session was REVERTED (incl. `stuckFlightDir` / `isLegacyViewer` /
    `LEGACY_HINT_MAGNITUDE`); `updateNewViewer` is back to a single version-agnostic spawn. (`ClientInfoTracker.isLegacy`
    + `LEGACY_PROTOCOL_MAX` were kept - `SilentDamage` uses them.)
  - **Tunable:** the self-heal latency is the sync interval (20t = up to ~1s, vanilla parity). If snappier relog is
    wanted later, lower `syncInterval` for arrows or trigger an extra `resyncStuck()` on `updateNewViewer` while stuck.
- **Hit model (vanilla-faithful)**: `ManagedProjectile.onHit` routes through the **damage system first** with
  a 0-damage snapshot, then applies knockback **only if it landed**. The 0-damage hit still plays hurt + opens
  the invul window (the GATE) because `damage/types/projectile/ProjectileDamage.java` (`minecraft:thrown`,
  `baseAmount 0`, `triggersInvul` on) and `DamageSystem` now lets a 0-damage hit land when its type triggers
  invul. Projectile + melee count as `knockbackOwnsVelocity` (no double hurt-velocity broadcast). The
  projectile still breaks on an invul'd victim (no KB).
- **Velocity tracking is a profile member** (not `mm.velocity()`, which is DELETED): `MechanicsProfile.velocity`
  / `MechanicsProfiles.velocityFor(victim)`; `KnockbackCalculator` resolves `KnockbackConfig.velocity` ->
  profile velocity -> `VelocityRule.DEFAULT`. Presets expose `Vanilla18.velocity()`, `Hypixel.velocity()`,
  `Minemen.velocity()`. `MechanicsProfile.projectiles` + `projectilesFor` added too.
  - **Velocity is ONE per-player thing, not a per-system tracker.** `VelocityRule` (simulated/delta/closed/split)
    is a *stateless read strategy* over the single `MotionTracker` (one per-tick tracker for all players); switching
    or mixing modes costs nothing per tick. "Multiple velocities per player" was a non-issue. Profile = the single
    source of truth; melee KB, **projectile KB**, and the hurt broadcast all inherit it via `velocityFor(victim)`.
  - **Resolved velocity is threaded onto `KnockbackContext`** (`ctx.velocityRule()` / `ctx.victimVelocity()`):
    `KnockbackCalculator` resolves the rule once (config -> profile -> DEFAULT) and hands it to the custom
    `KnockbackComponent`s via `ctx.withVelocity(...)`, so a component reads the SAME velocity the friction fold
    used instead of hard-pinning a static rule. `Minemen.kb()` no longer sets `.velocity(...)`; its axial-drag /
    cap-hold components read `ctx.victimVelocity()` and it relies on `MechanicsProfile.velocity(Minemen.velocity())`.
    This removed the melee-vs-projectile velocity divergence (the projectile carries its own `Vanilla18.kb()`, so a
    velocity pinned on a melee KB config never reached it - only the profile channel does).
- **Clean install**: `ProjectileSystem.install(mm, Minemen.projectiles())` (test server uses this).
  `Vanilla18.projectiles()` = generic `defaults` + `snowball()` entry; `Minemen.projectiles()` re-bases from it
  (`builder(Vanilla18.projectiles())`, vanilla projectile behavior today, seam to give projectiles Minemen feel).
  Profile-scoped via `projectilesFor`.

### Decisions locked (from the user)
1. Projectile KB = plain `KnockbackConfig` through `KnockbackSystem.apply`. 2. Per-item override = config
lambdas; **base/vanilla configs must stay constant-only (no lambdas)**; custom-item NBT deferred to the future
attributes system. 3. No TickScaler yet, but all timing stays tick-denominated so it's addable.
4. **Velocity tracking = one per-player rule set ONCE on a profile scope** (instance/global/player), inherited by
every system (melee KB, projectile KB, hurt broadcast). `KnockbackConfig.velocity` stays as a rare per-config
override; components read the resolved rule via `ctx.victimVelocity()` (no static pinning). NOT building a registry
of stateful per-scope trackers - the single `MotionTracker` + stateless `VelocityRule` reads already give "one
velocity per player" for free. Revisit only if a future mode needs its own per-tick state.
5. **Projectile config lives in the PRESETS, never in the type class.** Types are identity + behavior (config-free
`ProjectileType(key, name, entityType)`); `Vanilla18`/`Minemen` own the tuning. A `ProjectileConfig` carries a
generic `defaults` (the shared baseline, applied to every type) plus sparse per-type overrides, so a type entry
restates only its deltas (`ProjectileTypeConfig.builder(KEY).damage(x)`) or is empty just to enable. Resolution:
per-type -> generic `defaults` -> type intrinsic -> hard fallbacks. Spawn offset is `spawnOffsetH`/`spawnOffsetV`
(+ combined `spawnOffset(h,v)`).
6. **Projectile config resolves in TWO phases**: flight knobs at launch, hit knobs at IMPACT (against a
`ProjectileContext` with `target`/`isSelfHit`/`throwOrigin`/`hitPos`). Self-vs-other and throw-time behavior are
plain config lambdas (`ctx -> ctx.isSelfHit() ? ...`), NOT a dedicated structure or the event API; `selfHits(false)`
is the one constant knob (pass-through-self) so vanilla stays constant-only. KB yaw is `KnockbackSource.SHOOTER`
(source = shooter) + `yawWeight`, never a `SHOOTER_LOOK` enum. The event API stays for genuinely dynamic per-hit logic.

### Immediate next (rough order)
1. **In-game test snowball/egg/pearl** (user does this): flight, shooter-relative KB, hurt flash + invul gate
   (rapid throws - only ~1 per window applies KB), break-on-hit; egg baby-chicken (1/8); pearl teleport + 5 fall
   damage + the 1.8 self-pass-through (throw straight up -> it falls through you -> teleports on landing). Watch
   the 1.8 client for desync to decide which F-ledger fixes are needed. NOTE: verify `living.damage(0)` plays the
   hurt flash; if not, force the hurt status packet. Verify the pearl 5-damage (GenericDamage stand-in) hurts + respects invul.
2. **Egg + pearl DONE** (this session). Remaining polish: pearl fall-reset / endermite / dedicated damage type +
   cross-instance; egg chicken has no AI (plain Entity); snowball blaze-3 (needs entity-type-aware damage).
3. **Bow + arrow CORE DONE** (draw/power, `ArrowEntity` velocity-damage + crit + stick + 7-tick pickup cooldown,
   `Vanilla18.arrow()`). OPEN, roughly in priority:
   a. **Stuck-arrow 1.8 desync (relog rotation + edge-hit overshoot) - DONE.** Root cause was our F2 radio silence;
      fix = vanilla-style periodic `resyncStuck()` re-assert + modern inGround metadata. See the status bullet
      "STUCK-ARROW 1.8 DESYNC". This SUPERSEDED F7/F8/F12 (legacy stuck-sync no longer needed). User-confirmed working.
      **UNSTUCK on a relogged 1.8 client (freeze-before-fall) - ACCEPTED, not fixed:** a 1.8 arrow that is client-side
      `inGround` IGNORES position teleports, and a relog-spawned stuck arrow holds a stale inGround state, so when the
      block breaks it briefly freezes at the old spot before falling. A tried `unstick()` re-spawn fixed it but the
      user disliked it; this same behavior is present on vanilla 26+Via (and occasionally vanilla 1.8), so it's
      vanilla-accurate and we keep it. `unstick()` is back to the simple form (just a NOTE comment).
   b. **Arrow pickup - DONE (animation + vanilla bbox + player-only scan + pickup MODE).** `ArrowEntity` sends
      `CollectItemPacket` before `remove()` (arrow flies into the player, vanilla `player.take`). Geometry is the exact
      vanilla AABB overlap: the arrow's `0.5 x 0.5` box (1.8 `setSize(0.5,0.5)`; our collision box is 0 per F1) vs the
      player's bbox inflated `(1.0, 0.5, 1.0)` - identical in 1.8 `EntityHuman` (`grow(1,0.5,1)`) and 26.1
      `Player#aiStep` (`inflate(1,0.5,1)`). Scan is `EntityTracker.Target.PLAYERS` (nearby PLAYERS only) + the box test.
      Pickup who/what is now `ArrowEntity.Pickup` {DISALLOWED, ALLOWED, CREATIVE_ONLY} (replaced the ad-hoc `pickupable`
      boolean; the bow sets ALLOWED survival / CREATIVE_ONLY creative - mirrors 1.8 `fromPlayer`). Geometry is now the
      `ProjectileTypeConfig.PickupBox` knob (inflateH/V + arrow box; default vanilla), stamped on `ArrowEntity`. The
      scan radius is DERIVED from the geometry (vanilla has no scan radius - it iterates players in the grown box;
      Minestom's entity query is radius-based, so the radius is only a broad-phase pre-filter and `withinPickupBox` is
      the exact 1:1 test). TODO: inventory-full = no pickup; "random.pop" sound.
   c. **ENTITY-HIT DESYNC - FIXED + made configurable (this session).** 1.8 grows the TARGET by 0.3 each side and
      ray-tests the flight path; we were too tight, so arrows the 1.8 client predicts as a hit flew past server-side
      ("arrow disappears for shooter / bounces for target"). Now `growSymmetrically(entityHitGrow,...)` from `position`
      (§5). `entityHitGrow` is a per-type `ProjectileTypeConfig` flight knob (default 0.3, stamped at launch). Arrow KB
      is also SHOOTER-relative now (1.8 `damageEntity` knocks from `DamageSource.arrow.getEntity()` = the shooter).
   d. **Deflection + pass-through 1:1 - DONE.** Arrow deflect-on-invuln: a hit that deals no damage (invuln victim)
      bounces (`motion *= -0.1`) instead of breaking - `deflectOnInvuln` hit knob (arrow `true`, throwables `false`);
      `ManagedProjectile.deflect()` does `-0.1` + re-arms shooter immunity (vanilla `as = 0`) + clamps the placement to
      the hit point (no overshoot before the bounce). Pearl pass-through verified 1:1 (1.8 `EntityEnderPearl.a`:
      `if (hit == shooter) return;` = our `selfHit(PASS_THROUGH)`). Remaining smaller items: 1200-tick stuck despawn;
      Power/Punch/Flame enchants; offhand/Infinity arrow selection; draw-gate on having arrows.
   e. **Post-stick 0.05 pull-back + flight-sync throttle (O1) - DONE.** Stick now pulls the arrow back 0.05 along the
      flight direction (vanilla 1.8 `locX -= motX/|mot|*0.05`; 26.1 per-axis) so the tip pokes out of the block face -
      `stuckPlacement`. This may also reproduce the 1.8 "small nudge a few ticks after sticking" (client prediction vs
      the server's pulled-back position; modern stays put via inGround metadata). O1: in-flight `synchronizePosition`
      now sends the absolute teleport only every `syncInterval` (velocity still goes every tick via `Entity.tick`, the
      client predicts the arc between) instead of every tick - cuts packets for long-flight projectiles; **test flight
      smoothness** on 1.8 (tunable via `syncInterval`).
4. **Fishing rod** (phase 5): bobber + desync handling (revisit whether the bobber needs the same periodic-resync
      treatment or its own model) + pseudo-hook modes (config enum).
5. Hypixel/Minemen projectile presets. (The old `LegacyStuckSync`/F7/F8/F12 plan is dropped - superseded by 3a.)

### Known TODOs left in code (grep `TODO`)
- `KnockbackCalculator` / `KnockbackConfig.customComponents` / `DamageConfig.customComponents`: **stages plan**
  - make built-in stages (friction/combine/bounds, damage invul rule) replaceable strategies, not just
  append-only post-components. `DamageSystem.knockbackOwnsVelocity` should become a `DamageTypeConfig` flag.
- `DamageSystem.apply` / `KnockbackSystem.apply`: phased event layer (PreDamage/DamageModify/FinalDamage) +
  kill the double config-resolution.

## 3. Port order

## 3. Port order

1. **Entity core**: ProjectileEntity port (zero bbox, physics, stick machinery, absolute-teleport sync,
   updateNewViewer) — modern-correct first.
2. **LegacyStuckSync**: the 1.8 hint/pullback system, gated per viewer by protocol. Rework the edge filter.
3. **Throwables**: ThrowableLauncher + ThrownItemEntity (snowball/egg) — smallest item->spawn->hit loop;
   proves snapshot/config/event plumbing end to end. Then pearls (teleport on land).
4. **Bow**: draw state, power, arrow selection/consumption, ArrowEntity (pickup, despawn, crit), enchants.
5. **Fishing rod**: cast/retrieve, bobber entity with both desync fixes, pseudo-hook modes
   (VANILLA stick / LEGACY_18 no-stick / MINEMEN pseudo-hook as a per-type config enum), pull logic.
6. **Presets**: `Vanilla18.projectiles()`, Hypixel/Minemen overrides (bobber mode, rod KB, spawn offsets).

## 4. Open questions

- Pearl/egg gameplay effects (teleport, spawn chicken?) — in scope or stub the hooks?
- Old known issues to fix during port (from old TODOs): rod can't hit the shooter; bobber "pickup" feel
  (collides with own player when running forward); bobber tunnels through blocks on long casts;
  >30-block auto-retract unimplemented; edge-hit filter misfires.
- Naming for the hit->damage types and whether arrows get their own invul interaction (vanilla 1.8 arrows
  respect the same noDamageTicks window).

## 5. Vanilla parity notes (researched from 1.8 PaperSpigot + 26.1 source)

Source paths: 1.8 = `...\paper_source_1.8.8\...\net\minecraft\server\Entity{Projectile,Snowball,Egg,EnderPearl}.java`;
26.1 = `...\26.1-src\net\minecraft\world\entity\projectile\{Projectile,ThrowableProjectile,throwableitemprojectile\*}.java`.

### Momentum inheritance (shooter velocity folded into the throw)
- **1.8** (`EntityProjectile` ctor + `shoot()`): **NONE.** A thrown snowball/egg/pearl gets velocity =
  `look x 1.5 + gaussian(0.0075)` only - the shooter's `motX/Y/Z` is never added. So vanilla-1.8
  `inheritMomentum = FALSE`.
- **26.1** (`Projectile.shootFromRotation`): `delta += source.getKnownMovement().x/z` always, `.y` only when the
  shooter is airborne (`source.onGround() ? 0 : y`). Critically it reads **`getKnownMovement()`** (the entity's
  REAL tracked movement), NOT server velocity. Our analog is **`MotionTracker.positionDelta(shooter)`** (b/t
  move-delta), NOT `Entity.getVelocity()` (which is ~0/stale for client-driven players - the "feels off" bug).
- **Decision**: `inheritMomentum` defaults FALSE (vanilla 1.8 + resolver fallback). When TRUE (modern presets),
  the momentum source is `MotionTracker.positionDelta(shooter)`; horizontal always, vertical only if airborne.

### Self-hit vs other-entity hit (via hit-time resolution, NOT a dedicated record)
The point is NOT immunity - a projectile can behave DIFFERENTLY on its thrower vs any other entity. After exploring
a `SelfHit` record (rejected as "preset-enum stiff" + limiting), the clean solution is to **resolve the hit knobs at
IMPACT** against a context that carries the target, so plain config lambdas express any self-vs-other difference.
- **Resolution split** (`ProjectileConfigResolver`): `resolveFlight(tc, ctx)` at launch (spawn/physics);
  `resolveHit(tc, ctx)` at impact, where `ctx = ProjectileContext.of(snap, services).atHit(target, throwOrigin, hitPos)`.
  `ManagedProjectile` stores the merged `effectiveConfig` + snapshot and resolves the hit knobs each impact (rare, so
  late resolution is cheap). A hit lambda reads `ctx.isSelfHit()`, `ctx.target()`, `ctx.throwOrigin()`.
- **Vanilla has NO self-immunity** (the user confirmed: singleplayer only). 1.8 excludes the shooter for the first
  5 ticks (`ar < 5`); after that a self-hit is normal - a snowball thrown straight up hits you on the descent. So the
  default is just NORMAL. The **ender pearl** is the one genuine 1.8 self-ignore (`if (hit.entity == shooter) return;`).
- **Native knobs (no lambda for the common case)**:
  - `selfHit` (`HitResponse` knob {HIT, PASS_THROUGH, DEFLECT}, default HIT): the response when the projectile hits
    its OWN shooter. `PASS_THROUGH` = ignore + keep flying (1.8 pearl, Hypixel "self does nothing"); `DEFLECT` =
    bounce off (reverse+damp velocity, no break/dmg/KB). Constant-friendly, so vanilla stays constant-only -
    `Vanilla18.pearl()` = `selfHit(PASS_THROUGH)`. The same `HitResponse` enum generalizes to any future "not allowed
    to hit this entity" case (team filters, etc.).
  - Everything else self-specific is a lambda on the existing hit knobs: Minemen self-KB-along-yaw =
    `knockback(ctx -> ctx.isSelfHit() ? KnockbackConfig.builder(kb).yawWeight(1).build() : kb)`.
- **Yaw, no `SHOOTER_LOOK`**: `KnockbackSource.SHOOTER` now carries `source = shooter` in the snapshot (like melee),
  so the KB calculator has the shooter's look and `yawWeight` does the work - `yawWeight(1)` pushes along the yaw.
  `PROJECTILE` is unchanged (origin = projectile, dir = flight). Dropped `SHOOTER_LOOK`.
- **Throw-time snapshot**: `shooterOriginPos` (captured at spawn) is now exposed as `ctx.throwOrigin()` and on
  `ProjectileHitEvent` (+ `isSelfHit()`), so lambdas AND the event can push KB/teleport from the throw pose.

### Spawn position, launch velocity, collision box (1.8 `EntityProjectile` ctor + `shoot`)
- **Spawn**: shooter eye height, then offset `(-cos(yaw)*0.16, -0.1, -sin(yaw)*0.16)` - a **0.16 LATERAL** shift
  (perpendicular to look = the throwing-hand offset) and **0.1 DOWN**, NO forward. Our config had only the -0.1
  vertical (no lateral) - that's a Hypixel-style accuracy trim, not vanilla. Added `spawnOffsetLateral` (vanilla
  `0.16`). 26.1 (`ThrowableItemProjectile`): eye `- 0.1`, NO lateral.
- **Velocity**: `look_unit * 1.5 + gaussian*0.0075` (speed `j()=1.5`, uncertainty `1.0` -> **spread `0.0075`**). Our
  config had `spread = 0` (Hypixel/accuracy); vanilla is `0.0075`. Gravity `m()=0.03`, drag `0.99` (`0.8` in water).
  Same constants in 26.1.
- **Collision box**: entity SIZE `0.25 x 0.25` (1.8 `setSize`, 26.1 `sized(0.25,0.25)`) is render/broad-phase only.
  BLOCK collision is a POINT raytrace (center -> center+motion) in both - our `bbox = 0` (F1) matches it and is the
  reason we keep size 0 (modern client `inGround` flush). ENTITY collision: 1.8 grows the TARGET by `0.3` on EACH side
  (`Entity{Arrow,Projectile}`: `f=0.3F`, both verified) and ray-tests the path. **FIXED (was the bug):** we now
  `growSymmetrically(grow, grow, grow)` the zero projectile box from the un-offset `position` (Minkowski dual of
  target+0.3), where `grow` is the per-type `ProjectileTypeConfig.entityHitGrow` knob (default `0.3`, stamped on the
  entity at launch like `shooterImmunityTicks`; `DEFAULT_ENTITY_HIT_GROW`). GOTCHA: Minestom's `expand(0.3)` only adds
  0.3 to the TOTAL size (±0.15) and offsets y, so a first attempt with `expand` reached only ~0.45 to the side and
  looked unchanged; `growSymmetrically` adds 0.3 to BOTH sides (±0.3, centered, = 1.8's `grow`). The old `(0.1,0.3,0.1)`
  from `pos-0.3` was too tight: arrows the 1.8 CLIENT predicts as a hit flew right past on the server -> "arrow
  disappears for shooter / bounces for target" desync (1.8-vs-1.8 only; modern's hitbox matches the server). 26.1 uses
  a different margin -> set `entityHitGrow` in the future modern preset.
- **Physics order**: 1.8 moves THEN applies drag+gravity (post-move); 26.1 applies gravity+inertia THEN moves
  (pre-move). Ours = 1.8. Modern preset needs a pre-move toggle later (ties into bobber F10).

### 1.8 -> 26.1 deltas the system must express natively (for the future modern preset)
| Aspect | 1.8 | 26.1 | Knob today |
|---|---|---|---|
| Momentum | none | `getKnownMovement` (h always, v airborne) | `inheritMomentum` (+ positionDelta source) |
| Spawn lateral | 0.16 | 0 | `spawnOffsetLateral` |
| Spread | 0.0075 | 0.0075 | `spread` |
| Self-hit (pearl) | pass-through | self-teleport | `selfHits(false)` + hit-knob lambdas on `ctx.isSelfHit()` |
| Shooter immunity | 5 ticks | `leftOwner` (geometric) | `shooterImmunityTicks` (leftOwner = TODO) |
| Physics order | post-move drag/grav | pre-move grav/inertia | **TODO** (hardcoded post-move) |
| Pearl dmg type | FALL, 5 | `enderPearl`, 5 | amount yes; type = TODO (GenericDamage stand-in) |
| Pearl teleport target | pre-move pos | `oldPosition()` | ~same (pre-move) |
| Sync interval | n/a here | `updateInterval(10)` | `syncInterval` (ours 20) |

### Egg / pearl impact + pearl damage (both fire on entity AND block hit)
- **Egg**: 0 dmg to a hit entity; `1/8` chance baby chicken (`1/32` -> 4), break. (26.1 adds a chicken-variant
  component - skipped.)
- **Snowball**: 0 dmg, `3` to a Blaze (defer - needs entity-type-aware damage), break.
- **Pearl damage is a CONSTANT, not distance-based** (answers the open question): 1.8
  `entityliving.fallDistance = 0; damageEntity(DamageSource.FALL, 5.0F)` - it ZEROES the fall distance then deals a
  flat **5**, typed as FALL (feather-falling/resistance apply, armor does not). 26.1:
  `hurtServer(damageSources().enderPearl(), 5.0F)` - flat **5** via a DEDICATED `enderPearl` type. So the amount is
  constant 5 in both; only the damage TYPE differs (FALL vs enderPearl). We now call
  `FallDamage.resetFallDistance(shooter)` (already public "for ender pearls") + deal a flat 5 (currently via
  `GenericDamage` - no armor model yet so numerically == FALL; a dedicated EnderPearl/FALL-amount type is the clean
  follow-up that also captures the 1.8-vs-26 type delta).
