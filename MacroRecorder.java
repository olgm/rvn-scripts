/*
 * MacroRecorder - Raven Script Loader (RSL) port
 *
 * Records and replays player inputs on a per-tick basis.
 * Everything is driven from the clickgui; there are no chat commands.
 *
 * Settings layout:
 *  -- Controls
 *       [Key]  Start Record
 *       [Key]  Stop Record
 *       [Key]  Play Last
 *       [Key]  Stop Playback
 *       [Key]  Play Macro Key
 *  -- Behaviour
 *       [Btn]  Loop
 *       [Btn]  Record On First Input
 *       [Btn]  Record Other Player
 *       [Btn]  Show Indicator
 *  -- Record Channels
 *       [Btn]  Rec Camera / Rec Movement / Rec Sprint / Rec Jump / Rec Crouch
 *       [Btn]  Rec Attack / Rec Use / Rec Drop / Rec Slot
 *  -- Playback Channels
 *       [Btn]  Play Camera / Play Movement / Play Sprint / Play Jump / Play Crouch
 *       [Btn]  Play Attack / Play Use / Play Drop / Play Slot
 *  -- Macro Files
 *       [Slider] Active Macro  (index into the saved macro list)
 *       [Btn]    Save Recording
 *       [Btn]    Delete Active
 *       [Btn]    Play Macro
 *
 * Usage:
 *   1. Enable the script. Saved macro names are printed to chat with their
 *      slider index.
 *   2. Bind and press "Start Record" -> recording begins.
 *   3. Press "Stop Record" -> recording stops and is kept in memory.
 *   4. Press "Play Last" to replay what was just recorded.
 *   5. Toggle "Save Recording" on to store it as rec_<unix time>. The buttons
 *      fire on the off -> on edge, so toggle them back off to arm them again.
 *   6. To replay a saved macro, point "Active Macro" at its index and press
 *      "Play Macro Key" (or toggle the "Play Macro" button).
 *
 * Notes on this build:
 *   - Channel toggles are read with the module name, not the group name; RSL
 *     registers settings by group but looks them up by module.
 *   - Setting names are unique across groups because lookup is flat by name.
 *   - Keybind names are passed without the "key." prefix. RSL strips that
 *     prefix when it builds its keybind map, so "key.forward" matches nothing.
 *   - Swap-hands was dropped: there is no offhand on 1.8.9.
 *   - Nothing in this file may contain the eight letters of the java keyword
 *     for pulling in a class, or RSL refuses to load it unless "Allow unsafe
 *     scripts" is on. Comments are scanned too.
 *   - Rotations are written to both the entity and the outgoing PlayerState so
 *     the replayed aim matches what was recorded on the same tick.
 */

// Sandbox provides ArrayList, HashMap, List and Map already.

// ---------------------------------------------------------------------------
// Per-tick frame data, stored as Map<String,Object> so it can be serialised to
// JSON and read back with the built-in Json class.
// Keys: yaw, pitch, fwd, left, back, right, jump, sprint, sneak,
//       attack, use, drop, slot, repeats
// A numeric channel that was not recorded is stored as NOT_SET; a boolean
// channel that was not recorded is stored as null.
// ---------------------------------------------------------------------------

final double NOT_SET = Double.MIN_VALUE;
final int MAX_MACROS = 64;

// -- runtime state ----------------------------------------------------------
List<Map<String, Object>> recordedFrames = new ArrayList<>();   // current session
List<Map<String, Object>> playbackFrames = new ArrayList<>();   // frames being replayed
int playbackIndex = 0;
int currentRepeat = 0;
boolean isRecording = false;
boolean isPlaying = false;
boolean waitingForFirstInput = false;

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
    say("&aMacroRecorder enabled.");
    listMacros();
}

void onDisable() {
    if (isRecording) stopRecording(false);
    if (isPlaying) stopPlayback(false);
    releaseAllKeys();
}

