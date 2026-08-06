// =============================================================================
//  autopearl.java  --  automatic "pearl catching" for the Raven scripting API
// =============================================================================
//
//  MECHANIC
//  --------
//  On servers where a fire charge becomes a real flying fireball, a fireball
//  that collides with an ender pearl detonates it early.  The pearl's owner is
//  teleported to wherever the pearl was when it got hit, not to where they
//  were aiming.  Two uses, one per settings category:
//
//    user  -- you throw a pearl (upward, to save yourself from the void); the
//             script fireballs it and you teleport up to the catch point.
//    enemy -- an opponent pearls; the script pops it so they land somewhere
//             useless instead of where they aimed.
//
//  This script throws FIREBALLS ONLY.  It never throws a pearl for you -- the
//  user category triggers off a pearl you threw yourself.
//
//  SETUP:  run  /autopearl init  in chat, then throw one fireball.
//  ---------------------------------------------------------------
//  Fireball speed is server-specific, so nothing is hardcoded: the command
//  watches your next fireball throw and measures it.  What it records is a
//  distance-vs-time curve, which also captures launch latency (your ping) and
//  the spawn offset, so it works whether the server gives the fireball a flat
//  velocity or an accelerating one.  Saved to script_config.txt; survives
//  restarts.  Until then the HUD says UNCALIBRATED and it uses the fallback
//  speed slider.  Other commands:
//
//      /autopearl init
//      /autopearl whitelist add|remove|list|clear [name]
//      /autopearl status
//
//  (The whitelist is a chat command because the scripting API has no list or
//  text setting type -- only sliders, buttons, keys and colours.)
//
//  MODEL
//  -----
//  Pearls are ballistic:  per tick  pos += vel;  vel *= 0.99;  vel.y -= 0.03.
//  Simulated tick by tick, including where the arc terminates on a block --
//  intercepts are never planned at or after the landing point.
//
//  Fireballs fly straight.  For every candidate launch delay d the solver asks
//  "if the use packet leaves at tick d, when does the expanding fireball front
//  meet the pearl's arc?", solves |pearl(t) - origin| = muzzle + travel(t) by
//  bisection, and scores the intercept.  Choosing *when* to fire is the only
//  freedom you have with a straight projectile -- that is how "Sort by Height"
//  waits for the pearl's apex instead of shooting immediately.
//
//  SILENT AIM AND MOVE FIX
//  -----------------------
//  Aiming goes through Raven's own rotation system, not by touching the
//  player's rotation fields.  getRotations() feeds ClientRotationEvent, which
//  RotationHelper unwraps, fixes and writes into the outgoing movement packet
//  -- the server sees the aim, your camera never moves.
//
//  The move fix is enabled from inside getRotations(), which matters: Raven
//  clears forceMovementFix every tick in onRunTick, and it is consumed during
//  the player update (PostPlayerInputEvent / StrafeEvent / JumpEvent).  Set it
//  anywhere after that -- from onPostUpdate, say -- and it is already stale by
//  the time anything reads it, so your WASD would silently steer relative to
//  the silent-aim yaw instead of your camera.  Setting it in the rotation
//  callback is the same place AimAssist sets it, and it is why strafing while
//  the script is holding an aim still moves you where you are looking.
//
//  TIMING (why this is not off by a tick)
//  --------------------------------------
//  getRotations() runs early in the tick, so by the time onPostUpdate runs the
//  server already has this tick's aim -- but that aim was computed in the
//  *previous* tick's onPostUpdate.  That is self-consistent: the solver reruns
//  every tick and holds the aim, so a shot chosen for absolute tick T keeps the
//  same aim as its delay counts 5, 4, 3 ... down to 0.  As a backstop every
//  shot is gated on missDistance(), which measures the aim the server ACTUALLY
//  has (post fixRotation, post yaw randomisation) against the pearl's current
//  arc.  Side effect: if the client's global "random yaw factor" is turned up,
//  that noise reads as miss distance and the script refuses to fire.
//
//  The trigger/policy layer is isolated at the bottom under one banner.
// =============================================================================


// ---------------------------------------------------------------- constants --

private static final double PEARL_DRAG    = 0.99;   // EntityThrowable air drag
private static final double PEARL_GRAVITY = 0.03;   // EntityThrowable gravity
private static final double SELF_GRAVITY  = 0.08;
private static final double SELF_DRAG_Y   = 0.98;
private static final double SELF_DRAG_XZ  = 0.91;

private static final int PATH_TICKS   = 120;  // how far a pearl arc is simulated
private static final int PROFILE_MAX  = 80;   // max ticks of fireball curve kept
private static final int PROFILE_TICKS = 20;  // ticks of fireball tracked on init
private static final int SOLVE_STEPS  = 24;   // bisection iterations per root
private static final int DELAY_STEP   = 1;    // launch delays are scanned in
                                              // steps of this many ticks
private static final int MAX_PLAN     = 4;    // enemy pearls considered at once

// void metric search bounds (see voidDistance)
private static final int VOID_DOWN = 48;
private static final int VOID_R    = 16;
private static final int VOID_V    = 8;
private static final double VOID_CAP = 96.0;

private static final String CFG_DELAY  = "autopearl.delay";
private static final String CFG_MUZZLE = "autopearl.muzzle";
private static final String CFG_TRAVEL = "autopearl.travel";
private static final String CFG_TAIL   = "autopearl.tail";
private static final String CFG_WHITE  = "autopearl.whitelist";


// ------------------------------------------------------------------- types --

/** A tracked ender pearl plus its simulated future arc. */
private static class Pearl {
    int id;
    Entity ent;
    String owner;          // player name, or null if we never saw it spawn
    boolean mine;
    int age;               // ticks since we first saw it
    Vec3 pos;
    Vec3 vel;              // velocity that will be applied NEXT tick
    Vec3 lastPos;
    boolean hasLast;
    Vec3[] path;           // path[i] = position i+1 ticks from now
    int pathLen;
    boolean lands;         // arc terminates on a block within the horizon
    long firstSeen;
    int shots;
    long lastShotAt;
    // user-category trigger data, captured when the throw packet went out
    long throwTime;
    double throwElevation; // degrees, +90 = straight up, -90 = straight down
    boolean hasThrowData;
}

/** One candidate solution: fire at +delay ticks, hit at +hitTick ticks. */
private static class Shot {
    int pearlId;
    int delay;         // ticks from now until the use packet is sent
    int spawnTick;     // delay + launch delay = when the fireball exists
    double hitTick;    // absolute ticks from now
    double flight;     // hitTick - spawnTick
    Vec3 origin;       // our eye at spawnTick
    Vec3 point;        // intercept point
    float yaw, pitch;
    double distance;
    double value;      // category-specific score, higher is better
}

/** A fireball being measured by /autopearl init. */
private static class FbTrack {
    int id;
    String type;
    int useTick;
    int seenTick;
    Vec3 first;
    double muzzle;
    List<Double> travel = new ArrayList<Double>();
    boolean done;
}

/**
 * Nested classes are resolved lazily, and the script host closes its class
 * loader the instant it has constructed us -- Script.run() does loadClass,
 * newInstance, close, and nothing in between ever touches Pearl / Shot /
 * FbTrack, because the generics in the field initialisers below erase away.
 * The first reflective call into the script is getDeclaredMethods(), which has
 * to resolve the parameter types of every method we declare; by then the loader
 * is dead and the lookup fails with NoClassDefFoundError on sc_<name>$Pearl.
 *
 * Naming the three types here resolves them during class init -- inside
 * newInstance(), while the loader is still open.  Once defined they are cached
 * by name+loader, so later resolution never consults the closed loader at all.
 * Keep this if you add another nested type.
 */
