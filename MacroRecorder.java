/*
 * MacroRecorder - Raven Script Loader (RSL) port
 *
 * Records and replays player inputs on a per-tick basis.
 * Everything is driven from the clickgui; there are no chat commands.
 *
 * Settings layout:
 *  -- Controls
 *       [Key]  Start Record / Stop Record / Play Last / Stop Playback
 *       [Key]  Play Macro Key
 *  -- Behaviour
 *       [Btn]  Loop / Record On First Input / Record Other Player
 *       [Btn]  Show Indicator / Force Motion / Debug
 *  -- Record Channels
 *       [Btn]  Rec Camera / Rec Movement / Rec Sprint / Rec Jump / Rec Crouch
 *       [Btn]  Rec Attack / Rec Use / Rec Drop / Rec Slot
 *  -- Playback Channels
 *       [Btn]  Play Camera / Play Movement / Play Sprint / Play Jump / Play Crouch
 *       [Btn]  Play Attack / Play Use / Play Drop / Play Slot
 *  -- Macro Files
 *       [Slider] Active Macro     [Btn] Save Recording / Delete Active / Play Macro
 *
 * Usage:
 *   1. Enable the script. Saved macro names are printed with their index.
 *   2. Bind and press "Start Record", then "Stop Record".
 *   3. Press "Play Last" to replay it. Toggle "Save Recording" on to keep it
 *      as rec_<unix time>; the buttons fire on the off -> on edge.
 *   4. To replay a saved macro, point "Active Macro" at its index and press
 *      "Play Macro Key".
 *
 * How the tick lines up, which is the whole game here:
 *   EntityPlayerSP.onUpdate  HEAD          -> onPreUpdate   (keys injected here)
 *     onLivingUpdate -> updatePlayerMoveState reads the keybinds
 *                    -> moveEntityWithHeading actually moves the player
 *   onUpdateWalkingPlayer    HEAD          -> onPreMotion   (rotations, advance)
 * A key pressed from onPreMotion cannot affect the tick it was pressed on,
 * because the movement for that tick was computed before it ran. Keys are
 * therefore injected from onPreUpdate, with onPreMotion kept as a backstop in
 * case PreUpdateEvent never arrives, which costs a one tick delay but still
 * moves.
 *
 * Diagnostics: turn on "Debug" and start a playback. It prints every channel
 * toggle, the keybind name and keycode it resolved for each control, a
 * write-then-read probe on the forward bind, and how many times each hook has
 * fired. That separates a config problem from a script problem from the loader
 * not calling us at all, without guessing.
 *
 * Notes on this build:
 *   - Channel toggles are read with the module name, not the group name; RSL
 *     registers settings by group but looks them up by module.
 *   - Setting names are unique across groups because lookup is flat by name.
 *   - Keybind names are resolved at runtime: RSL keys its map by the keybind
 *     description with "key." stripped, so "forward" is what matches, but the
 *     script probes for both spellings rather than trusting either.
 *   - Swap-hands was dropped: there is no offhand on 1.8.9.
 *   - Nothing in this file may contain the eight letters of the java keyword
 *     for pulling in a class, or RSL refuses to load it unless "Allow unsafe
 *     scripts" is on. Comments are scanned too.
 *   - Rotations are written to both the entity and the outgoing PlayerState so
 *     the replayed aim matches what was recorded on the same tick.
 *   - Movement, jump and crouch are recorded from movementInput rather than
 *     from the keybinds, so what gets stored is the input the game actually
 *     used that tick.
 *   - World displacement is recorded alongside the keys. If key injection does
 *     not reach the game on a given setup, "Force Motion" replays that
 *     displacement before vanilla collision handling instead. It is off by
 *     default because the key path reproduces the original physics.
 *
 * Macros saved before the keybind names were corrected hold false for every
 * movement key. Rotations and sprint still replay from those files, which looks
 * like "it turns and sprints but never walks". They cannot be repaired and have
 * to be recorded again; playback warns when it loads one.
 */

// Sandbox provides ArrayList, HashMap, List and Map already.

// ---------------------------------------------------------------------------
// Per-tick frame data, stored as Map<String,Object> so it can be serialised to
// JSON and read back with the built-in Json class.
// Numbers: yaw, pitch, dx, dz, slot, repeats   (NOT_SET when not recorded)
// Flags:   fwd, left, back, right, jump, sprint, sneak, attack, use, drop
//          (null when not recorded, which is distinct from a recorded false)
// ---------------------------------------------------------------------------

final double NOT_SET = Double.MIN_VALUE;
final int MAX_MACROS = 64;

// -- runtime state ----------------------------------------------------------
List<Map<String, Object>> recordedFrames = new ArrayList<>();
List<Map<String, Object>> playbackFrames = new ArrayList<>();
int playbackIndex = 0;
int currentRepeat = 0;
boolean isRecording = false;
boolean isPlaying = false;
boolean waitingForFirstInput = false;

// Playback starts from inside onPreMotion, by which point this tick's
// onPreUpdate has already run. Skip that one tick so frame 0 gets its keys and
// its rotation on the same tick instead of a frame apart.
boolean playbackJustStarted = false;

int targetPlayerId = -1;
String targetPlayerName = "";

// keybind "just pressed" tracking
boolean startRecordWasDown = false;
boolean stopRecordWasDown = false;
boolean playLastWasDown = false;
boolean stopPlayWasDown = false;
boolean playMacroWasDown = false;