// ---------------------------------------------------------------------------
// Main tick - key detection, frame recording and playback
// ---------------------------------------------------------------------------
void onPreMotion(PlayerState state) {
    Entity player = client.getPlayer();
    if (player == null) return;

    // -- read settings ------------------------------------------------------
    // Note: the first argument is the MODULE name. Passing the group name here
    // silently resolves to nothing and every read comes back false.
    boolean loop = modules.getButton(scriptName, "Loop");
    boolean recordOnFirstInput = modules.getButton(scriptName, "Record On First Input");

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
    boolean pbMovement = modules.getButton(scriptName, "Play Movement");
    boolean pbSprint = modules.getButton(scriptName, "Play Sprint");
    boolean pbJump = modules.getButton(scriptName, "Play Jump");
    boolean pbCrouch = modules.getButton(scriptName, "Play Crouch");
    boolean pbAttack = modules.getButton(scriptName, "Play Attack");
    boolean pbUse = modules.getButton(scriptName, "Play Use");
    boolean pbDrop = modules.getButton(scriptName, "Play Drop");
    boolean pbSlot = modules.getButton(scriptName, "Play Slot");

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
            if (keybinds.isPressed("forward") || keybinds.isPressed("back")
                    || keybinds.isPressed("left") || keybinds.isPressed("right")
                    || keybinds.isPressed("jump") || keybinds.isPressed("sneak")
                    || keybinds.isPressed("attack") || keybinds.isPressed("use")) {
                waitingForFirstInput = false;
            }
        }

        if (!waitingForFirstInput) {
            double yaw = NOT_SET;
            double pitch = NOT_SET;
            double slot = NOT_SET;
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
                    double dx = target.getX() - target.getPrevX();
                    double dz = target.getZ() - target.getPrevZ();
                    fwd = false;
                    back = false;
                    left = false;
                    right = false;

                    if (dx * dx + dz * dz > 0.0001) {
                        double moveAngle = Math.toDegrees(Math.atan2(-dx, dz));
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
                fwd = recMovement ? (Object) keybinds.isPressed("forward") : null;
                left = recMovement ? (Object) keybinds.isPressed("left") : null;
                back = recMovement ? (Object) keybinds.isPressed("back") : null;
                right = recMovement ? (Object) keybinds.isPressed("right") : null;
                jump = recJump ? (Object) keybinds.isPressed("jump") : null;
                sprint = recSprint ? (Object) player.isSprinting() : null;
                sneak = recCrouch ? (Object) player.isSneaking() : null;
                attack = recAttack ? (Object) keybinds.isPressed("attack") : null;
                use = recUse ? (Object) keybinds.isPressed("use") : null;
                drop = recDrop ? (Object) keybinds.isPressed("drop") : null;
                if (recSlot) slot = inventory.getSlot();
            }

            Map<String, Object> frame = buildFrame(yaw, pitch, fwd, left, back, right,
                    jump, sprint, sneak, attack, use, drop, slot, 1);

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

        // camera: the entity rotation survives the tick, the PlayerState is
        // what actually leaves in this tick's position packet.
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

        // movement keys
        if (pbMovement) {
            keybinds.setPressed("forward", isDown(frame, "fwd"));
            keybinds.setPressed("left", isDown(frame, "left"));
            keybinds.setPressed("back", isDown(frame, "back"));
            keybinds.setPressed("right", isDown(frame, "right"));
        } else {
            keybinds.setPressed("forward", false);
            keybinds.setPressed("left", false);
            keybinds.setPressed("back", false);
            keybinds.setPressed("right", false);
        }

        if (pbJump) {
            Object jumpO = frame.get("jump");
            if (jumpO != null) {
                boolean j = (boolean) jumpO;
                client.setJump(j);
                keybinds.setPressed("jump", j);
            }
        }

        if (pbSprint) {
            Object sprintO = frame.get("sprint");
            if (sprintO != null) client.setSprinting((boolean) sprintO);
        }

        if (pbCrouch) {
            Object sneakO = frame.get("sneak");
            if (sneakO != null) {
                boolean s = (boolean) sneakO;
                client.setSneak(s);
                keybinds.setPressed("sneak", s);
            }
        }

        if (pbAttack) {
            Object attackO = frame.get("attack");
            if (attackO != null) {
                boolean att = (boolean) attackO;
                keybinds.setPressed("attack", att);
                if (att) keybinds.leftClick();
            }
        }

        if (pbUse) {
            Object useO = frame.get("use");
            if (useO != null) {
                boolean u = (boolean) useO;
                keybinds.setPressed("use", u);
                if (u) keybinds.rightClick();
            }
        }

        if (pbDrop && isDown(frame, "drop")) {
            client.dropItem(false);
        }

        if (pbSlot) {
            double slotD = getDouble(frame, "slot");
            if (slotD != NOT_SET) {
                int s = (int) slotD;
                if (s >= 0 && s <= 8) inventory.setSlot(s);
            }
        }

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
    playbackIndex = 0;
    currentRepeat = 0;
    isPlaying = true;
    say("&aPlaying last recording (" + playbackFrames.size() + " frames)." + (loop ? " &7[Looping]" : ""));
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
    playbackIndex = 0;
    currentRepeat = 0;
    isPlaying = true;
    say("&aPlaying macro '&f" + name + "&a' (" + playbackFrames.size() + " frames)." + (loop ? " &7[Looping]" : ""));
}