private static final Object[] CLASS_PIN = new Object[]{ Pearl.class, Shot.class, FbTrack.class };


// ------------------------------------------------------------------- state --

private int tick = 0;
private boolean armed = false;

private Map<Integer, Pearl> pearls = new HashMap<Integer, Pearl>();
private List<FbTrack> tracks = new ArrayList<FbTrack>();
private Set<String> whitelist = new HashSet<String>();

// measured fireball model
private int    launchDelay = -1;      // ticks: use packet sent -> fireball exists
private double muzzle      = 0.0;     // blocks from eye to the fireball spawn
private double[] travelCurve = null;  // cumulative blocks travelled per tick
private int    travelLen   = 0;
private double tailSpeed   = 0.0;     // blocks/tick past the end of the curve

// /autopearl init
private boolean initListening = false;

// aiming / firing
private boolean aimActive = false;
private float aimYaw = 0f, aimPitch = 0f;
private int aimHeld = 0;
private int aimTargetId = -1;
private Shot current = null;
private Pearl currentPearl = null;
private double currentMiss = Double.MAX_VALUE;
private long lastThrowAt = 0L;
private long lastEnemyShotAt = 0L;
private int slotSwitchTick = -1000;
private int restoreSlot = -1;

// use-packet bookkeeping (calibration timing + pearl/fireball ownership)
private long lastFireballUse = 0L;
private int  lastFireballTick = -1000;
private long lastPearlUse = 0L;
private double lastPearlElevation = 0.0;

// enemy keybind edge detection
private boolean keyWasDown = false;
private int fireballCount = 0;   // refreshed once per tick

// per-tick caches
private Vec3[] selfEye = null;
private Map<Long, Boolean> passCache = new HashMap<Long, Boolean>();
private Map<Long, Double> voidCache = new HashMap<Long, Double>();

// cached settings (read once per tick)
private double sMinAngle, sUserDelay, sUserSort;
private boolean sEnemyOn;
private double sEnemySort, sEnemyDelay;
private double sMaxDist, sMaxLead, sHitRadius, sExtraLead, sSettle, sFallback;
private boolean sLos, sAutoSwitch, sRestore, sPacketThrow, sSwing, sPredict;
private boolean sArc, sMark, sHud, sDebug;


// =============================================================================
//  LIFECYCLE
// =============================================================================

public void onLoad() {
    modules.registerDescription("Fireballs ender pearls out of the air. Run /autopearl init once per server to measure fireball speed. Whitelist via /autopearl whitelist.");

    modules.registerGroup("user");
    modules.registerSlider("user", "Minimum angle", " deg", 45.0, -90.0, 90.0, 2.0);
    modules.registerSlider("user", "User delay", " s", 1.0, 0.5, 10.0, 0.5);
    modules.registerSlider("user", "User sort by", "", 0, new String[]{ "Height", "Time" });

    modules.registerGroup("enemy");
    modules.registerButton("enemy", "Enabled", true);
    modules.registerKey("enemy", "Keybind", 0);
    modules.registerSlider("enemy", "Enemy sort by", "", 0, new String[]{ "Void", "Distance" });
    modules.registerSlider("enemy", "Enemy delay", " ms", 200.0, 50.0, 1000.0, 50.0);

    modules.registerGroup("engine");
    modules.registerSlider("engine", "Max distance", " blocks", 45.0, 5.0, 120.0, 1.0);
    modules.registerSlider("engine", "Max lead", " ticks", 30.0, 0.0, 60.0, 1.0);
    modules.registerSlider("engine", "Hit radius", " blocks", 0.85, 0.10, 3.0, 0.05);
    modules.registerSlider("engine", "Extra lead", " ticks", 0.0, -5.0, 10.0, 1.0);
    modules.registerSlider("engine", "Aim settle", " ticks", 1.0, 1.0, 5.0, 1.0);
    modules.registerSlider("engine", "Fallback speed", " b/t", 1.60, 0.30, 6.0, 0.05);
    modules.registerButton("engine", "Line of sight", true);
    modules.registerButton("engine", "Auto switch", true);
    modules.registerButton("engine", "Restore slot", true);
    modules.registerButton("engine", "Packet throw", true);
    modules.registerButton("engine", "Swing", true);
    modules.registerButton("engine", "Predict own motion", true);

    modules.registerGroup("visuals");
    modules.registerButton("visuals", "Draw arc", true);
    modules.registerButton("visuals", "Draw intercept", true);
    modules.registerButton("visuals", "HUD", true);
    modules.registerButton("visuals", "Debug", false);

    loadCalibration();
    loadWhitelist();
}

public void onEnable() {
    resetState();
    if (launchDelay < 0) {
        say("&eno fireball data &7- run &f/autopearl init &7and throw one fireball.");
    } else {
        say("&aready&7: &f" + util.round(tailSpeed * 20.0, 1) + " blocks/s&7, launch delay &f"
            + launchDelay + "t&7, muzzle &f" + util.round(muzzle, 2) + "&7.");
    }
}

public void onDisable() {
    releaseAim();
    resetState();
}

private void resetState() {
    armed = false;
    pearls.clear();
    tracks.clear();
    passCache.clear();
    voidCache.clear();
    current = null;
    currentPearl = null;
    currentMiss = Double.MAX_VALUE;
    aimTargetId = -1;
    restoreSlot = -1;
}


// =============================================================================
//  MAIN TICK
// =============================================================================

public void onPostUpdate() {
    tick++;
    passCache.clear();
    voidCache.clear();

    Entity me = client.getPlayer();
    if (me == null) {
        releaseAim();
        return;
    }
    readSettings();
    fireballCount = countFireballs();
    handleEnemyKeybind();
    trackFireballs();
    updatePearls(me);

    armed = engineEnabled(me);
    if (!armed) {
        releaseAim();
        return;
    }

    selfEye = predictSelf((int) sMaxLead + effectiveDelay() + 2);

    // Saving yourself comes before denying somebody else.
    Shot shot = planUserCatch(me);
    if (shot == null) shot = planEnemyCatch(me);

    current = shot;
    currentPearl = shot == null ? null : pearls.get(Integer.valueOf(shot.pearlId));
    if (shot == null || currentPearl == null) {
        releaseAim();
        currentMiss = Double.MAX_VALUE;
        return;
    }

    setAim(shot.yaw, shot.pitch, shot.pearlId);
    prepareSlot();

    // Confidence: where does the shot land using the rotation the SERVER has,
    // against the pearl's current arc?
    currentMiss = missDistance(currentPearl, shot, serverYaw(shot.yaw), serverPitch(shot.pitch));

    if (readyToFire(shot, currentPearl)) {
        if (throwFireball()) {
            lastThrowAt = client.time();
            currentPearl.shots++;
            currentPearl.lastShotAt = lastThrowAt;
            if (!currentPearl.mine) lastEnemyShotAt = lastThrowAt;
            if (sDebug) {
                say("&bfire &7-> " + (currentPearl.mine ? "own" : nameOf(currentPearl))
                    + " pearl, flight &f" + util.round(shot.flight, 1) + "t&7, dist &f"
                    + util.round(shot.distance, 1) + "&7, miss &f" + util.round(currentMiss, 2));
            }
        }
    }
}


// =============================================================================
//  PEARL TRACKING
// =============================================================================

