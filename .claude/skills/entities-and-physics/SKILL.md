---
name: entities-and-physics
description: Centity node trees, transforms, animation and hitboxes as the server runs them, and the rigid-body physics solver - bodies, colliders, contacts (SAT), sequential impulses, inertia, sleeping, collision with world blocks and other centities' hitboxes, how physics writes node poses, the Lua velocity/impulse API, and the deliberate behaviour changes from the reference (client-only shapes fitted by the editor). Read before touching apps/plugin/runtime/.../centity or .../physics, PhysicsDef, or the Node physics API.
---

# Entities and physics

Spec: `docs/format/centity.md`. The tick pipeline, hitboxes and click routing
are in the plugin-runtime skill; this one adds where shapes come from and
physics.

## Shapes the server can and can't know

The plugin never needs a client-only fact (game-data skill). So:

- `world:raycast` tests centities with the same shapes clicks are
  (`Centities.raycast` → `nearestHitbox`: a `raycast` node's boxes, any
  other node's interaction-entity bounds, skipping a node set not
  clickable), in loaded instances only, and
  takes whichever is nearer of that and the platform's block ray cast.
- Hitboxes are explicit `boxes`, or `shape: "collision"` (the block's live
  collision shape, `GameData.collisionBoxes`, cached per state), or the unit
  cube. A stair's **model** shape or a **text** quad is fitted by the editor
  into explicit boxes (`fittedTo`); the validator warns when stale.
- A script that changes a block or text at runtime can only make a hitbox
  follow the **collision** shape, or keep its explicit boxes. This is a
  deliberate change from the reference, which resolved model/text shapes on the
  server from baked tables.

## Physics: where things are

| Path (`apps/plugin/runtime/.../`) | Owns                                                                              |
| --------------------------------- | --------------------------------------------------------------------------------- |
| `physics/Quat.kt`                 | orientation maths; euler convention identical to `Matrix4.rotateXYZ`              |
| `physics/RigidBody.kt`            | `RigidBody`, `ColliderPart`, `BodyState` (velocity, sleep, …)                     |
| `physics/Inertia.kt`              | inverse inertia of box compounds (parallel-axis) and spheres                      |
| `physics/Contacts.kt`             | SAT (15 axes), manifolds, sphere cases, `BodyShape`                               |
| `physics/PhysicsSolver.kt`        | slicing, sequential impulses, friction, restitution, bias separation              |
| `physics/PhysicsCollider.kt`      | collider boxes from `physics.collider`/hitbox, centre of mass, origin             |
| `centity/Physics.kt`              | the per-instance pass: snapshot, obstacles, solve, write back; script API helpers |

The solver is a faithful Double port of the reference's
(`centity/packages/core/.../physics`), pure and Platform-free. Tests:
`physics/PhysicsTest`, `PhysicsPairTest`, `QuatTest` (solver alone) and
`PhysicsCentityTest` (spawned centities on the fake world).

## How a tick moves bodies

Order per instance: **animate → path → physics → scripts → sync** (path: see
"Walking" below). After the
solve, `Physics.report` raises the node events: `collide` for each thing a
body started touching (`PhysicsSolver.Entry.touches`, keyed by the other body
or, for obstacles, by which of six ways the surface faces, compared with
`BodyState.touching` from its last simulated step, so resting contact never
fires and a sleeper keeps its set), then `wake`/`sleep` when `asleep` differs
from `BodyState.reportedAsleep` (whatever woke it: a hit, a script). Contacts
are only turned into payloads when something listens (`Centities.listening`).