void stopPlayback(boolean announce) {
    isPlaying = false;
    playbackIndex = 0;
    currentRepeat = 0;
    releaseAllKeys();
    if (announce) say("&ePlayback stopped.");
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
// Key release helper. RSL strips the "key." prefix from keybind descriptions,
// so these are the names its map is keyed by.
// ---------------------------------------------------------------------------
void releaseAllKeys() {
    keybinds.setPressed("forward", false);
    keybinds.setPressed("back", false);
    keybinds.setPressed("left", false);
    keybinds.setPressed("right", false);
    keybinds.setPressed("jump", false);
    keybinds.setPressed("sprint", false);
    keybinds.setPressed("sneak", false);
    keybinds.setPressed("attack", false);
    keybinds.setPressed("use", false);
    keybinds.setPressed("drop", false);
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
                               double slot, int repeats) {
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
    if (getDouble(a, "yaw") != getDouble(b, "yaw")) return false;
    if (getDouble(a, "pitch") != getDouble(b, "pitch")) return false;
    if (getDouble(a, "slot") != getDouble(b, "slot")) return false;
    return true;
}

boolean isDown(Map<String, Object> frame, String key) {
    Object v = frame.get(key);
    return v != null && (boolean) v;
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
    String[] boolKeys = {"fwd", "left", "back", "right", "jump", "sprint", "sneak", "attack", "use", "drop"};
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < frames.size(); i++) {
        Map<String, Object> frame = frames.get(i);
        if (i > 0) sb.append(",");
        sb.append("{");
        appendNum(sb, frame, "yaw");
        sb.append(",");
        appendNum(sb, frame, "pitch");
        sb.append(",");
        appendNum(sb, frame, "slot");
        sb.append(",");
        appendNum(sb, frame, "repeats");
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

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Json obj = items.get(i);
            Map<String, Object> frame = new HashMap<>();

            frame.put("yaw", parseDouble(obj.get("yaw")));
            frame.put("pitch", parseDouble(obj.get("pitch")));
            frame.put("slot", parseDouble(obj.get("slot")));
            frame.put("repeats", parseDouble(obj.get("repeats")));

            frame.put("fwd", parseNullableBool(obj.get("fwd")));
            frame.put("left", parseNullableBool(obj.get("left")));
            frame.put("back", parseNullableBool(obj.get("back")));
            frame.put("right", parseNullableBool(obj.get("right")));
            frame.put("jump", parseNullableBool(obj.get("jump")));
            frame.put("sprint", parseNullableBool(obj.get("sprint")));
            frame.put("sneak", parseNullableBool(obj.get("sneak")));
            frame.put("attack", parseNullableBool(obj.get("attack")));
            frame.put("use", parseNullableBool(obj.get("use")));
            frame.put("drop", parseNullableBool(obj.get("drop")));

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