private void updatePearls(Entity me) {
    Set<Integer> alive = new HashSet<Integer>();
    List<Entity> ents = world.getEntities();

    for (int i = 0; i < ents.size(); i++) {
        Entity e = ents.get(i);
        if (e == null || e.type == null) continue;
        if (!e.type.equals("EntityEnderPearl")) continue;
        if (e.isDead()) continue;

        alive.add(Integer.valueOf(e.entityId));
        Pearl p = pearls.get(Integer.valueOf(e.entityId));
        if (p == null) {
            p = new Pearl();
            p.id = e.entityId;
            p.ent = e;
            p.firstSeen = client.time();
            assignOwner(p, e.getPosition(), me);
            pearls.put(Integer.valueOf(p.id), p);
        }
        p.ent = e;
        p.age++;

        Vec3 now = e.getPosition();
        if (p.pos != null) {
            p.lastPos = p.pos;
            p.hasLast = true;
        }
        p.pos = now;

        // Velocity that will be applied on the pearl's next tick.  Prefer the
        // observed displacement (immune to the motion field being overwritten
        // by movement packets) and re-apply one step of drag+gravity to it;
        // fall back to the entity's own motion on the first frame.
        if (p.hasLast) {
            double dx = p.pos.x - p.lastPos.x;
            double dy = p.pos.y - p.lastPos.y;
            double dz = p.pos.z - p.lastPos.z;
            if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
                p.vel = new Vec3(dx * PEARL_DRAG, dy * PEARL_DRAG - PEARL_GRAVITY, dz * PEARL_DRAG);
            } else {
                p.vel = e.getMotion();
            }
        } else {
            p.vel = e.getMotion();
        }
        simulatePearl(p);
    }

    for (Iterator<Map.Entry<Integer, Pearl>> it = pearls.entrySet().iterator(); it.hasNext(); ) {
        Map.Entry<Integer, Pearl> en = it.next();
        if (!alive.contains(en.getKey())) it.remove();
    }
}

/**
 * Forward-simulates the arc: pos += vel; vel *= 0.99; vel.y -= 0.03.
 * The arc stops where the pearl would hit a block -- that is its termination
 * point, and pathLen is a hard bound on any intercept, so the solver can never
 * plan a catch that happens after the pearl has already landed.  The step is
 * sampled along the segment, not just at its end, because a pearl at terminal
 * velocity moves ~3 blocks per tick and would otherwise tunnel through floors.
 */
private void simulatePearl(Pearl p) {
    if (p.path == null) p.path = new Vec3[PATH_TICKS];
    double x = p.pos.x, y = p.pos.y, z = p.pos.z;
    double vx = p.vel.x, vy = p.vel.y, vz = p.vel.z;
    p.lands = false;
    int n = 0;
    for (int i = 0; i < PATH_TICKS; i++) {
        double px = x, py = y, pz = z;
        x += vx; y += vy; z += vz;
        vx *= PEARL_DRAG;
        vy = vy * PEARL_DRAG - PEARL_GRAVITY;
        vz *= PEARL_DRAG;
        p.path[i] = new Vec3(x, y, z);
        n = i + 1;
        if (!segmentClear(px, py, pz, x, y, z)) {
            p.lands = true;
            break;
        }
        if (y < -80.0) break;
    }
    p.pathLen = n;
}

/** Pearl position at t ticks from now (t may be fractional). */
private Vec3 pearlAt(Pearl p, double t) {
    if (t <= 0) return p.pos;
    int i = (int) Math.floor(t);
    double f = t - i;
    Vec3 a = pearlTick(p, i);
    Vec3 b = pearlTick(p, i + 1);
    if (a == null) return null;
    if (b == null || f <= 0.0) return a;
    return new Vec3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
}

private Vec3 pearlTick(Pearl p, int t) {
    if (t <= 0) return p.pos;
    if (t - 1 >= p.pathLen) return null;
    return p.path[t - 1];
}


// =============================================================================
//  INTERCEPT SOLVER
// =============================================================================

/**
 * Solves the intercept for every launch delay, returning an array indexed by
 * delay (null where no intercept exists).  Computing the whole family once and
 * reusing it is what makes the enemy ordering search affordable: choosing a
 * shot order only re-reads this table, it never re-solves.
 */
private Shot[] shotsByDelay(Pearl p) {
    int maxDelay = (int) sMaxLead;
    Shot[] out = new Shot[maxDelay + 1];
    for (int d = 0; d <= maxDelay; d += DELAY_STEP) {
        out[d] = solveForDelay(p, d);
    }
    return out;
}

private Shot solveForDelay(Pearl p, int delay) {
    int spawn = delay + effectiveDelay();
    Vec3 origin = eyeAt(spawn);
    if (origin == null) return null;

    double prev = Double.NaN;
    for (int t = spawn; t <= p.pathLen; t++) {
        Vec3 pp = pearlTick(p, t);
        if (pp == null) break;
        double f = origin.distanceTo(pp) - (muzzleOf() + travel(t - spawn));
        if (!Double.isNaN(prev) && prev > 0.0 && f <= 0.0) {
            double lo = t - 1, hi = t;
            for (int i = 0; i < SOLVE_STEPS; i++) {
                double mid = (lo + hi) * 0.5;
                Vec3 mp = pearlAt(p, mid);
                if (mp == null) { hi = mid; continue; }
                double fm = origin.distanceTo(mp) - (muzzleOf() + travel(mid - spawn));
                if (fm > 0.0) lo = mid; else hi = mid;
            }
            double hit = (lo + hi) * 0.5;
            Vec3 point = pearlAt(p, hit);
            if (point == null) return null;

            Shot s = new Shot();
            s.pearlId = p.id;
            s.delay = delay;
            s.spawnTick = spawn;
            s.hitTick = hit;
            s.flight = hit - spawn;
            s.origin = origin;
            s.point = point;
            s.distance = origin.distanceTo(point);
            float[] rot = rotationsTo(origin, point);
            s.yaw = rot[0];
            s.pitch = rot[1];
            return s;
        }
        prev = f;
    }
    return null;   // pearl is not catchable from this launch time
}

/**
 * Closest approach between the fireball line and the pearl arc for a given
 * rotation -- "if the shot went out now with the rotation the server has, how
 * far would it miss?".  This is the confidence gate.
 */
private double missDistance(Pearl p, Shot s, float yaw, float pitch) {
    if (p == null || s == null) return Double.MAX_VALUE;
    Vec3 dir = lookVector(yaw, pitch);
    double end = Math.min(p.pathLen, s.spawnTick + s.flight * 2.0 + 6.0);

    double best = Double.MAX_VALUE;
    double bestT = s.spawnTick;
    for (double t = s.spawnTick; t <= end; t += 0.25) {
        double d = separation(p, s, dir, t);
        if (d < best) { best = d; bestT = t; }
        else if (d > best + 8.0) break;      // diverging, stop early
    }
    if (best == Double.MAX_VALUE) return best;

    // The true closest approach almost never lands on the sample grid, and at
    // a couple of blocks per tick a coarse minimum reads ~0.2 blocks too high
    // -- enough to veto shots that would actually connect.  Refine it.
    double lo = Math.max(s.spawnTick, bestT - 0.25);
    double hi = Math.min(end, bestT + 0.25);
    for (int i = 0; i < 30 && hi - lo > 1.0E-5; i++) {
        double m1 = lo + (hi - lo) / 3.0;
        double m2 = hi - (hi - lo) / 3.0;
        if (separation(p, s, dir, m1) <= separation(p, s, dir, m2)) hi = m2; else lo = m1;
    }
    return Math.min(best, separation(p, s, dir, (lo + hi) * 0.5));
}