// button "just toggled on" tracking
boolean saveRecBtnWasDown = false;
boolean deleteBtnWasDown = false;
boolean playMacroBtnWasDown = false;

// saved-macro list, mirrored into the config file
List<String> macroNames = new ArrayList<>();

// -- keybind names, resolved against the loader's map at runtime -------------
String kForward = "forward";
String kBack = "back";
String kLeft = "left";
String kRight = "right";
String kJump = "jump";
String kSneak = "sneak";
String kSprint = "sprint";
String kAttack = "attack";
String kUse = "use";
String kDrop = "drop";
boolean bindsResolved = false;

// -- diagnostics ------------------------------------------------------------
int preUpdateCalls = 0;
int preMotionCalls = 0;
int playbackTicks = 0;
String lastDebugLine = "";

// ---------------------------------------------------------------------------
// onLoad - register all settings
// ---------------------------------------------------------------------------
void onLoad() {
    modules.setCategory(category.player);

    modules.registerGroup("Controls");
    modules.registerKey("Controls", "Start Record", 0);
    modules.registerKey("Controls", "Stop Record", 0);
    modules.registerKey("Controls", "Play Last", 0);
    modules.registerKey("Controls", "Stop Playback", 0);
    modules.registerKey("Controls", "Play Macro Key", 0);

    modules.registerGroup("Behaviour");
    modules.registerButton("Behaviour", "Loop", false);
    modules.registerButton("Behaviour", "Record On First Input", false);
    modules.registerButton("Behaviour", "Record Other Player", false);
    modules.registerButton("Behaviour", "Show Indicator", true);
    modules.registerButton("Behaviour", "Force Motion", false);
    modules.registerButton("Behaviour", "Debug", false);

    modules.registerGroup("Record Channels");
    modules.registerButton("Record Channels", "Rec Camera", true);
    modules.registerButton("Record Channels", "Rec Movement", true);
    modules.registerButton("Record Channels", "Rec Sprint", true);
    modules.registerButton("Record Channels", "Rec Jump", true);
    modules.registerButton("Record Channels", "Rec Crouch", true);
    modules.registerButton("Record Channels", "Rec Attack", true);
    modules.registerButton("Record Channels", "Rec Use", true);
    modules.registerButton("Record Channels", "Rec Drop", false);
    modules.registerButton("Record Channels", "Rec Slot", true);

    modules.registerGroup("Playback Channels");
    modules.registerButton("Playback Channels", "Play Camera", true);
    modules.registerButton("Playback Channels", "Play Movement", true);
    modules.registerButton("Playback Channels", "Play Sprint", true);
    modules.registerButton("Playback Channels", "Play Jump", true);
    modules.registerButton("Playback Channels", "Play Crouch", true);
    modules.registerButton("Playback Channels", "Play Attack", true);
    modules.registerButton("Playback Channels", "Play Use", true);
    modules.registerButton("Playback Channels", "Play Drop", true);
    modules.registerButton("Playback Channels", "Play Slot", true);

    modules.registerGroup("Macro Files");
    modules.registerSlider("Macro Files", "Active Macro", "", 0, 0, MAX_MACROS - 1, 1);
    modules.registerButton("Macro Files", "Save Recording", false);
    modules.registerButton("Macro Files", "Delete Active", false);
    modules.registerButton("Macro Files", "Play Macro", false);

    loadMacroList();
}

// ---------------------------------------------------------------------------
// onEnable / onDisable
// ---------------------------------------------------------------------------
void onEnable() {
    loadMacroList();
    resolveBindNames();
    say("&aMacroRecorder enabled.");
    listMacros();
}

void onDisable() {
    if (isRecording) stopRecording(false);
    if (isPlaying) stopPlayback(false);
    releaseAllKeys();
}

// ---------------------------------------------------------------------------
// Keybind name resolution. RSL builds its map from the keybind description
// with a leading "key." stripped, so "forward" is the name that matches. Older
// builds may differ, and a name that is not in the map makes every press and
// every read a silent no-op, so probe for it instead of assuming.
// ---------------------------------------------------------------------------
void resolveBindNames() {
    kForward = bindName("forward");
    kBack = bindName("back");
    kLeft = bindName("left");
    kRight = bindName("right");
    kJump = bindName("jump");
    kSneak = bindName("sneak");
    kSprint = bindName("sprint");
    kAttack = bindName("attack");
    kUse = bindName("use");
    kDrop = bindName("drop");
    bindsResolved = true;
}

String bindName(String base) {
    if (keybinds.getKeyCode(base) != -1) return base;
    if (keybinds.getKeyCode("key." + base) != -1) return "key." + base;
    return base;
}

// ---------------------------------------------------------------------------
// Key injection. This runs at the head of EntityPlayerSP.onUpdate, so the
// presses set here are read by updatePlayerMoveState later in the same tick
// and move the player on that tick.
// ---------------------------------------------------------------------------
void onPreUpdate() {
    preUpdateCalls++;
    if (!isPlaying || playbackJustStarted) return;
    if (playbackIndex < 0) return;
    if (playbackIndex >= playbackFrames.size()) {
        if (modules.getButton(scriptName, "Loop")) {
            playbackIndex = 0;
            currentRepeat = 0;
        } else {
            releaseAllKeys();
            return;
        }
    }

    Map<String, Object> frame = playbackFrames.get(playbackIndex);
    // Heading must be in place before onLivingUpdate calculates acceleration.
    applyFrameCamera(frame);
    applyFrameKeys(frame);
}

