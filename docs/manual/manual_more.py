"""More manual content: the rest of 3.2 (T10–T35) and the ideas section. Imported by build_manual.py."""


def build(task, steps, gaps, E):
    def T(tid, goal, start, rows, sc, friction, ideal, how="", np=False):
        body = steps(rows) if rows else ""
        if np:
            how = '<span class="np">Not possible today</span> ' + how
        return task(tid, goal, start, body, sc, friction, ideal, how=how)

    s = lambda a, t, z, w, sh=False: (a, t, z, w, sh)

    sec32b = f'''
<h3 id="s3-2-tricks">Two hidden fast routes</h3>
<p><b>Hold several pads, then play one key:</b> every selected step gets that note. This is the cheapest way to enter a repeated note, a bassline or a hand-made arpeggio, and nothing on screen says so (<code>GrooveState.kt:421</code>).</p>
<p><b>With Shift latched, a pad tap selects or deselects</b> and never deletes, so you can enter notes without long-presses (<code>StepPads.kt:111</code>). The catch: Undo reads Redo, and holding two pads starts a step loop.</p>

<h3 id="s3-2-bass">Bassline</h3>
{T("3-2-t10", "Root notes on A with octave jumps: A on 1, 3, 7, 9, 11, 15, high A on 4 and 12", "Seq, slot 2, empty pattern, stopped.",
   [s("Long-press pad 1 and keep holding", "L", "Y", "pads"), s("Touch pads 3, 7, 9, 11, 15 (they join)", "R", "S", "pads (7, 9 centre)"),
    s("Tap A3: all six steps get A3", "L", "Y", "keys"), s("Tap the display", "L", "Y", "display"),
    s("Long-press pad 4, hold; touch pad 12; tap A4", "L, R", "Y", "pads, keys"), s("Tap the display", "L", "Y", "display")],
   [("touches", "12", "ok"), ("hidden trick", "1", "warn"), ("risk", "pads shrink as keys appear", "warn")],
   ["The fast route is undiscoverable; most players do select → key → deselect per note (24 touches).", "All six notes get one key-touch velocity.", "A bass usually lives at A1/A2, below the keys."],
   ["Always-visible pen keys: tap A3, then tap the pads; tap A4, then tap 4 and 12. 10 touches, nothing moves under the thumbs."])}
{T("3-2-t11", "Put a low E (E2) on step 13, below the keys", "Seq, slot 2, a bassline.",
   [s("Long-press pad 13", "R", "Y", "pads"), s("Tap E3", "L", "Y", "keys"), s("Tap Shift", "R", "S", "top bar, right"),
    s("Drag Note down 12 semitones to E2", "R", "Y", "strip 1", True), s("Tap Shift off, tap the display", "R, L", "S", "top bar, display")],
   [("touches", "6", "warn"), ("stretches", "2", "warn"), ("risk", "double-tap Note = C4", "warn")],
   ["72 notes on one strip is fiddly; fine mode needs a second finger.", "The alternative, the sound's Octave −1, moves the whole slot and the key labels lie."],
   ["A keyboard octave shift on Seq with labels following, or Octave ± on the empty Shift strip 2."])}
{T("3-2-t12", "Accent the downbeats, make step 7 a short blip, leave 8 silent", "Bassline playing, nothing selected.",
   [s("Long-press pad 1, hold; touch 5, 9, 13", "L, R", "S", "pads"), s("Drag Velocity to 100%; tap the display", "R, L", "Y", "strip 1, display"),
    s("Long-press pad 7; drag Length to 1/4; tap the display", "L, R, L", "S", "pads, strip 2"), s("Tap pad 8 to empty it", "L", "S", "pads")],
   [("touches", "10", "warn"), ("accent audible", "barely", "bad")],
   ["Accents are nearly inaudible: 80% → 100% is about +1.5 dB (<code>Voice.cpp:174</code>) unless Filter Vel is raised.", "Velocity and length aren't drawn anywhere."],
   ["Velocity as fill height; an Accent toggle with a clear boost to level and filter; tails for length."])}
{T("3-2-t13", "Slide from step 3 into step 4, like an acid line", "Seq, slot 2 (a Poly preset), playing.",
   [s("Synth tab; Osc; Shift; Mode to Mono; Glide up; Shift off", "L / R", "S", "top bar, rail, strips", True),
    s("Seq tab; long-press pad 3; Length to 1½ so it overlaps step 4; tap the display", "L / R", "S", "top bar, pads, strip 2")],
   [("touches", "10", "bad"), ("screen switches", "2", "bad"), ("stretches", "5", "bad")],
   ["Glide hits every note that starts while the last one sounds, release tail included (<code>Voice.cpp:77</code>): all notes slide a little or none do.", "Velocity on the slid note is ignored."],
   ["A Slide flag on the empty Shift strip 2 (glide only into that step, legato); a tie line on the pad. 4 touches, no screen switch."], np=True,
   how="A per-step slide doesn't exist; the steps below get the closest thing.")}

<h3 id="s3-2-chords">Chords</h3>
<p class="note">T4 above places chords on pads 1, 5, 9, 13 of a 16-step pattern: that is one chord per <b>beat</b>. One chord per bar is T14.</p>
{T("3-2-t14", "Am | F | C | G, one chord per bar, each ringing for the whole bar", "Seq, slot 4, empty, stopped.",
   [s("Drag Length (pattern) to 64", "R", "Y", "strip 2"), s("Long-press pad 1; hold A3 + C4 + E4 together", "L, both + 1 finger", "S", "pads, keys"),
    s("Drag Length (step) to 16; tap the display", "R, L", "Y", "strip 2, display"), s("Page 2: same with F3 A3 C4; page 3: C4 E4 G4; page 4: G3 B3 D4", "L / R", "Y", "rail, pads, keys")],
   [("touches", "≈28", "bad"), ("fingers per chord", "3", "bad"), ("page trips", "4", "warn")],
   ["A bar is a page, so a 4-bar progression is four page trips and a Length per chord.", "Step Length tops out at 16 steps; a 2-bar chord means Rate 1/8 for the whole part.", "The tail isn't drawn and pages don't follow the playhead."],
   ["“Hold until the next chord” length (tie to the next lit step); a pitch roll showing chord bars; page copy for repeats."])}
{T("3-2-t15", "Make it Am7, and play the F as F/A", "T14's progression.",
   [], [("add a note", "not possible", "bad"), ("4-note chord", "needs 4 fingers", "bad")],
   ["A chord is only “the keys held together”. Adding the 7th means a live-record overdub timed onto the downbeat (2 screen switches, timing risk).", "An inversion means replaying the chord; voicings above B4 need the Note strip afterwards.", "The footer never names the chord."],
   ["First key replaces, later keys toggle: a 7th is 1 tap, an inversion 2 taps; the keys light the step's notes. Optionally a chord-shape strip (triad / 7th / sus) on the empty Shift strip 3."],
   np=True, how="Adding one note to a chord, or a 4-note chord with two thumbs, isn't possible.")}
{T("3-2-t16", "Stab Am on 1, 4, 7, 11, 14", "Seq, slot 4, empty.",
   [s("Long-press pad 1, hold; touch 4, 7, 11, 14", "L, R", "S", "pads"), s("Lift; hold A3 + C4 + E4", "both + 1", "S", "keys"), s("Tap the display", "L", "Y", "display")],
   [("touches", "9 (5 via the pen)", "ok"), ("fingers", "3", "warn")],
   ["“A tap enters the last chord” is invisible, so you can't see what the pen holds.", "Changing to F for the next stab is a full select → chord → deselect."],
   ["Show the pen chord in the footer (“pen: Am”); pen keys always visible."])}

<h3 id="s3-2-melody">Melody</h3>
{T("3-2-t17", "Step-write an 8-note lead with rests between phrases and a long last note", "Seq, slot 3, empty.",
   [s("Tap Shift (latch)", "R", "S", "top bar, right"), s("For each note: tap the pad (selects), tap the key, tap the pad again", "L / R", "Y", "pads, keys", True),
    s("Shift off; long-press the last note; Length to 4; tap the display", "R, L, R, L", "S", "top bar, pads, strip 2")],
   [("touches", "29 (25 the usual way)", "bad"), ("long-presses", "0–8", "warn")],
   ["Step Length isn't on the Shift layer, so a long note forces Shift off.", "The line can't be read back (T1)."],
   ["Pen keys + tap pad = 2 touches per note; Shift + drag along the pads draws a note's length."])}
{T("3-2-t18", "Hand-made arpeggio: A C E A up the 16ths; then an A octave bounce", "Seq, slot 3, empty (no arpeggiator in v1).",
   [s("Long-press 1, hold; touch 5, 9, 13; tap A3; tap the display", "L, R, L", "S", "pads, keys"),
    s("Same for 2, 6, 10, 14 → C4; 3, 7, 11, 15 → E4; 4, 8, 12, 16 → A4", "L / R", "S", "pads, keys")],
   [("touches", "24", "warn"), ("long-presses", "4", "warn")],
   ["Each new pitch needs a fresh selection gesture.", "No “repeat this beat” and no fill gesture."],
   ["Pen keys + tap; an every-Nth-step fill (Shift + drag across pads); the v1.1 arpeggiator writing hits."])}

<h3 id="s3-2-arrange">Editing and arranging</h3>
{T("3-2-t19", "Move the note on step 6 to step 7", "Seq, slot 3, a lead.",
   [s("Long-press pad 6 to read it (“C4”); tap the display", "L", "Y", "pads, display"), s("Long-press pad 7; tap C4; tap the display", "L", "S", "pads, keys"), s("Tap pad 6 to delete it", "L", "Y", "pads")],
   [("touches", "6", "warn"), ("values kept", "no", "bad")],
   ["The obvious route (tap 6 off, tap 7 on) enters the last-played chord, not step 6's note.", "Velocity, length, chance and nudge don't come along."],
   ["Hold a lit pad and drag it onto another pad = move with all values; Shift + drag = copy."])}
{T("3-2-t20", "Take the E out of this chord · insert a rest and push the rest of the line later", "Seq, a part.",
   [], [("remove one chord note", "4–5 (replay)", "warn"), ("insert / delete a step", "not possible", "bad")],
   ["No insert or delete that shifts the line; you move each later note by hand, from the end (about 6 touches per note).", "Tapping a lit pad is also how you add, so a stray tap is a silent delete."],
   ["With a step selected, Shift + swipe the pads right / left inserts / deletes a step from there on."], np=True,
   how="Deleting a step is one tap; shifting the line isn't possible.")}
{T("3-2-t21", "Shift the whole pattern one step later", "Seq, slot 2, bassline.",
   [], [("rotate", "not possible", "bad"), ("workaround", "Nudge +50% on all, half a step only", "warn")],
   ["Nudge moves at most half a step, and a multi-select overwrites existing nudges."],
   ["Shift + swipe across the pads (no selection) rotates one step per pad width, wrapping at Length; one undo step."], np=True)}
{T("3-2-t22", "Copy step 3's chord onto 11 and 15", "Seq, slot 4.",
   [s("Long-press pad 3 to read it; tap the display", "L", "Y", "pads, display"), s("Long-press 11, hold; touch 15", "R", "Y", "pads"),
    s("Hold A3 + C4 + E4; tap the display", "both + 1, L", "S", "keys, display")],
   [("touches", "8 (2 if it was the last chord played)", "warn"), ("values copied", "no", "bad")],
   ["No copy; the pen route needs you to know what the pen holds."],
   ["Shift + drag a lit pad onto other pads; selecting a step loads its notes into the pen."])}
{T("3-2-t23", "Double my loop: bar 2 the same as bar 1, then change its last two notes", "Seq, slot 2, 16-step bass.",
   [s("Drag Length to 32", "R", "Y", "strip 2"), s("Tap page 2", "L", "Y", "rail"), s("Re-enter bar 1 by hand, then vary it", "L / R", "S", "pads, keys")],
   [("touches", "2 + 12–25", "bad"), ("duplicate", "not possible", "bad"), ("risk", "old hidden steps reappear", "warn")],
   ["Shortening Length keeps the hidden steps, so lengthening brings old notes back (<code>PatternStore.kt:194</code>).", "Melodic re-entry costs far more than drums."],
   ["A ×2 chip (see “Ideas” below): 1 touch doubles the length with the content; Shift + tap a page copies the current page there."])}
{T("3-2-t24", "Move the whole bassline up two semitones", "Seq, slot 2, 11 lit steps.",
   [s("Tap Shift (latch)", "R", "S", "top bar, right"), s("For each lit step: tap pad, drag Note up 2, tap pad", "L / R", "Y", "pads, strip 1", True), s("Tap Shift off", "R", "S", "top bar, right")],
   [("touches", "35", "bad"), ("risk", "multi-select flattens (T5)", "bad")],
   ["No relative transpose; you must know each target note name.", "Octave and Fine live on the sound, not the part."],
   ["Relative Note on a multi-selection (“+2”) plus select-all (Shift + tap a page selects its lit steps). 4 touches."])}
{T("3-2-t25", "I wrote it in A minor: make it C minor", "Seq, a part written in A minor.",
   [s("Tap Shift; drag Key to C; tap Shift off", "R", "S", "top bar, strip 3", True)],
   [("touches", "3 for the setting", "ok"), ("part moves", "no", "bad"), ("repair", "3 per ringed step", "warn")],
   ["Stored notes don't move (F8): out-of-key steps get a ring and the part still plays in A.", "“Key” means which keys work, not which key the part is in."],
   ["On a key change the footer offers “transpose parts +3” as one undoable action; a double-tap “snap out-of-key steps”."], np=True,
   how="Moving the part to the new key isn't possible as one action.")}
{T("3-2-t26", "Layer a second synth on the same bassline (slot 2 → slot 6)", "Seq, slot 2, bass done.",
   [], [("copy to a slot", "not possible", "bad"), ("re-enter", "≈15+", "bad"), ("tape workaround", "8 touches, frozen audio", "warn")],
   ["The most common layering move costs a full rewrite; committing to tape freezes it and Tape has no slot picker."],
   ["Hold a slot's overview row and swipe to another slot = copy this pattern there. 2 touches."], np=True)}

<h3 id="s3-2-feel">Feel</h3>
{T("3-2-t27", "Swing the bass the same as the drums", "Seq, slot 1 at Shuffle 30%, playing.",
   [s("Read “shuffle 30%” in the footer", "–", "Y", "display"), s("Swipe to slot 2", "L", "Y", "display"), s("Drag Shuffle to 30%", "R", "Y", "strip 1")],
   [("touches", "2", "ok")], ["Shuffle is per slot and per pattern; a new pattern starts at 0%."],
   ["One session swing by default; new patterns inherit the slot's last settings."])}
{T("3-2-t28", "Make all the notes shorter · then half-time", "Seq, slot 2, nothing selected.",
   [s("Drag Gate down", "R", "Y", "strip 3"), s("Drag Rate to 1/8", "R", "Y", "strip 4")],
   [("touches", "2", "ok"), ("applies to", "only some notes", "warn")],
   ["Steps with their own Length ignore Gate, and nothing shows which do.", "Gate tops at 100%, so it never gives legato."],
   ["Show own-length steps as tails; Shift + Gate = set every step's Length to the gate."])}
{T("3-2-t29", "Record a lead live and keep my timing · or quantise it after", "As T7.",
   [], [("quantise", "always on", "warn"), ("keep feel", "not possible", "bad"), ("quantise after", "not possible", "bad")],
   ["Notes snap to the nearest step with nudge 0 and the pattern gate; held length is dropped; a second pass stacks notes."],
   ["Recording keeps the feel (offset → nudge, held length → Length) and a Q chip tightens it in one tap (see “Ideas”)."], np=True)}
{T("3-2-t30", "Let the fill on 14–16 only play sometimes", "Seq, slot 3, lead.",
   [s("Long-press pad 14, hold; touch 15, 16", "R", "Y", "pads"), s("Drag Chance to 50%", "R", "Y", "strip 3"), s("Tap the display", "L", "Y", "display")],
   [("touches", "5", "ok"), ("risk", "empty steps get the last chord", "warn")],
   ["Moving a strip over an empty selected step writes the last-played chord there.", "The chance bar is small; the overview shows nothing; no “every 2nd loop”."],
   ["Skip empty steps unless a key is played; dimmer overview cells; later 1:2 / 1:4 conditions."])}
{T("3-2-t31", "Humanise it: loosen velocity and timing a bit", "Seq, slot 3, 12 lit steps.",
   [], [("touches by hand", "48", "bad"), ("random spread", "not possible", "bad")],
   ["A multi-selection sets one absolute value, so “a bit different each” is one step at a time."],
   ["The empty Shift strip 3 as “Spread”: on a selection it randomises velocity and nudge around their values, seeded and undoable. 4 touches."], np=True)}

<h3 id="s3-2-perform">Performance</h3>
{T("3-2-t32", "Mute the bass while I jam · solo the lead", "Seq, slot 3, playing.",
   [s("Mixer tab; Shift; tap slot 2's level bar; Shift off; Seq tab", "L / R", "S", "top bar, mixer", True)],
   [("mute", "5 touches, 2 switches", "bad"), ("solo", "not possible", "bad")],
   ["Mute lives on another screen; no solo; Seq and Synth don't show mute state."],
   ["A Mute chip beside Undo in the Seq and Synth footers; Shift + Mute = solo. 1 touch."])}
{T("3-2-t33", "Chorus: drums, bass and chords to pattern 2 together", "Seq, patterns 1 playing; pattern 2 written in slots 1, 2, 4.",
   [s("Tap Shift; drag Pattern to 2", "R", "S", "top bar, strip 2", True), s("Swipe to slot 2; Pattern to 2; swipe to slot 4; Pattern to 2", "L, R", "Y", "display, strip 2", True), s("Tap Shift off", "R", "S", "top bar, right")],
   [("touches", "7", "warn"), ("risk", "slots switch at different bars", "warn")],
   ["You can't write pattern 2 while pattern 1 plays: Seq only edits the playing pattern.", "No view of which patterns hold notes; no copy to start from."],
   ["Switch all slots at once; edit any pattern while another plays (a pattern row on the display); copy into an empty pattern."])}
{T("3-2-t34", "Let me hear just this part while I fix it", "Seq, slot 2, playing.",
   [], [("isolate", "10–14 touches", "bad"), ("hear one step", "not possible", "bad")],
   ["Muting the others is a Mixer detour; a step loop pins both thumbs.", "Hearing a step's note without changing it isn't possible: a key overwrites it."],
   ["Shift + Mute = solo; long-pressing a pad plays its note or chord once."])}
{T("3-2-t35", "Is it safe to edit while it's playing?", "Seq, any synth slot.",
   [], [("hidden rules", "3", "warn")],
   ["With Record armed, keys played into a selected step also record at the playhead, so stray notes stack.", "When a queued pattern switch lands, your selection disappears.", "Pads stay on the chosen page while the playhead moves on."],
   ["While Record is armed, selecting a step stops note recording; optional page follow; keep the selection after a switch."])}
{gaps(["No move, copy, insert or rotate.", "No relative transpose or transpose-to-key.", "No per-step slide; no unquantised record; no humanise spread.",
       "No copy of a part to another slot; mute only on the Mixer; no solo.", "Can't edit a pattern that isn't playing.",
       "Shift-layer strips 2 and 3 are unused with steps selected: room for Slide and Spread."])}'''

    ideas = [
        ("1", "How do I double my beat?", "One button doubles the loop and copies its notes and automation.", "Not possible (≈18 touches by hand).", "<b>×2 chip</b> in the Seq footer beside Undo: Length × 2, content copied with every value and motion; Shift + ×2 halves.", "1", "high", "S", "v1"),
        ("2", "Copy bar 1 into bar 2 / 3 / 4", "Page copy and paste.", "Not possible.", "Shift + tap page <i>n</i> on the rail copies the page you're viewing there.", "1 + Shift", "high", "S", "v1"),
        ("3", "Make a variation of this pattern", "Duplicate a clip or pattern.", "Not possible; new patterns are empty.", "<b>Dup chip</b>: copies into the next empty pattern and queues it; Shift + Dup copies without switching.", "1", "high", "S", "v1"),
        ("4", "How do I quantise my beat?", "Quantise with a strength; record loose, tighten after.", "Recording always snaps and loses the feel.", "Recording keeps the feel (offset → nudge); <b>Q chip</b> tightens 100%, Shift + Q 50%.", "1", "high", "S / M", "v1"),
        ("5", "Drop the kick for a bar", "Mute / solo per track or sound.", "Partial: slot mute on the Mixer only.", "Shift + tap a sound-grid cell mutes that sound; Shift + double-tap solos.", "1 + Shift", "high", "S–M", "v1"),
        ("6", "Hats on every 2nd step", "Fill / Euclidean helpers.", "Not possible; tap each pad.", "Shift + tap pad A, then pad B: fills the lane every B − A steps.", "2 + Shift", "med", "S", "v1"),
        ("7", "Give me a click and a count-in", "Metronome and count-in.", "None.", "Shift + tap Record toggles the click; arming while stopped gives a 1-bar count-in.", "1 + Shift", "high", "M", "v1"),
        ("8", "That was good, keep it!", "Capture what you just played without recording.", "Not possible; an idea played unarmed is lost.", "The engine keeps the last 8 bars of played notes; long-press Record keeps them as a pattern.", "1", "high", "M", "v1 / v1.1"),
        ("9", "Play chords with two thumbs", "Chord and scale modes on pads.", "Badly: 3–4 fingers.", "A Play rail item on Synth: Chord (1 / 3 / 4 notes in key), Spread, Key, Scale.", "1 per chord", "high", "M", "v1.1"),
        ("10", "Sweep a filter over the whole mix · stutter the bar end", "Master performance effects.", "None.", "A Master channel on the Mixer: DJ filter, Repeat, Drive, Click level.", "2", "high", "M", "v1.1"),
        ("11", "Arpeggiate this chord", "Arpeggiator.", "Backlog.", "Play rail · Shift: Arp mode, Rate, Octaves, Latch; records steps when armed.", "2 + hold", "high", "M", "v1.1"),
        ("12", "Roll the hats twice on this step", "Ratchet / retrigger.", "None.", "Step Shift strip 2 = Ratchet (1–4).", "3 + Shift", "high", "M", "v1.1"),
        ("13", "Surprise me", "Random and generative sequencers.", "Chance only.", "<b>Dice chip</b>: re-rolls the lane or the notes in key; undoable.", "1", "high", "M", "v1.1"),
        ("14", "Play this only every 4th time / only in the fill", "Conditional steps and fill.", "Chance only.", "Step Shift strip 3 = Cond (1:2 … 4:4, First, Fill); hold the overview for Fill.", "3 + Shift", "med", "M", "v1.1"),
        ("15", "Copy these steps over there", "Copy / paste steps with spacing.", "None.", "With steps selected, Shift + tap a pad pastes them there.", "2 + Shift", "med", "S", "v1"),
        ("16", "Switch everything to the chorus", "Scenes, chains, song mode.", "Partial: per slot.", "Pattern row with content dots; Shift + tap switches all slots; hold A + tap B chains.", "1 + Shift", "med", "M", "v1.1"),
        ("17", "Save this jam and start a new one", "Sets / projects.", "One autosaved session.", "Long-press the Tape tab: sketchbook strip (New, Duplicate, load).", "2", "med", "M", "v1.1"),
        ("18", "Undo that sound tweak", "Undo everywhere.", "Seq and Tape only.", "One history across screens; the Undo chip on every display footer.", "1", "med", "M", "v1"),
        ("19", "Transpose the whole tune up 2", "Live transpose.", "Partial: octave only; Note strip bug.", "Shift + tap a key transposes every synth slot relative to C.", "1 + Shift", "med", "M", "v1.1"),
        ("20", "Record over it · erase the snare while it plays", "Replace record; erase while recording.", "Overdub only.", "Shift + Record = replace; hold a grid cell + Clear erases that sound as the playhead passes.", "2", "med", "M", "v1.1"),
        ("21", "Roll the hats with my thumb", "Note repeat.", "None.", "Shift + hold a kit key repeats at the Seq Rate; strip 1 becomes Rate while held.", "1 + Shift", "med", "M", "v1.1"),
        ("22", "Lock the cutoff only on step 7", "Per-step parameter locks on any parameter.", "Only by recording motion.", "Selected steps stay selected across screens; a strip drag on Synth writes that step's motion.", "≈5", "med", "M", "v1.1"),
        ("23", "Same swing on drums and bass", "Global swing.", "Per slot and pattern.", "Shift + double-tap Shuffle copies it to all slots.", "1 + Shift", "low", "S", "v1"),
        ("24", "Tap in the tempo", "Tap tempo.", "Drag only.", "Tap the tempo readout 3+ times; long-press = 120.", "3–4", "low", "S", "v1"),
        ("25", "Share this loop", "Export a loop directly.", "Commit to tape, then export.", "Shift + Export renders the current patterns × N bars offline.", "2 + Shift", "med", "S–M", "v1.1"),
        ("26", "Cut this bar and paste it there (tape)", "Tape lift / drop / splice.", "Clear, undo, loop region only.", "Tape Shift: long-press a lane lifts the loop region; double-tap drops it.", "2 + Shift", "med", "L", "later"),
        ("27", "Tape stop · scrub · stutter", "Tape performance tricks.", "Fixed speeds and reverse.", "Drag the lane picture to scrub, flick down for tape stop (owner call).", "1", "med", "M", "v1.1"),
        ("28", "Turn this tape loop into a drum sound", "Resample.", "None.", "Tape: Shift + long-press a lane puts the loop region on the selected kit sound (no mic needed).", "2 + Shift", "high", "L", "later"),
        ("29", "Sample my voice", "Mic sampling.", "None (spec: later).", "Same as #28 with the mic; permission asked on first use only.", "–", "med", "L", "later"),
        ("30", "Export stems", "Per-track export.", "Master only.", "Shift + long-press Export: the 4 tape tracks + the mix.", "2", "low", "S–M", "v1.1"),
        ("31", "Shift the whole beat one step later", "Rotate a lane.", "None.", "Shift + swipe on the overview rotates one step per detent.", "1 + Shift", "low", "S", "v1.1"),
        ("32", "Sync to other gear", "MIDI and clock.", "v1.1 backlog.", "No change.", "–", "low", "L", "v1.1"),
    ]
    lvl = {"high": "High", "med": "Medium", "low": "Low"}
    rows = "".join(
        f'<tr><td class="n">{n}</td><td><b>{E(goal)}</b><div class="sub2">{E(ref)}</div></td><td>{E(today)}</td><td>{flow}</td>'
        f'<td class="c">{E(t)}</td><td class="c"><span class="pill v-{v}">{lvl[v]}</span></td><td class="c">{E(eff)}</td><td class="c">{E(rel)}</td></tr>'
        for n, goal, ref, today, flow, t, v, eff, rel in ideas)

    sec8 = f'''
<section id="s8"><h2><span class="num">8</span>Ideas from other grooveboxes</h2>
<p class="lede">Workflows musicians use all the time on pocket grooveboxes and pad controllers that this app doesn't support yet, each mapped onto our two-thumb grammar. Ideas only: no names, layouts or looks are borrowed. Ranked by value to the Noodler against effort (S ≈ 1–3 days, UI only; M touches the engine or the save format; L is a new subsystem). “+ Shift” means a stretch to the Shift button and back.</p>
<div class="tbl"><table class="ideas"><thead><tr><th>#</th><th>Goal</th><th>Today</th><th>Proposed flow</th><th>Touches</th><th>Value</th><th>Effort</th><th>Release</th></tr></thead><tbody>{rows}</tbody></table></div>
<p><b>Suggested v1 bundle:</b> one footer row on Seq — Undo · ×2 · Dup · Q — all for the left thumb; page copy and step paste on Shift + tap; sound mute and solo; click and count-in; swing to all slots and tap tempo. Capture (#8) is the closest match to “never lose an idea” but needs an audio-thread review.</p>

<h3 id="s8-double">Worked proposal: “How do I double my beat?”</h3>
<p><b>Today:</b> drag Length 16 → 32, tap page 2, re-enter every hit lane by lane: about 18 touches for a beat, 12–25 for a bassline. Shortening a pattern keeps its hidden steps, so lengthening can also bring old notes back.</p>
<ol class="proposals">
<li><b>×2 chip (recommended).</b> In the Seq footer beside Undo. Length L becomes 2L; steps 1…L are copied into L+1…2L with every hit value and the motion lanes. Dimmed above 32 steps; works for odd lengths (12 → 24). The playhead keeps its place, so nothing jumps; the pads move to the new page and flash “×2 · 32 steps”. One undo step. Shift + ×2 halves. <b>1 touch, left thumb, no Shift.</b></li>
<li><b>Page copy (with it).</b> Shift + tap a page number copies the page you're viewing there: the tool for “now vary bar 2” or “bar 1 into bar 4”. 1 touch + Shift.</li>
<li><b>Lengthening repeats automatically (not recommended).</b> No new control, but hidden, and you can't make empty space on purpose.</li>
<li><b>Double all slots (later).</b> Shift + long-press ×2. Useful before a tape commit, heavy-handed otherwise.</li>
</ol>
<p class="meta">Implementation: Kotlin only (PatternStore and PatternHistory, through the existing setters). No audio-callback change.</p>

<h3 id="s8-quantise">Worked proposal: “How do I quantise my beat?”</h3>
<p><b>The catch:</b> today you can't play an unquantised beat in the first place. Live recording snaps every hit to the nearest step with nudge 0 and drops held lengths, so quantising has two parts: let recording keep the feel, then give a one-touch way to tighten it. Our hits already have nudge (±½ step) and length, so a loose hit is exactly “nearest step + nudge”: no save-format change.</p>
<ol class="proposals">
<li><b>Recording keeps the feel (prerequisite).</b> The engine already knows the exact frame of each played note; it writes the offset as nudge and the held time as length. Arithmetic only on the audio thread (no allocation or lock), one extra field in the recorded-event queue, with a host test that the offset is sample-exact.</li>
<li><b>Q chip (recommended).</b> In the footer beside Undo and ×2. Tap = tighten 100% (nudge × 0) for the selected steps, else the current drum sound, else the whole synth pattern. Shift + Q = the whole pattern at 50%: tighter but still human. Recorded lengths round to the nearest 1/16. Swing is applied on top, so it stays. The chip glows when there's something loose to tighten. One undo step. <b>1 touch.</b></li>
<li><b>A live Quantise % per pattern (v1.1 option).</b> Dial between loose and tight while listening; nothing is lost. But every strip slot is taken, and “stored vs heard nudge” gets confusing.</li>
<li><b>Quantise while recording (fallback).</b> Keep today's snap as an option (Shift + long-press Record toggles Snap / Feel). Default: Feel.</li>
</ol>
<p><b>The resulting flow for “I jammed a beat and it's sloppy”:</b> play (the feel is kept), then tap <b>Q</b>. Today that's 2 + N touches per lane, and only works on nudges you set yourself.</p>

<h3 id="s8-questions">Open questions for the owner</h3>
<ol class="proposals">
<li>Should live record keep the feel by default (recommended) or keep snapping?</li>
<li>Footer chips Undo · ×2 · Dup · Q: room for all four, or Dup and Q on Shift + ×2 / Shift + Undo?</li>
<li>Capture (keep what you just played) in v1?</li>
<li>Sound mute on Seq takes Shift + tap on the grid: acceptable that tap-to-hear is lost while Shift is latched?</li>
<li>Kick position in the drum grid: bottom-left or top-left?</li>
</ol>
</section>'''
    return sec32b, sec8