/** Distance between the fireball and the pearl at t ticks from now. */
private double separation(Pearl p, Shot s, Vec3 dir, double t) {
    Vec3 pp = pearlAt(p, t);
    if (pp == null) return Double.MAX_VALUE;
    double d = muzzleOf() + travel(t - s.spawnTick);
    double dx = s.origin.x + dir.x * d - pp.x;
    double dy = s.origin.y + dir.y * d - pp.y;
    double dz = s.origin.z + dir.z * d - pp.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

/** Measured cumulative distance travelled j ticks after the fireball spawns. */
private double travel(double j) {
    if (j <= 0.0) return 0.0;
    if (travelCurve == null || travelLen < 2) return sFallback * j;
    int i = (int) Math.floor(j);
    if (i >= travelLen - 1) return travelCurve[travelLen - 1] + tailSpeed * (j - (travelLen - 1));
    double f = j - i;
    return travelCurve[i] * (1.0 - f) + travelCurve[i + 1] * f;
}

private double muzzleOf() {
    return launchDelay < 0 ? 0.0 : muzzle;
}

/** Ticks between the use packet leaving and the fireball existing. */
private int effectiveDelay() {
    int base = launchDelay >= 0 ? launchDelay : pingTicks() + 1;
    int d = base + (int) sExtraLead;
    return d < 0 ? 0 : d;
}

private int pingTicks() {
    try {
        Entity me = client.getPlayer();
        if (me != null) {
            NetworkPlayer np = me.getNetworkPlayer();
            if (np != null && np.getPing() > 0) return (int) Math.round(np.getPing() / 50.0);
        }
    } catch (Throwable ignored) {}
    return 2;
}


// =============================================================================
//  SELF PREDICTION
// =============================================================================

/** Our eye position at each of the next `ticks` ticks. */
private Vec3[] predictSelf(int ticks) {
    if (ticks < 1) ticks = 1;
    Vec3[] out = new Vec3[ticks + 1];
    Entity me = client.getPlayer();
    if (me == null) return out;
    Vec3 p = me.getPosition();
    double eye = me.getEyeHeight();
    out[0] = new Vec3(p.x, p.y + eye, p.z);

    if (sPredict) {
        try {
            Simulation sim = Simulation.create();
            for (int i = 1; i <= ticks; i++) {
                sim.tick();
                Vec3 sp = sim.getPosition();
                out[i] = new Vec3(sp.x, sp.y + eye, sp.z);
            }
            return out;
        } catch (Throwable ignored) {}

        Vec3 m = me.getMotion();
        double x = p.x, y = p.y, z = p.z;
        double vx = m.x, vy = m.y, vz = m.z;
        boolean ground = me.onGround();
        for (int i = 1; i <= ticks; i++) {
            x += vx;
            z += vz;
            if (ground) { vy = 0.0; } else { y += vy; vy = (vy - SELF_GRAVITY) * SELF_DRAG_Y; }
            vx *= SELF_DRAG_XZ;
            vz *= SELF_DRAG_XZ;
            out[i] = new Vec3(x, y + eye, z);
        }
        return out;
    }

    for (int i = 1; i <= ticks; i++) out[i] = out[0];
    return out;
}

private Vec3 eyeAt(int t) {
    if (selfEye == null || selfEye.length == 0) return null;
    if (t < 0) t = 0;
    if (t >= selfEye.length) t = selfEye.length - 1;
    return selfEye[t];
}


// =============================================================================
//  /autopearl init  --  fireball velocity measurement
// =============================================================================

/** Follows the fireball we are measuring and records distance vs time. */
private void trackFireballs() {
    for (int i = tracks.size() - 1; i >= 0; i--) {
        FbTrack t = tracks.get(i);
        Entity e = world.getEntityById(t.id);
        if (e == null || e.isDead() || t.travel.size() >= PROFILE_TICKS) {
            finishTrack(t);
            tracks.remove(i);
            continue;
        }
        t.travel.add(Double.valueOf(t.first.distanceTo(e.getPosition())));
    }
}

private void finishTrack(FbTrack t) {
    if (t.done) return;
    t.done = true;
    if (!initListening) return;

    if (t.travel.size() < 4) {
        say("&cthat fireball died after " + t.travel.size() + " ticks &7- throw another one with a clear line of sight.");
        return;
    }

    double[] curve = new double[t.travel.size()];
    for (int i = 0; i < curve.length; i++) curve[i] = t.travel.get(i).doubleValue();
    if (curve.length > PROFILE_MAX) curve = Arrays.copyOf(curve, PROFILE_MAX);

    launchDelay = Math.max(0, t.seenTick - t.useTick);
    muzzle = t.muzzle;
    travelCurve = curve;
    travelLen = curve.length;
    tailSpeed = curve.length >= 4
        ? (curve[curve.length - 1] - curve[curve.length - 4]) / 3.0
        : curve[curve.length - 1] / (curve.length - 1);

    saveCalibration();
    initListening = false;   // disable the init entity listener

    client.print(util.color("&a[Success] &7logged velocity of entity &f" + t.type + " &7at &f"
        + util.round(tailSpeed * 20.0, 2) + " blocks/s"));
    if (sDebug) {
        say("&8launch delay " + launchDelay + "t, muzzle " + util.round(muzzle, 2)
            + ", curve " + travelLen + "t");
    }
}

private void saveCalibration() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < travelLen; i++) {
        if (i > 0) sb.append(",");
        sb.append(util.round(travelCurve[i], 4));
    }
    config.set(CFG_DELAY, String.valueOf(launchDelay));
    config.set(CFG_MUZZLE, String.valueOf(util.round(muzzle, 4)));
    config.set(CFG_TAIL, String.valueOf(util.round(tailSpeed, 4)));
    config.set(CFG_TRAVEL, sb.toString());
}

private void loadCalibration() {
    try {
        String d = config.get(CFG_DELAY);
        String m = config.get(CFG_MUZZLE);
        String tl = config.get(CFG_TAIL);
        String tr = config.get(CFG_TRAVEL);
        if (d == null || tr == null || tr.length() == 0) return;
        String[] parts = tr.split(",");
        double[] curve = new double[parts.length];
        for (int i = 0; i < parts.length; i++) curve[i] = Double.parseDouble(parts[i].trim());
        launchDelay = Integer.parseInt(d.trim());
        muzzle = m == null ? 0.0 : Double.parseDouble(m.trim());
        tailSpeed = tl == null ? 0.0 : Double.parseDouble(tl.trim());
        travelCurve = curve;
        travelLen = curve.length;
    } catch (Throwable t) {
        launchDelay = -1;
        travelCurve = null;
        travelLen = 0;
    }
}


// =============================================================================
//  CHAT COMMANDS
// =============================================================================