void applyFrameCamera(Map<String, Object> frame) {
    if (!modules.getButton(scriptName, "Play Camera")) return;

    Entity player = client.getPlayer();
    if (player == null) return;

    double yawD = getDouble(frame, "yaw");
    double pitchD = getDouble(frame, "pitch");
    if (yawD != NOT_SET) player.setYaw((float) yawD);
    if (pitchD != NOT_SET) player.setPitch((float) pitchD);
}

void applyFrameKeys(Map<String, Object> frame) {
    if (!bindsResolved) resolveBindNames();

    boolean playMovement = modules.getButton(scriptName, "Play Movement");
    boolean forceMotion = modules.getButton(scriptName, "Force Motion");
    boolean useDisplacement = playMovement && forceMotion && hasDisplacement(frame);

    // Force Motion is exclusive with movement keys. Keeping both enabled adds
    // vanilla acceleration to an already recorded path and can exceed legal
    // horizontal speed at the start of playback.
    if (playMovement && !useDisplacement) {
        keybinds.setPressed(kForward, isDown(frame, "fwd"));
        keybinds.setPressed(kLeft, isDown(frame, "left"));
        keybinds.setPressed(kBack, isDown(frame, "back"));
        keybinds.setPressed(kRight, isDown(frame, "right"));
    } else {
        keybinds.setPressed(kForward, false);
        keybinds.setPressed(kLeft, false);
        keybinds.setPressed(kBack, false);
        keybinds.setPressed(kRight, false);
    }

    if (useDisplacement) {
        double dx = getDouble(frame, "dx");
        double dz = getDouble(frame, "dz");
        Vec3 current = client.getMotion();
        // This is set at PreUpdate, before moveEntityWithHeading runs. Vanilla
        // still performs collision checks and applies its normal post-move drag.
        client.setMotion(dx, current.y, dz);
    }

    if (modules.getButton(scriptName, "Play Jump")) {
        Object jumpO = frame.get("jump");
        if (jumpO != null) {
            boolean j = (boolean) jumpO;
            keybinds.setPressed(kJump, j);
            client.setJump(j);
        }
    }

    if (modules.getButton(scriptName, "Play Sprint")) {
        Object sprintO = frame.get("sprint");
        if (sprintO != null) client.setSprinting((boolean) sprintO);
    }

    if (modules.getButton(scriptName, "Play Crouch")) {
        Object sneakO = frame.get("sneak");
        if (sneakO != null) {
            boolean s = (boolean) sneakO;
            keybinds.setPressed(kSneak, s);
            client.setSneak(s);
        }
    }

    if (modules.getButton(scriptName, "Play Attack")) {
        Object attackO = frame.get("attack");
        if (attackO != null) {
            boolean att = (boolean) attackO;
            keybinds.setPressed(kAttack, att);
            if (att) keybinds.leftClick();
        }
    }

    if (modules.getButton(scriptName, "Play Use")) {
        Object useO = frame.get("use");
        if (useO != null) {
            boolean u = (boolean) useO;
            keybinds.setPressed(kUse, u);
            if (u) keybinds.rightClick();
        }
    }

    if (modules.getButton(scriptName, "Play Drop") && isDown(frame, "drop")) {
        client.dropItem(false);
    }

    if (modules.getButton(scriptName, "Play Slot")) {
        double slotD = getDouble(frame, "slot");
        if (slotD != NOT_SET) {
            int s = (int) slotD;
            if (s >= 0 && s <= 8) inventory.setSlot(s);
        }
    }
}