1. **Snapshot** every node with `physics` against one composition. Each
   carries a `BodyState` (on `Instance`, rebuilt by `redefine`: a reload
   restarts bodies at rest from the file's pose). If the node's rotation
   differs from what physics last wrote, a script or clip set it: re-seed the
   orientation from the pose (and wake).
2. **Obstacles**, in the simulation frame (relative to the anchor, the
   world's axes: `Instance.placed()`, see Coordinate spaces): world blocks from `WorldOps.collisionBoxes` (live collision shapes, loaded
   chunks only, region capped at 32 blocks a side) and the hitboxes of every
   instance within 48 blocks whose boxes reach the body's region, **each box
   separately**, found through `ObstacleGrid` (each instance's boxes measured
   once per change and filed by 4-block cell; `Instance` marks itself dirty on
   any pose, facing, visibility, display or anchor change) and listed in
   instance order, since the solver's result depends on obstacle order — minus the body's own
   subtree and the other bodies of its own instance (those are paired by the
   solver, which is what shares momentum). Bodies of other instances are
   immovable scenery. Sleeping bodies skip this unless due a support check
   (every 20 ticks) or near something awake; unsupported sleepers wake.
3. **Solve** all bodies together (`PhysicsSolver.solve`).
4. **Write** translation (and rotation unless `rotates: false`) through
   `Instance.set`, in the parent's frame (for a root, the centity's turn,
   `Instance.turn()`) — the same path a script's setter takes, so sync and
   read-back need nothing special. Only bodies that moved
   are written, so a settled instance costs nothing.
5. **Re-anchor** (`Physics.reanchor`): once a body is more than a chunk
   (`REANCHOR`, 16 blocks) from the anchor horizontally, the anchor moves
   under it (whole blocks) and every root node moves back by as much, so no
   world position changes but the entities, chunk loading, tracking and the
   index follow the body (the shift is world-axis, so it's turned back into
   the centity's space for the root translations). Skipped while a clip plays (it writes root
   translations itself). A script reading `Centity:position()` sees the
   anchor move; node translations are relative to it, as always.

Nodes a script adds (`add_node`/`add_child`) are bodies and hitboxes like
declared ones (the snapshot, obstacle grid and click routing all read
`Instance.definition`); see "Script-added nodes" in the plugin-runtime skill
for how they're kept and dropped.

Node poses aren't persisted: after a restart a body starts again from its
authored pose, relative to the (possibly moved) anchor and yaw, which are.

## Lua

`Node`: `velocity`/`set_velocity`/`add_velocity` (blocks/s, world axes),
`angular_velocity`/`set_angular_velocity` (degrees/s), `mass`, `apply_impulse`,
`apply_impulse_at` (the point a world position), `apply_force` (an impulse of
force / 20: a push for one tick), `is_on_ground`, `is_asleep`, `wake`, and
three switches on `BodyState`, none saved: `set_gravity` (the snapshot runs
the body with `gravity = 0`), `set_kinematic` and `set_physics_enabled`. A
kinematic or frozen body is **scenery** (`BodyState.scenery`): left out of the
solve, and an obstacle (the box around its collider) for its own centity's
other bodies in `hitboxes()`. A kinematic one is moved by `moveKinematic`
before the snapshot, by its own velocity and spin, through the setters; a
frozen one doesn't move at all and keeps its velocity for when it's let go. Every value is a `Vec3` in world space. Bodies simulate along the
world's axes, so velocities and impulses pass straight through; only the
impulse point moves, to the anchor-relative frame (`Instance.fromAnchor`),
before `Physics.impulse`. Every write wakes the body; non-bodies return
`false`/`vec3.zero`.

## Coordinate spaces

World; the **centity's** (origin at the anchor, turned by the centity's yaw:
root translations are in it); each node's **parent** space (`translation`,
`rotation`, `scale`). `Instance.space()` is the one matrix from centity space
to the world (`translate(anchor) · turn()`), and every conversion the Lua API
offers goes through it (`toWorld`, `toCentity`, `worldMatrix(index)` =
`space() · compose()[index]`, `parentWorldMatrix`). `compose()` uses the pose
as shown (rest plus clips, physics already written), which is what "as shown
now" means for `world_position`, `world_rotation`, `to_local`, `to_world` and
`world_direction`. `look_at` and `rotate` set the node's rotation in its
parent's space (a look rotation keeps +X level; `rotate` pre-multiplies a
parent-space axis-angle), so on a root they work in the turned space.