/** Returns true if the message was a command (and should not reach the server). */
private boolean handleCommand(String raw) {
    String msg = raw.trim();
    if (!msg.toLowerCase().startsWith("/autopearl")) return false;

    String rest = msg.length() > 10 ? msg.substring(10).trim() : "";
    String[] a = rest.length() == 0 ? new String[0] : rest.split("\\s+");

    if (a.length == 0) {
        say("&f/autopearl init &8- measure fireball velocity");
        say("&f/autopearl whitelist add|remove|list|clear [name]");
        say("&f/autopearl status");
        return true;
    }

    String cmd = a[0].toLowerCase();

    if (cmd.equals("init")) {
        initListening = true;
        tracks.clear();
        say("&blistening &7- throw one fireball now.");
        return true;
    }

    if (cmd.equals("status")) {
        if (launchDelay < 0) {
            say("&euncalibrated &7- using fallback &f" + util.round(sFallback * 20.0, 1) + " blocks/s");
        } else {
            say("&f" + util.round(tailSpeed * 20.0, 2) + " blocks/s&7, launch delay &f" + launchDelay
                + "t&7, muzzle &f" + util.round(muzzle, 2) + "&7, curve &f" + travelLen + "t");
        }
        say("&7whitelist: &f" + (whitelist.isEmpty() ? "empty" : joinNames()));
        say("&7fireballs in hotbar: &f" + countFireballs());
        return true;
    }

    if (cmd.equals("whitelist") || cmd.equals("wl")) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        if (sub.equals("list")) {
            say("&7whitelist: &f" + (whitelist.isEmpty() ? "empty" : joinNames()));
        } else if (sub.equals("clear")) {
            whitelist.clear();
            saveWhitelist();
            say("&7whitelist cleared.");
        } else if (sub.equals("add") && a.length > 2) {
            whitelist.add(a[2].toLowerCase());
            saveWhitelist();
            say("&aadded &f" + a[2]);
        } else if ((sub.equals("remove") || sub.equals("del")) && a.length > 2) {
            whitelist.remove(a[2].toLowerCase());
            saveWhitelist();
            say("&cremoved &f" + a[2]);
        } else {
            say("&7usage: &f/autopearl whitelist add|remove|list|clear [name]");
        }
        return true;
    }

    say("&7unknown command. &f/autopearl &7for help.");
    return true;
}

private String joinNames() {
    StringBuilder sb = new StringBuilder();
    for (Iterator<String> it = whitelist.iterator(); it.hasNext(); ) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(it.next());
    }
    return sb.toString();
}

private void saveWhitelist() {
    config.set(CFG_WHITE, joinNames().replace(" ", ""));
}

private void loadWhitelist() {
    String s = config.get(CFG_WHITE);
    if (s == null || s.trim().length() == 0) return;
    String[] parts = s.split(",");
    for (int i = 0; i < parts.length; i++) {
        String n = parts[i].trim().toLowerCase();
        if (n.length() > 0) whitelist.add(n);
    }
}


// =============================================================================
//  EVENTS
// =============================================================================

/**
 * Silent aim.  Raven's rotation system takes these, unwraps and fixes them and
 * writes them into the outgoing movement packet -- the server sees the aim,
 * the camera does not move.
 *
 * The move fix MUST be enabled here rather than from the tick callback:
 * forceMovementFix is cleared every tick in RotationHelper.onRunTick, and it
 * is read during the player update (PostPlayerInputEvent / StrafeEvent /
 * JumpEvent), which happens before onPostUpdate.  Setting it there would
 * always be too late, and your movement would silently steer relative to the
 * silent-aim yaw.  This is the same place AimAssist sets it.
 */
public Float[] getRotations() {
    if (!aimActive) return null;
    client.enableMovementFix();
    return new Float[]{ Float.valueOf(aimYaw), Float.valueOf(aimPitch) };
}

public boolean onPacketSent(CPacket packet) {
    // Chat commands are intercepted here and never reach the server.
    if (packet instanceof C01) {
        String msg = ((C01) packet).message;
        if (msg != null && handleCommand(msg)) return false;
        return true;
    }

    if (packet instanceof C08) {
        ItemStack st = ((C08) packet).itemStack;
        if (st != null) {
            if (isFireball(st)) {
                lastFireballUse = client.time();
                lastFireballTick = tick;
            } else if (isPearl(st)) {
                // user category triggers off the head angle at THROW time, not
                // off the pearl's own launch angle
                lastPearlUse = client.time();
                Entity me = client.getPlayer();
                lastPearlElevation = me == null ? 0.0 : -me.getPitch();
            }
        }
    }
    return true;
}

public void onWorldJoin(Entity entity) {
    if (entity == null || entity.type == null) return;
    Entity me = client.getPlayer();
    if (me == null) return;

    if (entity.type.equals("EntityEnderPearl")) {
        Pearl p = new Pearl();
        p.id = entity.entityId;
        p.ent = entity;
        p.firstSeen = client.time();
        assignOwner(p, entity.getPosition(), me);
        if (p.mine && client.time() - lastPearlUse < 2000L) {
            p.throwTime = lastPearlUse;
            p.throwElevation = lastPearlElevation;
            p.hasThrowData = true;
        }
        pearls.put(Integer.valueOf(p.id), p);
        if (sDebug) {
            say("&7pearl &f" + p.id + "&7 from &f" + (p.mine ? "you" : nameOf(p))
                + (p.hasThrowData ? " &7at &f" + util.round(p.throwElevation, 1) + " deg" : ""));
        }
        return;
    }

    // Fireball we just threw -- the /autopearl init measurement target.
    if (entity.type.indexOf("Fireball") >= 0) {
        if (!initListening) return;
        if (client.time() - lastFireballUse > 2000L) return;
        Vec3 spawn = entity.getPosition();
        Vec3 eye = eyeOf(me);
        double dSelf = eye.distanceTo(spawn);
        if (dSelf > 8.0 || !isClosestPlayer(spawn, dSelf)) return;

        FbTrack t = new FbTrack();
        t.id = entity.entityId;
        t.type = entity.type;
        t.useTick = lastFireballTick;
        t.seenTick = tick;
        t.first = spawn;
        t.muzzle = dSelf;
        t.travel.add(Double.valueOf(0.0));
        tracks.add(t);
    }
}


// =============================================================================
//  AIM / THROW PLUMBING
// =============================================================================

private void setAim(float yaw, float pitch, int targetId) {
    if (aimActive && aimTargetId == targetId) aimHeld++;
    else aimHeld = 0;
    aimActive = true;
    aimYaw = yaw;
    aimPitch = pitch;
    aimTargetId = targetId;
}

private void releaseAim() {
    aimActive = false;
    aimHeld = 0;
    aimTargetId = -1;
    if (sRestore && restoreSlot >= 0) {
        inventory.setSlot(restoreSlot);
        restoreSlot = -1;
    }
}

private float serverYaw(float fallback) {
    Float f = client.getServerYaw();
    return f == null ? fallback : f.floatValue();
}

private float serverPitch(float fallback) {
    Float f = client.getServerPitch();
    return f == null ? fallback : f.floatValue();
}

/** True once the server has actually been told our aim. */
private boolean aimSettled() {
    if (aimHeld < (int) sSettle) return false;
    Float sy = client.getServerYaw();
    Float sp = client.getServerPitch();
    if (sy == null || sp == null) return true;
    return Math.abs(wrap(sy.floatValue() - aimYaw)) < 2.0
        && Math.abs(sp.floatValue() - aimPitch) < 2.0;
}

/** Moves to the fireball slot early so the switch overlaps the aim settle. */
private void prepareSlot() {
    if (!sAutoSwitch) return;
    int slot = findFireballSlot();
    if (slot < 0 || inventory.getSlot() == slot) return;
    if (restoreSlot < 0) restoreSlot = inventory.getSlot();
    inventory.setSlot(slot);
    slotSwitchTick = tick;
}

private boolean throwFireball() {
    int slot = findFireballSlot();
    if (slot < 0) return false;
    if (inventory.getSlot() != slot) {
        prepareSlot();
        return false;
    }
    // one tick for the vanilla held-item packet to reach the server
    if (tick - slotSwitchTick < 1) return false;

    ItemStack stack = inventory.getStackInSlot(slot);
    if (stack == null) return false;

    if (sPacketThrow) {
        // vanilla "use item in air": position -1/-1/-1, direction 255
        client.sendPacket(new C08(stack, new Vec3(-1.0, -1.0, -1.0), 255, new Vec3(0.0, 0.0, 0.0)));
        if (sSwing) client.swing();
    } else {
        keybinds.rightClick();
    }
    lastFireballUse = client.time();
    lastFireballTick = tick;
    return true;
}