// ---------------------------------------------------------------------------
// Main tick - key detection, frame recording and playback
// ---------------------------------------------------------------------------
void onPreMotion(PlayerState state) {
    preMotionCalls++;

    Entity player = client.getPlayer();
    if (player == null) return;

    // -- read settings ------------------------------------------------------
    // Note: the first argument is the MODULE name. Passing the group name here
    // silently resolves to nothing and every read comes back false.
    boolean loop = modules.getButton(scriptName, "Loop");
    boolean recordOnFirstInput = modules.getButton(scriptName, "Record On First Input");
    boolean debug = modules.getButton(scriptName, "Debug");

    boolean recCamera = modules.getButton(scriptName, "Rec Camera");
    boolean recMovement = modules.getButton(scriptName, "Rec Movement");
    boolean recSprint = modules.getButton(scriptName, "Rec Sprint");
    boolean recJump = modules.getButton(scriptName, "Rec Jump");
    boolean recCrouch = modules.getButton(scriptName, "Rec Crouch");
    boolean recAttack = modules.getButton(scriptName, "Rec Attack");
    boolean recUse = modules.getButton(scriptName, "Rec Use");
    boolean recDrop = modules.getButton(scriptName, "Rec Drop");
    boolean recSlot = modules.getButton(scriptName, "Rec Slot");

    boolean pbCamera = modules.getButton(scriptName, "Play Camera");

    // -- keybind polling (rising edge) --------------------------------------
    boolean startRecordDown = modules.getKeyPressed(scriptName, "Start Record");
    boolean stopRecordDown = modules.getKeyPressed(scriptName, "Stop Record");
    boolean playLastDown = modules.getKeyPressed(scriptName, "Play Last");
    boolean stopPlayDown = modules.getKeyPressed(scriptName, "Stop Playback");
    boolean playMacroDown = modules.getKeyPressed(scriptName, "Play Macro Key");

    boolean saveRecBtn = modules.getButton(scriptName, "Save Recording");
    boolean deleteBtn = modules.getButton(scriptName, "Delete Active");
    boolean playMacroBtn = modules.getButton(scriptName, "Play Macro");

    if (startRecordDown && !startRecordWasDown) {
        startRecording(recordOnFirstInput);
    }
    startRecordWasDown = startRecordDown;

    if (stopRecordDown && !stopRecordWasDown) {
        stopRecording(true);
    }
    stopRecordWasDown = stopRecordDown;

    if (playLastDown && !playLastWasDown) {
        playLastRecording(loop);
    }
    playLastWasDown = playLastDown;

    if (stopPlayDown && !stopPlayWasDown) {
        if (isPlaying) {
            stopPlayback(true);
        } else {
            say("&cNo macro is playing.");
        }
    }
    stopPlayWasDown = stopPlayDown;

    if (playMacroDown && !playMacroWasDown) {
        playSelectedMacro(loop);
    }
    playMacroWasDown = playMacroDown;

    if (saveRecBtn && !saveRecBtnWasDown) {
        saveMacro("rec_" + (client.time() / 1000L));
    }
    saveRecBtnWasDown = saveRecBtn;

    if (deleteBtn && !deleteBtnWasDown) {
        String name = getSelectedMacro();
        if (name != null) deleteMacro(name);
    }
    deleteBtnWasDown = deleteBtn;

    if (playMacroBtn && !playMacroBtnWasDown) {
        playSelectedMacro(loop);
    }
    playMacroBtnWasDown = playMacroBtn;

    // -- recording ----------------------------------------------------------
    if (isRecording) {
        if (waitingForFirstInput) {
            if (Math.abs(client.getForward()) > 0.01f || Math.abs(client.getStrafe()) > 0.01f
                    || client.isJump() || client.isSneak()
                    || keybinds.isPressed(kAttack) || keybinds.isPressed(kUse)) {
                waitingForFirstInput = false;
            }
        }

        if (!waitingForFirstInput) {
            double yaw = NOT_SET;
            double pitch = NOT_SET;
            double slot = NOT_SET;
            double dx = NOT_SET;
            double dz = NOT_SET;
            Object fwd = null;
            Object left = null;
            Object back = null;
            Object right = null;
            Object jump = null;
            Object sprint = null;
            Object sneak = null;
            Object attack = null;
            Object use = null;
            Object drop = null;

            if (targetPlayerId != -1) {
                // Remote recording: inputs are inferred from observed motion.
                Entity target = world.getEntityById(targetPlayerId);
                if (target == null) {
                    say("&cTarget player lost, stopping recording.");
                    stopRecording(false);
                    return;
                }

                if (recCamera) {
                    yaw = target.getYaw();
                    pitch = target.getPitch();
                }

                if (recMovement) {
                    double observedDx = target.getX() - target.getPrevX();
                    double observedDz = target.getZ() - target.getPrevZ();
                    dx = util.round(observedDx, 4);
                    dz = util.round(observedDz, 4);
                    fwd = false;
                    back = false;
                    left = false;
                    right = false;

                    if (observedDx * observedDx + observedDz * observedDz > 0.0001) {
                        double moveAngle = Math.toDegrees(Math.atan2(-observedDx, observedDz));
                        double diff = moveAngle - target.getYaw();
                        while (diff < -180) diff += 360;
                        while (diff > 180) diff -= 360;

                        double diffRad = Math.toRadians(diff);
                        double fwdComp = Math.cos(diffRad);
                        double rightComp = Math.sin(diffRad);

                        if (fwdComp > 0.3) fwd = true;
                        if (fwdComp < -0.3) back = true;
                        if (rightComp > 0.3) right = true;
                        if (rightComp < -0.3) left = true;
                    }
                }

                if (recJump) jump = (target.getY() - target.getPrevY() > 0.1) && !target.onGround();
                if (recSprint) sprint = target.isSprinting();
                if (recCrouch) sneak = target.isSneaking();
                if (recAttack) attack = target.getSwingProgress() > 0;
                if (recUse) use = target.isUsingItem();
                // drop and slot cannot be observed for another player
            } else {
                // Local recording. Rotations come from the outgoing state so
                // they line up with what playback writes back into it.
                if (recCamera) {
                    yaw = state.yaw;
                    pitch = state.pitch;
                }
                // Movement comes from movementInput, which is what the game
                // actually walked on this tick. updatePlayerMoveState builds it
                // as: forward ++moveForward, back --moveForward, left
                // ++moveStrafe, right --moveStrafe, then scales by 0.3 while
                // sneaking and 0.2 while using an item, so compare against a
                // small epsilon rather than 1.
                if (recMovement) {
                    float mf = client.getForward();
                    float ms = client.getStrafe();
                    fwd = mf > 0.01f;
                    back = mf < -0.01f;
                    left = ms > 0.01f;
                    right = ms < -0.01f;

                    // Store what the server-facing position actually moved,
                    // rather than the post-friction velocity for the next tick.
                    dx = util.round(player.getX() - player.getPrevX(), 4);
                    dz = util.round(player.getZ() - player.getPrevZ(), 4);
                }
                jump = recJump ? (Object) client.isJump() : null;
                sprint = recSprint ? (Object) player.isSprinting() : null;
                sneak = recCrouch ? (Object) client.isSneak() : null;
                attack = recAttack ? (Object) keybinds.isPressed(kAttack) : null;
                use = recUse ? (Object) keybinds.isPressed(kUse) : null;
                drop = recDrop ? (Object) keybinds.isPressed(kDrop) : null;
                if (recSlot) slot = inventory.getSlot();
            }

            Map<String, Object> frame = buildFrame(yaw, pitch, fwd, left, back, right,
                    jump, sprint, sneak, attack, use, drop, slot, dx, dz, 1);

            // Run-length encoding: bump the repeat count on an identical frame.
            if (!recordedFrames.isEmpty()) {
                Map<String, Object> lastFrame = recordedFrames.get(recordedFrames.size() - 1);
                if (framesEqual(lastFrame, frame)) {
                    int reps = (int) getDouble(lastFrame, "repeats");
                    lastFrame.put("repeats", (double) (reps + 1));
                } else {
                    recordedFrames.add(frame);
                }
            } else {
                recordedFrames.add(frame);
            }
        }
    }

    // -- playback -----------------------------------------------------------
    if (isPlaying) {
        if (playbackFrames.isEmpty()) {
            stopPlayback(false);
            return;
        }

        // Playback was started earlier in this same call, after this tick's
        // onPreUpdate had already gone by. Give frame 0 a full tick.
        if (playbackJustStarted) {
            playbackJustStarted = false;
            return;
        }

        if (playbackIndex >= playbackFrames.size()) {
            if (loop) {
                playbackIndex = 0;
                currentRepeat = 0;
            } else {
                stopPlayback(false);
                say("&ePlayback finished.");
                return;
            }
        }

        Map<String, Object> frame = playbackFrames.get(playbackIndex);
        playbackTicks++;

        // Camera was installed in onPreUpdate before movement. Write the same
        // values into the outgoing state so the packet and physics agree.
        if (pbCamera) {
            double yawD = getDouble(frame, "yaw");
            double pitchD = getDouble(frame, "pitch");
            if (yawD != NOT_SET) {
                player.setYaw((float) yawD);
                state.yaw = (float) yawD;
            }
            if (pitchD != NOT_SET) {
                player.setPitch((float) pitchD);
                state.pitch = (float) pitchD;
            }
        }

        // Backstop: onPreUpdate should already have pressed this frame's keys
        // for this tick. If PreUpdateEvent never arrives, pressing them here
        // still works, one tick later than recorded.
        if (preUpdateCalls == 0) {
            applyFrameKeys(frame);
        }

        if (debug) debugTick(frame);

        // advance once the repeat count for this frame is used up
        int repeats = (int) getDouble(frame, "repeats");
        if (repeats < 1) repeats = 1;
        currentRepeat++;
        if (currentRepeat >= repeats) {
            currentRepeat = 0;
            playbackIndex++;
        }
    }
}