### Centity yaw

`Instance.yaw` (Minecraft degrees, wrapped into [-180, 180), set only through
`turnTo`; the store gets it when the world saves, see below). `turn()` is the
rotation it means, `rotateY(-yaw)`, with exact quarter turns so a centity
facing a compass direction reports whole numbers. The yaw is applied in one
place per consumer, never on the entities:

- **`Instance.placed()`** = `turn() · compose()[i]`: node matrices relative to
  the anchor along the **world's axes**. Sync pushes these as display
  matrices (the entities stand at the anchor with yaw 0), measures hitbox
  bounds with them (interaction entities are axis-aligned in the world, so a
  turned box's AABB is taken after turning), and click picking casts the
  sight ray in this frame (`fromAnchor` / `atAnchor`), so hit positions and
  normals come back as world values directly.
- **Physics simulates in the `placed()` frame**, not the centity's: gravity,
  `BodyState.velocity`/`angularVelocity`/`orientation`, world blocks, other
  instances' hitboxes and contacts are all along the world's axes, offset by
  the anchor. A turned centity therefore collides with the world exactly as
  an unturned one would. Writing back goes through the parent matrix (a
  root's parent is `turn()`); reanchoring turns its world-axis shift back
  into centity space.
- **`turnTo`** rotates every body's velocity, spin and orientation by the
  change in yaw, so a turn carries the bodies with it rather than leaving
  them pointing the old way (which would also break the writtenRotation
  re-seed check).

The anchor `Location`'s own yaw/pitch are always 0. `centity:location()`
returns the yaw (pitch nil); `nf.centities.spawn` and `centity:teleport` take
a `Location`'s yaw when it has one (`LuaPlace.yaw`), and teleport keeps the
current yaw otherwise. The yaw is saved in `InstanceRecord.yaw` (the
store's `instances.yaw`; non-finite reads as 0) and survives a reload because
`redefine` keeps it. Like the anchor, it's kept in memory and written when the
world saves, the anchor's chunk unloads or the session stops (the
plugin-runtime skill's "Persistence"), so a centity turning every tick writes
nothing.

## Walking (`centity:move_to`)

Our own A*, not the game's pathfinder (centities aren't mobs). Where things are:

| Path (`apps/plugin/runtime/.../`) | Owns                                                                                          |
| --------------------------------- | --------------------------------------------------------------------------------------------- |
| `pathing/Space.kt`                | `BlockShapes` (a block's collision boxes, null = unloaded), `Walker`, `Space.clear`/`floorAt` |
| `pathing/PathSearch.kt`           | the shared `AStar` core, `GroundSearch` (walk), `AirSearch` (fly), smoothing, `NODE_LIMIT`    |
| `centity/WorldShapes.kt`          | `BlockShapes` from `WorldOps.collisionBoxes`: `RegionShapes` (one call), `BrickShapes` (8³)   |
| `centity/CentityPaths.kt`         | `Walk` (on `Instance.walk`), `move_to`'s start, the per-tick step, re-paths, `path_end`       |

- **Platform-free search**, tested alone (`pathing/PathSearchTest`) against a
  map of blocks, and on spawned centities in `CentityPathTest`. It only asks
  `BlockShapes`, i.e. the live **collision** shapes (`GameData`'s server
  facts): a fence is 1.5 tall, a carpet nothing. Unloaded chunks are walls,
  never loaded. Other centities' hitboxes aren't obstacles.
- **The walker** is a square footprint `width` across and `height` tall,
  measured once at `move_to` from the shown hitboxes in the centity's space
  (wider side, height; 1×1 without hitboxes; the options override). Its
  **feet** (bottom middle of the hitboxes, turned into the world) are what
  walks: `Walk.feet` is world state, and each step moves the anchor by as
  much (turning it about the feet when `face`), so physics re-anchoring
  can't disturb it. Grid cells are centred on block centres for odd widths
  and corners for even ones (`Walker.offset`), so a 2-wide walker fits a
  2-wide gap.
- **Walk vs fly cost.** `GroundSearch` nodes are (column, standing height): 8
  neighbours, each one `Space.floorAt` probe (box tops in the footprint from
  `step_height` above to `max_drop` below, highest with head room) plus a
  clear check of the box round both footprints (no corner cutting). It never
  looks at the air above or below the walker. `AirSearch` is separate: 26
  neighbouring cells, each a 3D clear check of the box round the walker at
  both. On the "round a wall" test, walking looked at ~15.7k blocks
  (70 places) and flying ~30k (122). Walking costs nothing extra for flying
  existing; only `fly = true` pays for 3D.
- **Bounded.** One search: `range` from its start (default 48, max 128) and
  `NODE_LIMIT` (4096) expanded places; past either it answers the closest
  place reached (an incomplete `PathResult`), and an empty one only when
  there's no way any closer. `move_to` always searches at once (it answers
  whether there's a way). Searches walks start on their own (following, a
  block in the way, a partial path walked to its end) share `TICK_NODES`
  (16384) a tick and wait for the next tick past it, so a long way is found
  in pieces as it's walked rather than in one stalling search.
- **Smoothing**: from each kept point, the furthest of the next 16 a straight
  line reaches: walking, only on level ground, tested every 0.25 blocks for
  room (box round consecutive samples) and support (so it never walks off an
  edge); flying, room only. Steps and drops keep their grid points.
- **The step** (`CentityPaths.step`, in the pipeline after animate and before
  physics, so bodies settle where the centity was walked to and scripts see
  the result): moves `speed / 20` along the path, checking each stretch it
  passes in one `RegionShapes` read (a step up when something is in the way
  and it fits, else blocked); walking, then held up where it stops or falls
  under gravity (32 b/s², at most 78.4). Blocked or 40 ticks still: a new
  path (at most 3 in a row without reaching a point, then `path_end` false).
  The anchor is set directly; the store gets it when the world saves, as
  for any move.
- **Ends**: a place when its path is walked (reached = feet within 2 blocks;
  a partial path searches on while that gets it 0.5 closer), something it
  follows when the feet are within 2 blocks (re-pathing every 10 ticks if
  the target moved over a block), and gave up when that's gone or in another
  world. `stop_pathing`, `teleport` (`Centities.teleport`), a new `move_to`
  and `Instance.redefine` (a reload: the walk was sized from the old
  hitboxes and its handlers were the old script's) end it without
  `path_end`. Not saved; an unloaded instance isn't ticked, so its walk waits.

## Known limits (shared with the reference)

- **Stacks are unstable**: two boxes resting on each other drift apart over a
  few seconds (the pair normal tilts with the upper body's face). Body-on-world
  contact is stable.
- **A sleeping body is soft** against very slow contact (impulses on it aren't
  integrated); its velocity is zeroed every step so nothing accumulates.
- **Chains of touching bodies** resolve as one event (a Newton's cradle moves
  the whole row).

## How to…

**Tune or change the solver**: `physics/`, then the solver tests; check the
per-instance behaviour in `PhysicsCentityTest` and on a real server
(`pnpm test:integration` drops the example crate and waits for it to settle).

**Add a `physics` field**: format (project-format skill): the field on
`PhysicsDef`, its default and clamp in `ResolvedPhysics.of` (format's
`centity/ResolvedPhysics.kt`; the compiler resolves every body once, so the
solver only ever sees `ResolvedPhysics`), and the default in
`codegen/Constants.kt` if the editor shows it → use it in the
solver/collider → `docs/format/centity.md`.

**Add a body API**: `packages/api/` (`nodeClass`), `pnpm generate`, the new
`NodeApi` method in `api/NodeImpl.kt` using `centities.physics` /
`Instance.body`, a test.