/** A fire charge, including one renamed to "Fireball" / "Fire Ball". */
private boolean isFireball(ItemStack s) {
    if (s == null) return false;
    if (s.name != null && s.name.equals("fire_charge")) return true;
    if (s.displayName == null) return false;
    String d = util.strip(s.displayName).toLowerCase().replace(" ", "");
    return d.indexOf("fireball") >= 0;
}

private boolean isPearl(ItemStack s) {
    return s != null && s.name != null && s.name.equals("ender_pearl");
}

private int findFireballSlot() {
    for (int i = 0; i < 9; i++) {
        if (isFireball(inventory.getStackInSlot(i))) return i;
    }
    return -1;
}

/** Total fireballs available in the hotbar. */
private int countFireballs() {
    int n = 0;
    for (int i = 0; i < 9; i++) {
        ItemStack s = inventory.getStackInSlot(i);
        if (isFireball(s)) n += Math.max(1, s.stackSize);
    }
    return n;
}


// =============================================================================
//  GEOMETRY / WORLD HELPERS
// =============================================================================

private Vec3 eyeOf(Entity e) {
    Vec3 p = e.getPosition();
    return new Vec3(p.x, p.y + e.getEyeHeight(), p.z);
}

private float[] rotationsTo(Vec3 from, Vec3 to) {
    double dx = to.x - from.x;
    double dy = to.y - from.y;
    double dz = to.z - from.z;
    double horiz = Math.sqrt(dx * dx + dz * dz);
    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
    return new float[]{ wrap(yaw), pitch };
}