// ---------------------------------------------------------------------------
// Debug output. Printed on change plus a heartbeat, rather than every tick,
// so the chat stays readable.
// ---------------------------------------------------------------------------
void debugTick(Map<String, Object> frame) {
    String want = tf(isDown(frame, "fwd")) + tf(isDown(frame, "back"))
            + tf(isDown(frame, "left")) + tf(isDown(frame, "right"))
            + tf(isDown(frame, "jump"));
    String line = "&8[" + playbackIndex + "] want(FBLRJ)=" + want
            + " keyDown=" + tf(keybinds.isPressed(kForward))
            + " mi=" + util.round(client.getForward(), 2) + "/" + util.round(client.getStrafe(), 2)
            + " hooks pre=" + preUpdateCalls + " mot=" + preMotionCalls;
    if (line.equals(lastDebugLine) && playbackTicks % 40 != 0) return;
    lastDebugLine = line;
    say(line);
}

// Everything needed to tell a config problem from a script problem from the
// loader never calling us.
void dumpDiagnostics() {
    say("&7---- MacroRecorder diagnostics ----");
    say("&7play: &fcam=" + btn("Play Camera") + " move=" + btn("Play Movement")
            + " sprint=" + btn("Play Sprint") + " jump=" + btn("Play Jump")
            + " crouch=" + btn("Play Crouch") + " attack=" + btn("Play Attack")
            + " use=" + btn("Play Use") + " drop=" + btn("Play Drop")
            + " slot=" + btn("Play Slot"));
    say("&7rec:  &fcam=" + btn("Rec Camera") + " move=" + btn("Rec Movement")
            + " sprint=" + btn("Rec Sprint") + " jump=" + btn("Rec Jump")
            + " crouch=" + btn("Rec Crouch") + " attack=" + btn("Rec Attack")
            + " use=" + btn("Rec Use") + " drop=" + btn("Rec Drop")
            + " slot=" + btn("Rec Slot"));
    say("&7misc: &floop=" + btn("Loop") + " forceMotion=" + btn("Force Motion")
            + " otherPlayer=" + btn("Record Other Player")
            + " firstInput=" + btn("Record On First Input")
            + " frames=" + playbackFrames.size());
    say("&7binds: &f" + bindInfo(kForward) + " " + bindInfo(kBack) + " "
            + bindInfo(kLeft) + " " + bindInfo(kRight) + " " + bindInfo(kJump)
            + " " + bindInfo(kSneak) + " " + bindInfo(kAttack) + " " + bindInfo(kUse));
    say("&7hooks: &fpreUpdate=" + preUpdateCalls + " preMotion=" + preMotionCalls
            + (preUpdateCalls == 0 ? " &c(PreUpdateEvent never arrived, using the backstop)" : ""));
    say("&7probe: &f" + probeForwardBind());
}

// Writes the forward bind both ways and reads it back each time. If a write
// does not show up in the read, presses are landing on a different object than
// the one the game polls, and no hook placement will fix that.
String probeForwardBind() {
    boolean original = keybinds.isPressed(kForward);
    keybinds.setPressed(kForward, true);
    boolean readTrue = keybinds.isPressed(kForward);
    keybinds.setPressed(kForward, false);
    boolean readFalse = keybinds.isPressed(kForward);
    keybinds.setPressed(kForward, original);

    if (readTrue && !readFalse) return "write reaches read, key injection is sound";
    return "&cwrite does NOT reach read (set true -> " + tf(readTrue)
            + ", set false -> " + tf(readFalse) + "). Turn on Force Motion.";
}

String bindInfo(String name) {
    return name + "=" + keybinds.getKeyCode(name);
}

String btn(String name) {
    return tf(modules.getButton(scriptName, name));
}

String tf(boolean value) {
    return value ? "T" : "F";
}

// ---------------------------------------------------------------------------
// HUD indicator
// ---------------------------------------------------------------------------
void onRenderTick(float partialTicks) {
    if (!modules.getButton(scriptName, "Show Indicator")) return;
    if (!isRecording && !isPlaying) return;

    int[] displaySize = client.getDisplaySize();
    int screenW = displaySize[0];
    int screenH = displaySize[1];

    if (isRecording) {
        String msg;
        int color;
        if (waitingForFirstInput) {
            msg = "Waiting For Input";
            color = 0xFFFFFF00;
        } else {
            String prefix = (targetPlayerId != -1) ? targetPlayerName + " " : "";
            msg = "Recording " + prefix + "(" + recordedFrames.size() + " frames)";
            color = 0xFFFF4444;
        }
        render.text2d(msg, (screenW - render.getFontWidth(msg)) / 2, screenH - 50, 1.0f, color, true);
    }

    if (isPlaying) {
        String msg = "Playing (" + playbackIndex + " / " + playbackFrames.size() + ")";
        render.text2d(msg, (screenW - render.getFontWidth(msg)) / 2, screenH - 40, 1.0f, 0xFF44FF44, true);
    }
}

// ---------------------------------------------------------------------------
// Recording helpers
// ---------------------------------------------------------------------------
void startRecording(boolean recordOnFirstInput) {
    if (isPlaying) stopPlayback(false);
    if (!bindsResolved) resolveBindNames();

    targetPlayerId = -1;
    targetPlayerName = "";

    if (modules.getButton(scriptName, "Record Other Player")) {
        Entity local = client.getPlayer();
        List<Entity> ents = world.getEntities();
        Entity closest = null;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < ents.size(); i++) {
            Entity e = ents.get(i);
            if (e.isPlayer && !e.isUser) {
                double dist = local.distanceTo(e.getPosition());
                if (dist < minDist) {
                    minDist = dist;
                    closest = e;
                }
            }
        }
        if (closest == null) {
            say("&cNo other players nearby to record.");
            return;
        }
        targetPlayerId = closest.entityId;
        targetPlayerName = closest.getName();
        say("&aRecording started for player &f" + targetPlayerName + "&a.");
    } else {
        say("&aRecording started.&7 Press Stop Record to finish, then toggle &aSave Recording&7 to keep it.");
    }

    recordedFrames.clear();
    isRecording = true;
    waitingForFirstInput = recordOnFirstInput;
}

void stopRecording(boolean announce) {
    if (!isRecording) {
        if (announce) say("&cNot currently recording.");
        return;
    }
    isRecording = false;
    targetPlayerId = -1;
    waitingForFirstInput = false;
    if (announce) {
        say("&eRecording stopped, " + recordedFrames.size()
                + " frames captured. Toggle &aSave Recording&e to keep it or &aPlay Last&e to test.");
    }
}

// ---------------------------------------------------------------------------
// Playback helpers
// ---------------------------------------------------------------------------
void playLastRecording(boolean loop) {
    if (isRecording) {
        say("&cStop recording first.");
        return;
    }
    if (recordedFrames.isEmpty()) {
        say("&cNo recording in memory. Record something first.");
        return;
    }
    playbackFrames = new ArrayList<>(recordedFrames);
    startPlayback();
    say("&aPlaying last recording (" + playbackFrames.size() + " frames)." + (loop ? " &7[Looping]" : ""));
    warnIfNoKeyInput(false);
}

void playSelectedMacro(boolean loop) {
    if (isRecording) {
        say("&cStop recording first.");
        return;
    }
    String name = getSelectedMacro();
    if (name == null) return;
    loadMacroIntoPlayback(name, loop);
}