private Vec3 lookVector(float yaw, float pitch) {
    double y = Math.toRadians(yaw);
    double p = Math.toRadians(pitch);
    double cp = Math.cos(p);
    return new Vec3(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
}

private float wrap(float deg) {
    deg = deg % 360.0f;
    if (deg >= 180.0f) deg -= 360.0f;
    if (deg < -180.0f) deg += 360.0f;
    return deg;
}

private double manhattan(Vec3 a, Vec3 b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
}

/**
 * Manhattan distance from a point to the nearest solid, standable block --
 * "how deep into the void is this".  Straight down first (the common case, and
 * cheap), then a coarse 8-direction probe at increasing radius.  The probe is
 * deliberately approximate: this is a ranking heuristic, and an exact nearest
 * -block search would be tens of thousands of block lookups per tick.
 */
private double voidDistance(Vec3 p) {
    int px = (int) Math.floor(p.x), py = (int) Math.floor(p.y), pz = (int) Math.floor(p.z);
    Long key = packed(px, py, pz);
    Double memo = voidCache.get(key);
    if (memo != null) return memo.doubleValue();

    double result = VOID_CAP;
    for (int dy = 0; dy <= VOID_DOWN; dy++) {
        int y = py - dy;
        if (y < 0) break;
        if (isStandable(px, y, pz)) { result = dy; break; }
    }
    if (result >= VOID_CAP) {
        boolean found = false;
        for (int r = 2; r <= VOID_R && !found; r += 2) {
            for (int a = 0; a < 8 && !found; a++) {
                double ang = a * Math.PI / 4.0;
                int x = px + (int) Math.round(Math.cos(ang) * r);
                int z = pz + (int) Math.round(Math.sin(ang) * r);
                for (int dy = -VOID_V; dy <= VOID_V; dy++) {
                    int y = py + dy;
                    if (y < 0) continue;
                    if (isStandable(x, y, z)) {
                        result = Math.abs(x - px) + Math.abs(y - py) + Math.abs(z - pz);
                        found = true;
                        break;
                    }
                }
            }
        }
    }
    voidCache.put(key, Double.valueOf(result));
    return result;
}

private boolean isStandable(int x, int y, int z) {
    return !isPassable(x + 0.5, y + 0.5, z + 0.5) && isPassable(x + 0.5, y + 1.5, z + 0.5);
}

private Long packed(int x, int y, int z) {
    return Long.valueOf(((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL));
}

private boolean isPassable(double x, double y, double z) {
    int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
    // Ground scans and line-of-sight sweeps hit the same columns over and over
    // within a tick, and every getBlockAt allocates.  Memoise per tick.
    Long key = packed(bx, by, bz);
    Boolean hit = passCache.get(key);
    if (hit != null) return hit.booleanValue();
    boolean res = computePassable(bx, by, bz);
    passCache.put(key, Boolean.valueOf(res));
    return res;
}

private boolean computePassable(int bx, int by, int bz) {
    try {
        Block b = world.getBlockAt(bx, by, bz);
        if (b == null || b.name == null) return true;
        String n = b.name;
        if (n.equals("air")) return true;
        if (n.equals("water") || n.equals("flowing_water")) return true;
        if (n.equals("lava") || n.equals("flowing_lava")) return true;
        if (n.equals("tallgrass") || n.equals("deadbush") || n.equals("web")
            || n.equals("yellow_flower") || n.equals("red_flower") || n.equals("double_plant")
            || n.equals("torch") || n.equals("redstone_torch") || n.equals("unlit_redstone_torch")
            || n.equals("fire") || n.equals("snow_layer") || n.equals("carpet")
            || n.equals("sapling") || n.equals("wheat") || n.equals("reeds")
            || n.equals("rail") || n.equals("golden_rail") || n.equals("detector_rail")
            || n.equals("activator_rail") || n.equals("vine") || n.equals("wall_sign")
            || n.equals("standing_sign") || n.equals("ladder")) return true;
        return false;
    } catch (Throwable t) {
        return true;
    }
}

private boolean segmentClear(double ax, double ay, double az, double bx, double by, double bz) {
    double dx = bx - ax, dy = by - ay, dz = bz - az;
    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 0.001) return isPassable(bx, by, bz);
    int steps = (int) Math.ceil(len / 0.5);
    if (steps > 400) steps = 400;
    for (int i = 1; i <= steps; i++) {
        double f = (double) i / steps;
        if (!isPassable(ax + dx * f, ay + dy * f, az + dz * f)) return false;
    }
    return true;
}

/** Sampled line-of-sight test, used to reject shots that hit terrain first. */
private boolean pathClear(Vec3 a, Vec3 b) {
    return segmentClear(a.x, a.y, a.z, b.x, b.y, b.z);
}

private void assignOwner(Pearl p, Vec3 spawn, Entity me) {
    double bestDist = Double.MAX_VALUE;
    Entity best = null;
    List<Entity> players = world.getPlayerEntities();
    for (int i = 0; i < players.size(); i++) {
        Entity e = players.get(i);
        if (e == null || e.isDead()) continue;
        double d = eyeOf(e).distanceTo(spawn);
        if (d < bestDist) {
            bestDist = d;
            best = e;
        }
    }
    if (best == null || bestDist > 6.0) {
        p.owner = null;
        p.mine = false;
        return;
    }
    p.owner = best.getName();
    p.mine = best.isUser || (client.time() - lastPearlUse < 2000L && eyeOf(me).distanceTo(spawn) <= bestDist + 0.01);
}

private boolean isClosestPlayer(Vec3 spawn, double selfDist) {
    List<Entity> players = world.getPlayerEntities();
    for (int i = 0; i < players.size(); i++) {
        Entity e = players.get(i);
        if (e == null || e.isUser || e.isDead()) continue;
        if (eyeOf(e).distanceTo(spawn) < selfDist) return false;
    }
    return true;
}

private String nameOf(Pearl p) {
    return p.owner == null ? "?" : p.owner;
}

private void say(String msg) {
    client.print(util.color("&8[&bautopearl&8] &7" + msg));
}


// =============================================================================
//  RENDERING
// =============================================================================

public void onRenderWorld(float partialTicks) {
    if (!armed) return;
    int arcOwn   = new Color(90, 200, 255, 170).getRGB();
    int arcEnemy = new Color(255, 110, 90, 170).getRGB();
    int hitCol   = new Color(120, 255, 140, 220).getRGB();
    int badCol   = new Color(255, 200, 60, 220).getRGB();

    if (sArc) {
        for (Iterator<Pearl> it = pearls.values().iterator(); it.hasNext(); ) {
            Pearl p = it.next();
            if (p.pos == null || p.pathLen < 2) continue;
            int col = p.mine ? arcOwn : arcEnemy;
            Vec3 prev = p.pos;
            for (int i = 0; i < p.pathLen; i += 2) {
                render.line3D(prev, p.path[i], 1.5f, col);
                prev = p.path[i];
            }
        }
    }

    if (sMark && current != null) {
        Vec3 c = current.point;
        int col = currentMiss <= sHitRadius ? hitCol : badCol;
        double s = 0.45;
        render.line3D(new Vec3(c.x - s, c.y, c.z), new Vec3(c.x + s, c.y, c.z), 2.0f, col);
        render.line3D(new Vec3(c.x, c.y - s, c.z), new Vec3(c.x, c.y + s, c.z), 2.0f, col);
        render.line3D(new Vec3(c.x, c.y, c.z - s), new Vec3(c.x, c.y, c.z + s), 2.0f, col);
        render.line3D(current.origin, c, 1.0f, col);
        render.text3d(util.round(current.flight, 1) + "t / " + util.round(currentMiss, 2),
                      new Vec3(c.x, c.y + 0.6, c.z), 1.0f, true, false, true, col);
    }
}

public void onRenderTick(float partialTicks) {
    if (!sHud) return;
    int[] size = client.getDisplaySize();
    float x = 4.0f;
    float y = size[1] - 46.0f;
    int white = new Color(230, 230, 230).getRGB();
    int good = new Color(120, 255, 140).getRGB();
    int warn = new Color(255, 190, 60).getRGB();

    String cal = launchDelay < 0
        ? "UNCALIBRATED - run /autopearl init"
        : util.round(tailSpeed * 20.0, 1) + " b/s, delay " + launchDelay + "t";
    render.text2d("autopearl: " + cal + (initListening ? "  [waiting for your fireball]" : ""),
                  x, y, 1.0f, launchDelay < 0 || initListening ? warn : white, true);
    y += 10.0f;

    if (current != null && currentPearl != null) {
        String who = currentPearl.mine ? "own" : nameOf(currentPearl);
        render.text2d(who + " pearl  " + util.round(current.distance, 1) + "m  wait "
                      + current.delay + "t  flight " + util.round(current.flight, 1) + "t  miss "
                      + util.round(currentMiss, 2), x, y, 1.0f,
                      currentMiss <= sHitRadius && current.delay == 0 ? good : white, true);
    } else {
        render.text2d("no target (" + pearls.size() + " pearls, " + fireballCount + " fireballs)",
                      x, y, 1.0f, white, true);
    }
    y += 10.0f;
    if (!sEnemyOn) render.text2d("enemy catching off", x, y, 1.0f, warn, true);
}


// #############################################################################
// #                                                                           #
// #   TRIGGER LAYER  --  EDIT BELOW THIS LINE                                 #
// #                                                                           #
// #   Everything above is the engine: it tracks pearls, measures fireballs,   #
// #   solves intercepts and pulls the trigger when told to.  It never picks   #
// #   a target or a moment on its own.  All of that policy lives here:        #
// #                                                                           #
// #     engineEnabled(me)    master gate - run at all this tick?              #
// #     planUserCatch(me)    the `user` category                              #
// #     planEnemyCatch(me)   the `enemy` category, incl. the shot ordering    #
// #     shotAllowed(p, s)    hard filter on a candidate intercept             #
// #     readyToFire(s, p)    final gate before the use packet goes out        #
// #                                                                           #
// #   Per pearl: p.mine, p.owner, p.age, p.shots, p.pos, p.vel, p.pathLen,    #
// #   p.lands, p.throwElevation, p.throwTime.  Per shot: s.delay, s.flight,   #
// #   s.distance, s.point, s.hitTick, s.origin.  Plus currentMiss and every   #
// #   s* setting field.                                                       #
// #                                                                           #
// #############################################################################

/** Master gate: may the script do anything this tick? */
private boolean engineEnabled(Entity me) {
    if (me.isDead()) return false;
    if (client.isSpectator()) return false;
    // Any open GUI (chat, inventory, menu) stands the script down.
    if (client.getScreen() != null && !client.getScreen().isEmpty()) return false;
    // Nothing to throw.
    if (fireballCount < 1) return false;
    return true;
}

// ----------------------------------------------------------------- user ----

/**
 * `user` category.  Fires at a pearl YOU threw, but only if your head was
 * pitched above "Minimum angle" at the moment you threw it (+90 is straight
 * up, -90 straight down), and only once "User delay" seconds have passed since
 * the throw.  Minimum angle 90 disables the category, since no throw can
 * exceed it.
 */
private Shot planUserCatch(Entity me) {
    if (sMinAngle >= 90.0) return null;

    Shot best = null;
    for (Iterator<Pearl> it = pearls.values().iterator(); it.hasNext(); ) {
        Pearl p = it.next();
        if (!p.mine || p.pos == null || p.vel == null || p.pathLen < 2) continue;
        if (!p.hasThrowData) continue;
        // the user's head angle at the throw, NOT the pearl's own angle
        if (p.throwElevation <= sMinAngle) continue;
        if (client.time() - p.throwTime < (long) (sUserDelay * 1000.0)) continue;
        if (p.shots > 0 && client.time() - p.lastShotAt < 400L) continue;

        Shot[] all = shotsByDelay(p);
        for (int d = 0; d < all.length; d++) {
            Shot s = all[d];
            if (s == null || !shotAllowed(p, s)) continue;
            // Sort by: 0 = Height (highest catch point), 1 = Time (soonest)
            s.value = sUserSort < 0.5 ? s.point.y : -s.hitTick;
            if (best == null || s.value > best.value) best = s;
        }
    }
    return best;
}

// ---------------------------------------------------------------- enemy ----

/**
 * `enemy` category.  Every enemy pearl in the air gets a slot in a firing
 * order, slot k going out k * "Enemy delay" ms from now.  A pearl's value in a
 * slot is the best intercept it still has at that launch time, measured by
 * "Enemy sort by" (Void = furthest from standable ground, Distance = furthest
 * from you).  We pick the ordering with the highest total value and shoot
 * whoever holds slot 0.  Recomputed every tick, so it re-plans as pearls move.
 */
private Shot planEnemyCatch(Entity me) {
    if (!sEnemyOn) return null;

    List<Pearl> cand = new ArrayList<Pearl>();
    for (Iterator<Pearl> it = pearls.values().iterator(); it.hasNext(); ) {
        Pearl p = it.next();
        if (!wantEnemyPearl(p, me)) continue;
        cand.add(p);
    }
    if (cand.isEmpty()) return null;

    // Only as many shots as we have fireballs for, and cap the search width.
    int slots = Math.min(Math.min(cand.size(), fireballCount), MAX_PLAN);
    if (slots < 1) return null;
    if (cand.size() > MAX_PLAN) cand = nearest(cand, me, MAX_PLAN);

    int delayTicks = Math.max(1, (int) Math.round(sEnemyDelay / 50.0));
    int n = cand.size();

    // value[i][slot] and the shot that achieves it
    double[][] value = new double[n][slots];
    Shot[][] pick = new Shot[n][slots];
    for (int i = 0; i < n; i++) {
        Pearl p = cand.get(i);
        Shot[] all = shotsByDelay(p);
        for (int slot = 0; slot < slots; slot++) {
            int minDelay = slot * delayTicks;
            double bestVal = Double.NEGATIVE_INFINITY;
            Shot bestShot = null;
            for (int d = minDelay; d < all.length; d++) {
                Shot s = all[d];
                if (s == null || !shotAllowed(p, s)) continue;
                double v = enemyValue(s, me);
                if (v > bestVal) { bestVal = v; bestShot = s; }
            }
            value[i][slot] = bestVal;
            pick[i][slot] = bestShot;
        }
    }

    int[] order = new int[slots];
    int[] cur = new int[slots];
    boolean[] used = new boolean[n];
    double[] bestTotal = new double[]{ Double.NEGATIVE_INFINITY };
    for (int i = 0; i < slots; i++) order[i] = -1;
    searchOrder(value, slots, used, 0, cur, order, bestTotal);

    if (order[0] < 0) return null;
    Shot head = pick[order[0]][0];
    if (head != null) head.value = bestTotal[0];
    return head;
}

/**
 * Exhaustive search over firing orders (at most 4! = 24 with MAX_PLAN = 4).
 * Partial orders are scored too, so an unfillable slot does not throw away the
 * shots we can take.
 */
private void searchOrder(double[][] value, int slots, boolean[] used, int slot,
                         int[] cur, int[] best, double[] bestTotal) {
    if (slot > 0) {
        double total = 0.0;
        for (int s = 0; s < slot; s++) total += value[cur[s]][s];
        if (total > bestTotal[0]) {
            bestTotal[0] = total;
            for (int s = 0; s < slots; s++) best[s] = s < slot ? cur[s] : -1;
        }
    }
    if (slot >= slots) return;
    for (int i = 0; i < value.length; i++) {
        if (used[i]) continue;
        if (value[i][slot] == Double.NEGATIVE_INFINITY) continue;
        used[i] = true;
        cur[slot] = i;
        searchOrder(value, slots, used, slot + 1, cur, best, bestTotal);
        used[i] = false;
    }
}

/** Sort by: 0 = Void (deep in the void), 1 = Distance (far from you). */
private double enemyValue(Shot s, Entity me) {
    if (sEnemySort < 0.5) return voidDistance(s.point);
    return manhattan(s.point, eyeOf(me));
}

private boolean wantEnemyPearl(Pearl p, Entity me) {
    if (p.mine || p.pos == null || p.vel == null || p.pathLen < 2) return false;
    if (p.owner != null) {
        if (whitelist.contains(p.owner.toLowerCase())) return false;
        if (client.isFriend(p.owner)) return false;
    }
    if (p.shots > 0 && client.time() - p.lastShotAt < 400L) return false;
    if (eyeOf(me).distanceTo(p.pos) > sMaxDist + 20.0) return false;
    return true;
}

private List<Pearl> nearest(List<Pearl> in, Entity me, int k) {
    List<Pearl> out = new ArrayList<Pearl>();
    List<Pearl> pool = new ArrayList<Pearl>(in);
    Vec3 eye = eyeOf(me);
    while (out.size() < k && !pool.isEmpty()) {
        int bi = 0;
        for (int i = 1; i < pool.size(); i++) {
            if (eye.distanceTo(pool.get(i).pos) < eye.distanceTo(pool.get(bi).pos)) bi = i;
        }
        out.add(pool.remove(bi));
    }
    return out;
}

// ------------------------------------------------------------ shared -------

/**
 * Hard filter on a candidate intercept.  Only runs on shots we are actually
 * considering, so world queries are affordable here.
 */
private boolean shotAllowed(Pearl p, Shot s) {
    if (s.distance > sMaxDist) return false;
    if (s.flight < 0.0) return false;
    // never plan a catch at or past where the pearl terminates
    if (p.lands && s.hitTick >= p.pathLen) return false;
    if (sLos && !pathClear(s.origin, s.point)) return false;
    return true;
}

/** Final gate before the use packet leaves. */
private boolean readyToFire(Shot s, Pearl p) {
    if (s.delay != 0) return false;                       // solver says wait
    if (currentMiss > sHitRadius) return false;           // would not connect
    if (!aimSettled()) return false;                      // server lacks our aim
    if (fireballCount < 1) return false;
    // Rate limiting is deliberately left to two places only: the per-pearl
    // re-shoot guard above, and "Enemy delay" between shots at different
    // players' pearls.  A global floor here would silently override an Enemy
    // delay set below it.
    if (!p.mine && client.time() - lastEnemyShotAt < (long) sEnemyDelay) return false;
    return true;
}

/** Keybind toggles the enemy category's "Enabled" button. */
private void handleEnemyKeybind() {
    boolean down = modules.getKeyPressed(scriptName, "Keybind");
    if (down && !keyWasDown) {
        boolean now = !sEnemyOn;
        modules.setButton(scriptName, "Enabled", now);
        sEnemyOn = now;
        say(now ? "&aenemy catching on" : "&cenemy catching off");
    }
    keyWasDown = down;
}


// =============================================================================
//  SETTINGS SNAPSHOT
// =============================================================================
//  Note: the scripting API looks settings up by plain name across the whole
//  module -- groups do not namespace them -- so "Delay" and "Sort by" could
//  not exist in both categories.  They are prefixed instead.

private void readSettings() {
    sMinAngle   = modules.getSlider(scriptName, "Minimum angle");
    sUserDelay  = modules.getSlider(scriptName, "User delay");
    sUserSort   = modules.getSlider(scriptName, "User sort by");

    sEnemyOn    = modules.getButton(scriptName, "Enabled");
    sEnemySort  = modules.getSlider(scriptName, "Enemy sort by");
    sEnemyDelay = modules.getSlider(scriptName, "Enemy delay");

    sMaxDist    = modules.getSlider(scriptName, "Max distance");
    sMaxLead    = modules.getSlider(scriptName, "Max lead");
    sHitRadius  = modules.getSlider(scriptName, "Hit radius");
    sExtraLead  = modules.getSlider(scriptName, "Extra lead");
    sSettle     = modules.getSlider(scriptName, "Aim settle");
    sFallback   = modules.getSlider(scriptName, "Fallback speed");
    sLos        = modules.getButton(scriptName, "Line of sight");
    sAutoSwitch = modules.getButton(scriptName, "Auto switch");
    sRestore    = modules.getButton(scriptName, "Restore slot");
    sPacketThrow = modules.getButton(scriptName, "Packet throw");
    sSwing      = modules.getButton(scriptName, "Swing");
    sPredict    = modules.getButton(scriptName, "Predict own motion");

    sArc        = modules.getButton(scriptName, "Draw arc");
    sMark       = modules.getButton(scriptName, "Draw intercept");
    sHud        = modules.getButton(scriptName, "HUD");
    sDebug      = modules.getButton(scriptName, "Debug");
}