void loadMacroIntoPlayback(String name, boolean loop) {
    String json = config.get("macro_" + name);
    if (json == null || json.isEmpty()) {
        say("&cMacro '" + name + "' not found.");
        return;
    }
    List<Map<String, Object>> frames = parseFramesFromJson(json);
    if (frames == null || frames.isEmpty()) {
        say("&cMacro '" + name + "' is empty or corrupt.");
        return;
    }
    playbackFrames = frames;
    startPlayback();
    say("&aPlaying macro '&f" + name + "&a' (" + playbackFrames.size() + " frames)." + (loop ? " &7[Looping]" : ""));
    warnIfNoKeyInput(true);
}

void startPlayback() {
    playbackIndex = 0;
    currentRepeat = 0;
    playbackTicks = 0;
    isPlaying = true;
    playbackJustStarted = true;
    lastDebugLine = "";
    resolveBindNames();
    if (modules.getButton(scriptName, "Debug")) dumpDiagnostics();
}

void stopPlayback(boolean announce) {
    isPlaying = false;
    playbackIndex = 0;
    currentRepeat = 0;
    playbackJustStarted = false;
    releaseAllKeys();
    if (announce) say("&ePlayback stopped.");
}

// Frames recorded by the build that polled the wrong keybind names hold false
// for every key, so they turn and sprint but never walk. Nothing can recover
// the lost input, so say so plainly instead of replaying a dud.
void warnIfNoKeyInput(boolean fromFile) {
    String[] keys = {"fwd", "left", "back", "right", "jump", "attack", "use", "drop"};
    for (int i = 0; i < playbackFrames.size(); i++) {
        Map<String, Object> frame = playbackFrames.get(i);
        for (int k = 0; k < keys.length; k++) {
            if (isDown(frame, keys[k])) return;
        }
    }
    say("&cThis recording holds no key input at all, so it will only turn and sprint.");
    if (fromFile) {
        say("&7Macros saved before the keybind fix are all like this. Record it again.");
    }
}

// ---------------------------------------------------------------------------
// Save / load / delete macros through the config API.
// Config key "macros"        = comma separated macro names
// Config key "macro_<name>"  = JSON array of frame objects
// ---------------------------------------------------------------------------
void saveMacro(String name) {
    if (recordedFrames.isEmpty()) {
        say("&cNothing recorded. Press Start Record first.");
        return;
    }
    loadMacroList();
    if (macroNames.contains(name)) {
        say("&cMacro '&f" + name + "&c' already exists. Delete it first.");
        return;
    }
    if (macroNames.size() >= MAX_MACROS) {
        say("&cMacro list is full (" + MAX_MACROS + "). Delete one first.");
        return;
    }

    config.set("macro_" + name, framesToJson(recordedFrames));
    macroNames.add(name);
    persistMacroList();

    say("&aSaved macro '&f" + name + "&a' (" + recordedFrames.size() + " frames) at index &f"
            + (macroNames.size() - 1) + "&a.");
}

void deleteMacro(String name) {
    loadMacroList();
    if (!macroNames.contains(name)) {
        say("&cMacro '&f" + name + "&c' not found.");
        return;
    }
    config.set("macro_" + name, "");
    macroNames.remove(name);
    persistMacroList();
    say("&eDeleted macro '&f" + name + "&e'.");
    listMacros();
}

// Reads the "Active Macro" slider and resolves it to a name, or null.
String getSelectedMacro() {
    loadMacroList();
    if (macroNames.isEmpty()) {
        say("&cNo saved macros. Record and save one first.");
        return null;
    }
    int idx = (int) modules.getSlider(scriptName, "Active Macro");
    if (idx < 0 || idx >= macroNames.size()) {
        say("&cNo macro at index &f" + idx + "&c. Valid range is &f0-" + (macroNames.size() - 1) + "&c.");
        return null;
    }
    return macroNames.get(idx);
}

void loadMacroList() {
    macroNames.clear();
    String stored = config.get("macros");
    if (stored != null && !stored.isEmpty()) {
        String[] parts = stored.split(",");
        for (int i = 0; i < parts.length; i++) {
            String n = parts[i].trim();
            if (!n.isEmpty()) macroNames.add(n);
        }
    }
}

void persistMacroList() {
    config.set("macros", joinList(macroNames, ","));
}

// The slider range is fixed at registration time, so the list is printed
// instead to show which index maps to which macro.
void listMacros() {
    if (macroNames.isEmpty()) {
        say("&7No saved macros.");
        return;
    }
    say("&7Saved macros:");
    for (int i = 0; i < macroNames.size(); i++) {
        say("&7 " + i + ": &f" + macroNames.get(i));
    }
}

// ---------------------------------------------------------------------------
// Key release helper
// ---------------------------------------------------------------------------
void releaseAllKeys() {
    if (!bindsResolved) resolveBindNames();
    keybinds.setPressed(kForward, false);
    keybinds.setPressed(kBack, false);
    keybinds.setPressed(kLeft, false);
    keybinds.setPressed(kRight, false);
    keybinds.setPressed(kJump, false);
    keybinds.setPressed(kSprint, false);
    keybinds.setPressed(kSneak, false);
    keybinds.setPressed(kAttack, false);
    keybinds.setPressed(kUse, false);
    keybinds.setPressed(kDrop, false);
    client.setJump(false);
    client.setSneak(false);
    client.setSprinting(false);
}

// ---------------------------------------------------------------------------
// Frame construction and comparison helpers
// ---------------------------------------------------------------------------
Map<String, Object> buildFrame(double yaw, double pitch,
                               Object fwd, Object left, Object back, Object right,
                               Object jump, Object sprint, Object sneak,
                               Object attack, Object use, Object drop,
                               double slot, double dx, double dz, int repeats) {
    Map<String, Object> m = new HashMap<>();
    m.put("yaw", yaw);
    m.put("pitch", pitch);
    m.put("fwd", fwd);
    m.put("left", left);
    m.put("back", back);
    m.put("right", right);
    m.put("jump", jump);
    m.put("sprint", sprint);
    m.put("sneak", sneak);
    m.put("attack", attack);
    m.put("use", use);
    m.put("drop", drop);
    m.put("slot", slot);
    m.put("dx", dx);
    m.put("dz", dz);
    m.put("repeats", (double) repeats);
    return m;
}

boolean framesEqual(Map<String, Object> a, Map<String, Object> b) {
    String[] boolKeys = {"fwd", "left", "back", "right", "jump", "sprint", "sneak", "attack", "use", "drop"};
    for (int i = 0; i < boolKeys.length; i++) {
        Object av = a.get(boolKeys[i]);
        Object bv = b.get(boolKeys[i]);
        if (av == null && bv == null) continue;
        if (av == null || bv == null) return false;
        if (!av.equals(bv)) return false;
    }
    String[] numKeys = {"yaw", "pitch", "slot", "dx", "dz"};
    for (int i = 0; i < numKeys.length; i++) {
        if (getDouble(a, numKeys[i]) != getDouble(b, numKeys[i])) return false;
    }
    return true;
}

boolean isDown(Map<String, Object> frame, String key) {
    Object v = frame.get(key);
    return v != null && (boolean) v;
}

boolean hasDisplacement(Map<String, Object> frame) {
    double dx = getDouble(frame, "dx");
    double dz = getDouble(frame, "dz");
    return dx != NOT_SET && dz != NOT_SET
            && !Double.isNaN(dx) && !Double.isNaN(dz)
            && !Double.isInfinite(dx) && !Double.isInfinite(dz);
}

double getDouble(Map<String, Object> m, String key) {
    Object v = m.get(key);
    if (!(v instanceof Number)) return NOT_SET;
    return ((Number) v).doubleValue();
}

// ---------------------------------------------------------------------------
// JSON serialisation and parsing
// ---------------------------------------------------------------------------
String framesToJson(List<Map<String, Object>> frames) {
    String[] numKeys = {"yaw", "pitch", "slot", "dx", "dz", "repeats"};
    String[] boolKeys = {"fwd", "left", "back", "right", "jump", "sprint", "sneak", "attack", "use", "drop"};
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < frames.size(); i++) {
        Map<String, Object> frame = frames.get(i);
        if (i > 0) sb.append(",");
        sb.append("{");
        for (int k = 0; k < numKeys.length; k++) {
            if (k > 0) sb.append(",");
            appendNum(sb, frame, numKeys[k]);
        }
        for (int k = 0; k < boolKeys.length; k++) {
            sb.append(",");
            appendBool(sb, frame, boolKeys[k]);
        }
        sb.append("}");
    }
    sb.append("]");
    return sb.toString();
}

void appendNum(StringBuilder sb, Map<String, Object> frame, String key) {
    double v = getDouble(frame, key);
    sb.append("\"").append(key).append("\":");
    if (v == NOT_SET) {
        sb.append("null");
    } else {
        sb.append(v);
    }
}

void appendBool(StringBuilder sb, Map<String, Object> frame, String key) {
    Object v = frame.get(key);
    sb.append("\"").append(key).append("\":");
    sb.append((v == null) ? "null" : (((boolean) v) ? "true" : "false"));
}

List<Map<String, Object>> parseFramesFromJson(String jsonStr) {
    try {
        Json parsed = new Json(jsonStr);
        if (!parsed.exists()) return null;

        List<Json> items = parsed.array();
        if (items == null) return null;

        String[] numKeys = {"yaw", "pitch", "slot", "dx", "dz", "repeats"};
        String[] boolKeys = {"fwd", "left", "back", "right", "jump", "sprint", "sneak", "attack", "use", "drop"};

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Json obj = items.get(i);
            Map<String, Object> frame = new HashMap<>();
            for (int k = 0; k < numKeys.length; k++) {
                frame.put(numKeys[k], parseDouble(obj.get(numKeys[k])));
            }
            for (int k = 0; k < boolKeys.length; k++) {
                frame.put(boolKeys[k], parseNullableBool(obj.get(boolKeys[k])));
            }
            result.add(frame);
        }
        return result;
    } catch (Exception e) {
        client.log("MacroRecorder: failed to parse JSON: " + e.getMessage());
        return null;
    }
}

double parseDouble(String s) {
    if (s == null || s.isEmpty() || s.equals("null")) return NOT_SET;
    try {
        return Double.parseDouble(s);
    } catch (Exception e) {
        return NOT_SET;
    }
}

Object parseNullableBool(String s) {
    if (s == null || s.isEmpty() || s.equals("null")) return null;
    return Boolean.parseBoolean(s);
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
void say(String message) {
    client.print(util.color(message));
}

String joinList(List<String> list, String sep) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
        if (i > 0) sb.append(sep);
        sb.append(list.get(i));
    }
    return sb.toString();
}
