"""Builds docs/manual/manual.html from the content below (python3 docs/manual/build_manual.py).

The manual is a CX research tool (docs/manual/00-scope.md): each task is written as it
works in build-20, scored, and given an ideal flow. Edit the content here, not the HTML.
"""
import html
import pathlib

OUT = pathlib.Path(__file__).with_name("manual.html")
E = html.escape

# Zone marks: Y in a thumb zone, S stretch, N out of reach.
ZONE = {"Y": ("in", "in reach"), "S": ("st", "stretch"), "N": ("no", "out of reach")}


def steps(rows):
    """rows: (action, thumb, zone_code, zone_text, shift)"""
    out = ['<div class="tbl"><table class="steps"><thead><tr><th>#</th><th>Action</th>'
           '<th>Thumb</th><th>Where</th><th>Shift</th></tr></thead><tbody>']
    for i, (action, thumb, z, where, shift) in enumerate(rows, 1):
        cls, label = ZONE[z]
        out.append(
            f'<tr><td class="n">{i}</td><td>{action}</td>'
            f'<td><span class="thumb t{thumb[0].lower()}">{E(thumb)}</span></td>'
            f'<td><span class="zone {cls}" title="{label}"></span>{E(where)}</td>'
            f'<td>{"<span class=\"shift\">Shift</span>" if shift else "–"}</td></tr>')
    out.append("</tbody></table></div>")
    return "".join(out)


def score(items):
    """items: (label, value, level) level: ok / warn / bad"""
    return '<ul class="score">' + "".join(
        f'<li class="{lvl}"><b>{E(v)}</b> {E(k)}</li>' for k, v, lvl in items) + "</ul>"


def task(tid, goal, start, body, sc, friction, ideal, how=""):
    f = "".join(f"<li>{x}</li>" for x in friction)
    i = "".join(f"<li>{x}</li>" for x in ideal)
    return f'''
<article class="task" id="{tid}">
  <header><span class="tid">{E(tid.upper().replace("-", " "))}</span><h4>&ldquo;{E(goal)}&rdquo;</h4></header>
  {f'<p class="how">{how}</p>' if how else ''}
  <p class="start"><span class="lbl">Start</span>{start}</p>
  {body}
  {score(sc)}
  <div class="fi">
    <div class="friction"><h5>Friction</h5><ul>{f}</ul></div>
    <div class="ideal"><h5>Ideal flow</h5><ul>{i}</ul></div>
  </div>
</article>'''


def shot(name, caption):
    return (f'<figure class="shot"><img src="img/{name}.jpg" alt="{E(caption)}" loading="lazy" '
            f'width="1170" height="540"><figcaption>{caption}</figcaption></figure>')


def strips(title, rows):
    body = "".join(f"<tr><td>{a}</td><td>{b}</td><td>{c}</td></tr>" for a, b, c in rows)
    return (f'<div class="tbl"><table class="params"><caption>{title}</caption><thead><tr><th>Strip</th>'
            f'<th>Range (default)</th><th>What it does</th></tr></thead><tbody>{body}</tbody></table></div>')


def gaps(items):
    return '<div class="gaps"><h5>Gaps</h5><ul>' + "".join(f"<li>{x}</li>" for x in items) + "</ul></div>"


# ---------------------------------------------------------------- content

TOP_FINDINGS = [
    ("high", "3.2", "The Note strip flattens a multi-step selection to one pitch: drag it with four bass steps selected and all four become A3. Undo recovers it.", "Note moves every selected step by the same interval and reads +2."),
    ("high", "3.2", "Pitch, chords, length, velocity and nudge are invisible at a glance. Reading one step takes a long-press, which can delete it (a wobble becomes a tap) or arm the keys to overwrite it.", "Note names on lit pads, a pitch roll in the display (tap a note to select it), velocity as fill height, tails for long notes."),
    ("high", "3.1 · 3.2", "No copy of a pattern or a page. Bar 2 of a 32-step pattern starts empty; a variation means re-entering everything.", "Shift + tap a page number copies the page; a Dup chip copies the pattern."),
    ("high", "3.2", "Chords need three or four fingers held together; there is no way to add or remove one note.", "While a step is selected, the first key replaces and each further key toggles a note; the keys light the chord."),
    ("high", "1.1", "Any key touch closes the preset strip, so you can't load a preset and play it. Each audition costs another long-press.", "Keys don't close the strip; group labels (Bass, Lead, Pad, Pluck, FX)."),
    ("high", "1.4 · 2.2", "Sound edits (synth and drum) have no undo. Double-tap resets to the engine default, not the preset's value.", "One drag = one undo step, as on Seq; double-tap returns to the preset's value."),
    ("med", "3.1 · 3.2", "A latched Shift turns the Undo chip into Redo; after Shift + Clear you can't undo without a stretch to Shift.", "Redo only while Shift is held (or a long-press on Undo)."),
    ("med", "3.1", "Drum names on the phone are initials (DK, CS, TS, Cras, Bloc, Shak); the Drum map is upside down against the Seq grid; the right half of the keys is dead on Drum.", "Hand-written short names; one shared kit grid in thumb reach (owner: Kick bottom-left or top-left?)."),
    ("med", "3.1 · 3.2", "Live record: drums need the Drum screen (Seq has no drum keys), so you record blind; timing is snapped (nudge 0), held length is dropped, no count-in.", "Arming Record on Seq shows keys or kit pads; optional unquantised record; count-in."),
    ("med", "1.2", "Resting a thumb on a strip for half a second locks it, and the drag that follows does nothing.", "Lock on Shift + long-press, or a longer hold with a haptic."),
    ("med", "1.3", "Key and scale live only on the Seq Shift layer (7 touches, 2 screen switches); there is no keyboard octave shift (Octave moves the sound and its pattern).", "Key, scale and keyboard octave reachable from the Synth screen."),
    ("med", "3.1 · 3.2", "Clear also resets Length, Rate, Gate, Shuffle and motion; pages don't follow the playhead; the triplet grid still groups by 4.", "Clear removes notes only; page follow; beat edges follow the rate."),
    ("low", "1.3 · 3.1", "Glide does nothing in Poly; the step Length strip does nothing on drums (one-shots); the pad-tap velocity is the last drum key's velocity.", "Dim strips that do nothing; show the entry velocity."),
]

SEC1 = f'''
<section id="s1"><h2><span class="num">1</span>Synth sounds</h2>
<p class="lede">Slots 2–8 each hold one synth; slot 1 is always the drum kit. Each synth slot keeps its own sound, strip locks and selected section.</p>

<h3 id="s1-1">1.1 Pick a sound</h3>
<p>Tap a number 2–8 in the top bar, or swipe sideways on the display: finger left goes to the next slot, finger right to the previous one (about 58&nbsp;dp per slot). The swipe stops at 2 and 8 and never jumps to the drums.</p>
<p>To change the sound in a slot, open its <b>preset strip</b>: long-press the display (easiest for the left thumb) or long-press a slot button 2–8. A row of 16 presets slides over the top row of keys; the current one is coral. Tap a name to load it. The strip closes when you touch anything else.</p>
<div class="tbl"><table class="params"><caption>The 16 presets (the groups are stored but not shown in the app)</caption>
<tbody><tr><td>Starter</td><td>First Light</td></tr><tr><td>Basses</td><td>Rubber Floor, Deep Current, Gravel Bass</td></tr>
<tr><td>Leads</td><td>Glass Wire, Copper Lead, Whistle Pine</td></tr><tr><td>Pads</td><td>Slow Tide, Velvet Fog, Northern Hall</td></tr>
<tr><td>Plucks</td><td>Rain Glass, Thumb Piano, Paper Harp</td></tr><tr><td>FX</td><td>Siren Loop, Static Garden, Laser Drip</td></tr></tbody></table></div>
{shot("synth-main", "The Synth screen: section rail, display, four strips, capsule keys.")}
{shot("synth-presets", "The preset strip open. Ten of 16 names fit; half-hidden sharps show below it.")}
{task("1-1a", "Switch to my lead sound in slot 3", "Synth screen, slot 2, stopped.",
      steps([("Swipe left about 58&nbsp;dp on the display", "L", "Y", "display", False)]),
      [("touch", "1", "ok"), ("screen switches", "0", "ok"), ("reaches", "0", "ok"), ("hidden gesture", "1", "warn")],
      ["You can't see how far a swipe will go, so a long swipe overshoots.", "The subtitle is the only confirmation."],
      ["Keep the swipe; flash the slot number and preset name large on the display while swiping."])}
{task("1-1b", "Find a bass sound for this slot", "Synth screen, slot 4 (Rain Glass), stopped.",
      steps([("Long-press the display: the strip opens", "L", "Y", "display", False),
             ("Tap “Rubber Floor”", "L", "Y", "preset strip", False),
             ("Play a low key: you hear it, but the strip closes", "L", "Y", "keys", False),
             ("Long-press the display again", "L", "Y", "display", False),
             ("Tap “Deep Current”, play, repeat for “Gravel Bass”", "L", "Y", "strip, keys", False)]),
      [("touches for 3 basses", "≈9", "warn"), ("long-presses", "3", "warn"), ("edits wiped per load", "all", "bad")],
      ["Any key touch closes the strip (<code>GrooveScreen.kt:122</code>), though the code comment promises it stays open for auditioning.",
       "Nothing says which presets are basses.", "While open, the strip covers the sharps row."],
      ["Keys don't close the strip; a tap on the display or the strip's close does. That is 1 long-press, then 1 tap per preset.",
       "Group labels or dividers: Bass · Lead · Pad · Pluck · FX."])}
{task("1-1c", "Which preset am I on, and have I changed it?", "Synth screen, any slot.",
      "<p>Read the subtitle, e.g. “Rubber Floor · slot 2 · mono”. No touches.</p>",
      [("touches", "0", "ok")],
      ["The name stays the same after edits, so an edited sound looks like the stock preset."],
      ["An “edited” mark (“Rubber Floor*”) that clears when you reload the preset."])}
{gaps(["No categories, search or favourites.", "No previous / next preset gesture.", "No way to copy one slot's sound to another."])}

<h3 id="s1-2">1.2 Shape it</h3>
<p>The rail on the left picks a section: Osc, Filter, Env or Mod. The four strips show that section's controls; tap <b>Shift</b> (top right) to latch the second layer, tap again to go back, or hold it for a momentary layer. Shift is shared by every screen, so a latched Shift stays on when you change screens.</p>
<p><b>Strip gestures:</b> drag up or down (the full range is 1.5 strip heights, so it never jumps to your finger); a second finger resting on the strip gives ¼ speed; double-tap resets to the built-in default; a 0.5&nbsp;s still hold locks the strip (padlock).</p>
<div class="params-grid">
{strips("Osc", [("Wave", "Saw › Square › Tri › Sine, morph (Saw)", "Oscillator shape"), ("PW", "5–95% (50%)", "Pulse width"), ("Sub", "0–100% (0)", "Square one octave down"), ("Noise", "0–100% (0)", "Noise level")])}
{strips("Osc · Shift", [("Octave", "−2…+2 (0)", "Transposes the sound and its pattern"), ("Fine", "±50 cents (0)", "Fine tune"), ("Glide", "0–2 s (0)", "Slide; Mono and Unison only"), ("Mode", "Poly / Mono / Unison", "4 voices, legato mono, or 4 stacked voices")])}
{strips("Filter", [("Cutoff", "20 Hz–18 kHz (2.4k)", "Brightness"), ("Reso", "0–100% (20%)", "Resonance"), ("Env", "−100…+100% (+40%)", "Envelope amount"), ("Key", "0–100% (50%)", "Key tracking")])}
{strips("Filter · Shift", [("Drive", "0–24 dB (0)", "Saturation"), ("HPF", "20 Hz–2 kHz", "Thins the low end"), ("Vel", "0–100% (30%)", "Velocity to cutoff"), ("LFO", "0–100% (0)", "LFO to cutoff")])}
{strips("Env (amp)", [("Attack", "1 ms–10 s (2 ms)", "Fade-in"), ("Decay", "1 ms–10 s (300 ms)", "Fall to sustain"), ("Sustain", "0–100% (80%)", "Level while held"), ("Release", "1 ms–10 s (250 ms)", "Tail")])}
{strips("Env · Shift (filter env)", [("F.Att", "1 ms–10 s (5 ms)", ""), ("F.Dec", "1 ms–10 s (400 ms)", ""), ("F.Sus", "0–100% (30%)", ""), ("F.Rel", "1 ms–10 s (300 ms)", "")])}
{strips("Mod (LFO)", [("Rate", "0.05–30 Hz (2 Hz), or beat divisions when synced", "Speed"), ("Depth", "0–100% (0)", "Amount"), ("Shape", "Sine / Tri / Square / S&amp;H", "Wave"), ("Dest", "Pitch / Cutoff / PW / Amp", "Target")])}
{strips("Mod · Shift", [("Sync", "Free / Tempo", "Lock to tempo"), ("Delay", "0–5 s (0)", "LFO fade-in"), ("Retrig", "Off / On", "Restart per note"), ("—", "disabled", "Reserved for tilt (v1.1)")])}
</div>
{task("1-2a", "Make this pad darker and slower", "Synth screen, slot 4 on Slow Tide, Osc section, a chord playing.",
      steps([("Tap Filter on the rail", "L", "Y", "rail", False), ("Drag Cutoff down", "R", "Y", "strip 1", False),
             ("Tap Env on the rail", "L", "Y", "rail", False), ("Drag Attack up", "R", "Y", "strip 1", False), ("Drag Release up", "R", "Y", "strip 4", False)]),
      [("touches", "5", "ok"), ("reaches", "0", "ok"), ("Shift", "0", "ok")],
      ["You can't hold a chord and edit at the same time; you need a playing pattern."],
      ["Already good: 1 select + 1 drag per parameter."])}
{task("1-2b", "Add some wobble to the bass, in time", "Synth screen, slot 2, playing.",
      steps([("Tap Mod on the rail", "L", "Y", "rail", False), ("Drag Depth up", "R", "Y", "strip 2", False),
             ("Tap Shift", "R", "S", "top bar, right", False), ("Set Sync to Tempo", "R", "Y", "strip 1", True),
             ("Tap Shift off", "R", "S", "top bar, right", False), ("Drag Rate to 1/8", "R", "Y", "strip 1", False)]),
      [("touches", "6", "ok"), ("stretches", "2", "warn"), ("Shift", "2", "warn")],
      ["Sync is on the Shift layer but has to be set before Rate.", "Mod has no picture of its own (no LFO trace).", "Forgetting to unlatch Shift leaves every screen on its second layer."],
      ["Sync on the main layer (Rate snaps to divisions below a detent); a small LFO trace. 3 touches."])}
{task("1-2c", "Keep the cutoff where it is while I mess with everything else", "Synth screen, Filter section.",
      steps([("Hold Cutoff still for 0.5 s: padlock", "R", "Y", "strip 1", False), ("Hold again to unlock", "R", "Y", "strip 1", False)]),
      [("touches", "2", "ok"), ("hidden gestures", "2", "warn")],
      ["<b>Accidental lock:</b> resting a thumb for half a second before dragging locks the strip and the drag does nothing (<code>StripGesture.kt:89</code>).",
       "A lock doesn't protect against a preset load."],
      ["One right-thumb gesture, no Shift (Shift is out of reach while the right thumb is on a strip): lock on a 1 s still hold, with a filling ring and a haptic at the lock; any movement before then cancels and the drag works as normal. Preset loads respect locks."])}
{shot("synth-locked", "A locked Cutoff strip (padlock).")}
{task("1-2d", "Make it brighter with a bit of grit", "Synth screen, Osc section.",
      steps([("Tap Filter", "L", "Y", "rail", False), ("Drag Cutoff up", "R", "Y", "strip 1", False), ("Tap Shift", "R", "S", "top bar, right", False),
             ("Drag Drive up", "R", "Y", "strip 1", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "5", "ok"), ("stretches", "2", "warn"), ("Shift", "2", "warn")],
      ["Shift is the only top-bar reach in normal sound design.", "Labels collide: Filter “Env” vs the Env section, Filter “Key” vs the session Key, Mod “Delay” vs the FX delay."],
      ["A Shift pad by the strips (ADR 0003 P4). Rename: EnvAmt, KeyTrk, Fade."])}
{gaps(["No undo or compare (see 1.4).", "Glide stays live in Poly, where it does nothing.", "No picture for the LFO, octave, glide or mode.", "No randomise for happy accidents."])}

<h3 id="s1-3">1.3 Play it</h3>
<p>The keys cover two octaves, C3 to B4. Where you hit a key sets its velocity: near the top is soft, near the bottom is loud. To play higher or lower, change the sound's <b>Octave</b> (Osc · Shift · strip 1); this moves everything the slot plays, its pattern too, and the key labels still read C3 / C4. <b>Key and scale</b> are one per session and set on the Seq screen (Shift, no step selected, strips 3 and 4); back on Synth, out-of-key keys dim and play nothing and roots get an amber stripe.</p>
{shot("synth-osc-shift", "Osc · Shift: Octave, Fine, Glide, Mode. The key labels don't follow the octave.")}
{task("1-3a", "Play only in A minor", "Synth screen, slot 2, Shift off.",
      steps([("Tap the Seq tab", "L", "S", "top bar, left", False), ("Tap Shift", "R", "S", "top bar, right", False),
             ("Drag Key to A", "R", "Y", "strip 3", True), ("Drag Scale to Minor", "R", "Y", "strip 4", True),
             ("Tap Shift off", "R", "S", "top bar, right", False), ("Tap the Synth tab", "L", "S", "top bar, left", False)]),
      [("touches", "6", "warn"), ("screen switches", "2", "bad"), ("stretches", "4", "bad"), ("Shift", "2", "warn")],
      ["A playing setting lives on the sequencer's Shift layer; nothing on Synth says where to set it.", "Key drags through 12 values, Scale through 11."],
      ["Key and Scale on the Synth screen too (e.g. a fifth “Play” rail item: Key, Scale, Octave, Mode). 3 touches, 0 switches."])}
{shot("synth-a-minor", "After setting A minor: the display reads “A minor”, roots have amber stripes.")}
{task("1-3b", "Play the bass line an octave up", "Synth screen, slot 2 (Octave −1), playing.",
      steps([("Tap Osc", "L", "Y", "rail", False), ("Tap Shift", "R", "S", "top bar, right", False),
             ("Drag Octave to 0", "R", "Y", "strip 1", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "4", "ok"), ("stretches", "2", "warn"), ("side effect", "pattern moves too", "bad")],
      ["There is no keyboard octave; playing higher always changes the sound and its pattern."],
      ["A keyboard octave shift (e.g. swipe on the keys' tray edge) with the C labels following; Octave stays a sound setting."])}
{task("1-3c", "Make it a mono lead with a slide", "Synth screen, slot 3, Osc section.",
      steps([("Tap Shift", "R", "S", "top bar, right", False), ("Drag Mode to Mono", "R", "Y", "strip 4", True),
             ("Drag Glide up", "R", "Y", "strip 3", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "4", "ok"), ("stretches", "2", "warn")],
      ["Set Glide while still in Poly and you hear nothing, with no sign why.", "Unison is monophonic, which isn't said anywhere."],
      ["Dim Glide in Poly; name the mode “Unison (mono)”."])}
{gaps(["No keyboard octave shift.", "No Key and Scale on the Synth screen.", "No hold or sustain.", "Velocity by position isn't explained on screen."])}

<h3 id="s1-4">1.4 Keep it</h3>
<p>Everything about each synth slot saves by itself, about a second after you stop changing things and when the app goes to the background: every value, the preset it came from, the selected section and the locks. To get back to a preset, open the strip and tap it again; that throws away every edit on the slot. You can't save your own preset, name a sound, or copy a sound to another slot.</p>
{task("1-4a", "Undo a tweak I didn't like", "Synth screen; you just dragged Reso too far.",
      steps([("Drag Reso back by ear", "R", "Y", "strip 2", False)]),
      [("touches", "1–2", "ok"), ("old value", "lost", "bad")],
      ["There is no synth undo. Double-tap goes to 20%, the engine default, not the preset's value."],
      ["One drag = one undo step (as on Seq); an Undo chip at the bottom-left of the Synth display; double-tap returns to the preset's value."])}
{task("1-4b", "Save this sound so I can use it in another song", "Synth screen, a sound you like.",
      "<p>Not possible. The sound lives only in its slot in the one autosaved session.</p>",
      [("status", "not possible", "bad")],
      ["Sounds are the easiest thing to reuse, and there is no user preset."],
      ["Shift + long-press the display saves the slot as a user preset, auto-named (“Rubber Floor 2”), no dialog. Hold one slot and tap another to copy a sound."])}
{gaps(["No synth undo or redo.", "No compare (A/B) with the original preset.", "No user presets, no copy between slots, no “edited” mark."])}
</section>'''

SEC2 = f'''
<section id="s2"><h2><span class="num">2</span>Drum kit</h2>
<p class="lede">Slot 1 is always the kit: 16 sounds made by the app. Tap <b>Drum</b> or slot <b>1</b>. The lowest 16 keys play the kit; the other 8 keys are dimmed and play nothing.</p>

<h3 id="s2-1">2.1 Play the kit</h3>
<p>Tap a key to play its sound; near the top of a key is soft, near the bottom is hard. Every sound plays its whole length. Playing a sound also makes it the sound being edited, here and on the Seq screen.</p>
<div class="tbl"><table class="params kit"><caption>The kit as it appears on the phone (labels are made by cutting the real names)</caption>
<thead><tr><th>Key</th><th>Label</th><th>Sound</th><th>Key</th><th>Label</th><th>Sound</th></tr></thead><tbody>
<tr><td>1 C3</td><td>DK</td><td>Deep Kick</td><td>9 G#3</td><td>OH</td><td>Open Hat (choke A)</td></tr>
<tr><td>2 C#3</td><td>PK</td><td>Punch Kick</td><td>10 A3</td><td>Cras</td><td>Crash</td></tr>
<tr><td>3 D3</td><td>CS</td><td>Crack Snare</td><td>11 A#3</td><td>Ride</td><td>Ride</td></tr>
<tr><td>4 D#3</td><td>TS</td><td>Tight Snare</td><td>12 B3</td><td>LT</td><td>Low Tom</td></tr>
<tr><td>5 E3</td><td>Clap</td><td>Clap</td><td>13 C4</td><td>MT</td><td>Mid Tom</td></tr>
<tr><td>6 F3</td><td>Rim</td><td>Rim</td><td>14 C#4</td><td>HT</td><td>High Tom</td></tr>
<tr><td>7 F#3</td><td>CH</td><td>Closed Hat (choke A)</td><td>15 D4</td><td>Bloc</td><td>Block</td></tr>
<tr><td>8 G3</td><td>TH</td><td>Tick Hat (choke A)</td><td>16 D#4</td><td>Shak</td><td>Shaker</td></tr></tbody></table></div>
{shot("drum-keys", "The Drum screen with the real labels. The small map (right of the picture) runs bottom-up; the Seq grid runs top-down.")}
{task("2-1a", "Let me just hear what's in the kit", "Any screen, stopped.",
      steps([("Tap the Drum tab", "L", "S", "top bar, left", False), ("Tap DK … Cras", "L", "Y", "keys, left", False),
             ("Tap Ride, LT, MT, HT", "either", "S", "keys, centre", False), ("Tap Bloc, Shak", "R", "Y", "keys, right", False)]),
      [("screen switch", "1", "ok"), ("stretches", "1 + 4 sounds", "warn"), ("records if armed", "yes", "warn")],
      ["<b>Names are cryptic</b>: DK, PK, CS, TS don't say kick or snare; “Cras” is cut.",
       "<b>The right thumb's half of the keys is dead</b>: 10 of 16 sounds sit under the left thumb.",
       "Three layouts to learn: the keyboard zig-zag, the Drum map (bottom-up) and the Seq grid (top-down).",
       "The map can't be touched and has no labels; nothing on the keys shows which sound is selected.",
       "Bottom-row pads only reach about 75–95% velocity; the soft zone is the blank gap above the pad."],
      ["One kit grid, the same on Drum and Seq, in thumb reach; hand-written names (Kick, Punch, Crack, Snare, Clap, Rim, CHat, Tick, OHat, Crash, Ride, LoTom, MdTom, HiTom, Block, Shakr).",
       "Velocity zone the full height of the drawn pad.", "Owner decision: Kick at bottom-left (drum-machine habit, near the thumb) or top-left (reads like steps 1–16)."])}

<h3 id="s2-2">2.2 Edit one sound</h3>
<p>Play the sound you want to change; the strips now edit it. Pitch, Tone, Level and Pan change while it plays; Decay, Start, Rev and Choke take effect on the next hit, so play the key again to hear them.</p>
<div class="params-grid">
{strips("Main", [("Pitch", "−12…+12 st (0)", ""), ("Decay", "0–100% (100%)", "Length of the sound"), ("Tone", "200 Hz–20 kHz (20k)", "Low-pass"), ("Level", "0–100% (80%)", "")])}
{strips("Shift", [("Pan", "−100…+100% (0)", "Shown as a signed %"), ("Start", "0–50% (0)", "Skips the attack"), ("Rev", "Off / On", "Reverse"), ("Choke", "None / A / B", "Hats start in A")])}
</div>
{shot("drum-shift", "Drum · Shift: Pan, Start, Rev, Choke.")}
{task("2-2a", "Make the snare shorter and darker, and push it a bit left", "Drum screen, stopped, another sound selected.",
      steps([("Tap the CS key (plays and selects it)", "L", "Y", "keys", False), ("Drag Decay down", "R", "Y", "strip 2", False),
             ("Tap CS again to hear it", "L", "Y", "keys", False), ("Drag Tone down", "R", "Y", "strip 3", False),
             ("Tap CS to check", "L", "Y", "keys", False), ("Tap Shift", "R", "S", "top bar, right", False),
             ("Drag Pan down", "R", "Y", "strip 1", True), ("Tap CS to check", "L", "Y", "keys", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "9", "warn"), ("checks needed to hear it", "3", "warn"), ("stretches", "2", "warn"), ("undo", "none", "bad")],
      ["To select a sound you must play it, and it records if Record is armed.",
       "Decay, Start, Rev and Choke need a retrigger, so the thumbs ping-pong.",
       "A strip lock applies to all 16 sounds; drum edits can't be undone; Pan has no L / R."],
      ["Silent selection on a touchable grid; replay the sound when a Decay / Start / Rev drag ends; locks per sound; Rev and Choke as tap cells; edits undoable."])}
{gaps(["No silent sound selection on Drum.", "No kit save or recall beyond autosave; no reset for a whole sound.", "The Drum screen shows no pattern, so you can't tweak while watching the beat."])}
</section>'''

SEC31 = f'''
<section id="s3-1"><h2><span class="num">3.1</span>A drum beat</h2>
<p class="lede">Everything happens on the <b>Seq</b> screen with slot 1 selected. Left: the page rail (1–4) and Clear. Display: the 4&times;4 <b>sound grid</b>, the 4&times;16 overview and the footer with <b>Undo</b>. Right: four strips. Bottom: 16 step pads.</p>
<p><b>Lanes.</b> You work on one sound at a time. Tap a sound in the grid (you hear it) and it becomes the lane. Pads show that sound: <b>bright</b> where it plays, a <b>small dot</b> where only other sounds play, dark where empty. Tap a pad to add or remove the lane's sound; other sounds on the step stay (up to 8).</p>
<p><b>Step editing.</b> Hold a pad still for a moment: it gets an amber outline and stays selected. The strips become Velocity (1–100%), Length (gate, 1/16–16 steps), Chance (0–100%) and Nudge (±50% of a step). Keep holding and touch other pads to select several. Tap the display (outside the grid) to deselect. With nothing selected the strips are Shuffle, Length (1–64 steps), Gate and Rate; with Shift, Tempo, Pattern, Key and Scale.</p>
{shot("beat-lane-hat", "A finished beat on the CH lane: bright hats, dots where other sounds play, dots on grid cells in use.")}
{task("3-1-a", "Clear this and let me start my own beat", "App just opened, Synth screen, slot 2, demo loaded.",
      steps([("Tap the Seq tab", "L", "S", "top bar, left", False), ("Swipe the display right one slot (2 → 1)", "L", "Y", "display", False),
             ("Tap Clear", "L", "Y", "rail", False), ("Tap Clear again within 2 s", "L", "Y", "rail", False)]),
      [("touches", "4", "ok"), ("hidden gesture", "1", "warn"), ("also resets", "length, shuffle, gate, rate, motion", "bad")],
      ["Seq opens on slot 2, not the drums.", "Clear wipes the settings as well as the notes.", "No “new empty pattern” action."],
      ["Clear removes steps only; Shift + Clear (or another action) resets settings."])}
{task("3-1-b", "Kick on 1, 5, 9, 13; snare on 5 and 13; hats on the off-beats", "Empty pattern, CS-free.",
      steps([("Tap DK in the grid", "L", "Y", "grid", False), ("Tap pads 1, 5, 9, 13", "L → R", "S", "pads (9 is centre)", False),
             ("Tap CS", "L", "Y", "grid", False), ("Tap pads 5, 13", "L, R", "Y", "pads", False),
             ("Tap CH", "L", "Y", "grid", False), ("Tap pads 3, 7, 11, 15", "L → R", "S", "pads (7 is centre)", False),
             ("Tap Play", "R", "S", "top bar, right", False)]),
      [("touches", "14", "ok"), ("Shift", "0", "ok"), ("stretches", "1 + 2 pads", "warn")],
      ["A tapped hit takes the velocity of the last drum key played anywhere (or 80%), with nothing on screen to say so.",
       "Selecting a lane always plays the sound.", "No quick fill (every 2nd / 4th step)."],
      ["A fill gesture (e.g. Shift + drag across pads); show the entry velocity."])}
{task("3-1-c", "Record a part live", "Seq screen, drum slot, stopped.",
      steps([("Tap Record (short tap)", "R", "S", "top bar, right", False), ("Tap Play", "R", "S", "top bar, right", False),
             ("Tap the Drum tab", "L", "S", "top bar, left", False), ("Play the keys", "both", "Y", "keys", False),
             ("Tap Record to stop", "R", "S", "top bar, right", False), ("Tap the Seq tab to see it", "L", "S", "top bar, left", False)]),
      [("screen switches", "2", "bad"), ("stretches", "5", "bad"), ("feel kept", "no (snapped)", "bad")],
      ["Seq has no drum keys, so you record blind on another screen; the sound grid never records.",
       "Hits are snapped to the nearest step with nudge 0; no count-in or click.", "A Record hold of 300 ms or more doesn't arm."],
      ["Arming Record on the drum slot shows kit pads on Seq; optional unquantised record (timing kept as nudge); count-in; Play and Record in thumb reach (P2/P3)."])}
{task("3-1-d", "Add a soft snare on 15", "The beat above, CS lane.",
      steps([("Long-press pad 15 (strips dim: no CS there yet)", "R", "Y", "pads", False),
             ("Drag Velocity to about 30% (creates the hit)", "R", "Y", "strip 1", False), ("Tap the display to deselect", "L", "Y", "display", False)]),
      [("touches", "3", "ok"), ("visible afterwards", "no", "bad")],
      ["Pad 15 then looks exactly like the full snares on 5 and 13.", "The left thumb can't reach pads 11–16, so the right thumb selects and edits in turn."],
      ["Shade the pad and overview cell by velocity."])}
{shot("beat-ghost", "A 30% ghost snare on 15 looks the same as the full hits.")}
{task("3-1-e", "Lay the snare back a little and loosen the hats", "Beat playing, nothing selected.",
      steps([("Tap CS", "L", "Y", "grid", False), ("Long-press pad 5, keep holding, touch pad 13", "L, R", "Y", "pads", False),
             ("Drag Nudge to +8%", "R", "Y", "strip 4", False), ("Tap the display", "L", "Y", "display", False),
             ("Tap CH; for each of pads 3, 7, 11, 15: long-press, drag Nudge, tap the display", "L / R", "S", "pads, strip 4", False)]),
      [("touches", "17", "bad"), ("long-presses", "5", "warn"), ("multi-select", "one value for all", "warn")],
      ["You must deselect between steps; a long-press on another pad adds to the selection.", "No random spread; nudge isn't drawn anywhere."],
      ["Long-press replaces the selection (hold-and-add extends it); Shift + Nudge on several steps = random spread; a small early/late tick on the pad."])}
{task("3-1-f", "Make the hats random", "CH lane.",
      steps([("Long-press pad 3 and keep holding", "L", "Y", "pads", False), ("Touch pads 7, 11, 15", "R", "S", "pads", False),
             ("Drag Chance to about 60%", "R", "Y", "strip 3", False), ("Tap the display", "L", "Y", "display", False)]),
      [("touches", "7", "ok"), ("risk", "wrong lane creates hits", "warn")],
      ["If the lane is wrong, dragging Chance creates hits of that sound on every selected step; the only warning is a dimmed strip.",
       "The chance bar is small and shows only for the lane; the overview shows no chance."],
      ["Confirm before a drag creates hits; show chance as a dimmer cell in the overview."])}
{shot("beat-chance", "Chance bars on the hats.")}
{task("3-1-g", "Make it 2 bars and write bar 2", "Nothing selected.",
      steps([("Drag Length to 32 (fine mode helps)", "R", "Y", "strip 2", False), ("Tap page 2", "L", "Y", "rail", False), ("Re-enter the beat by hand", "L / R", "S", "pads", False)]),
      [("touches", "2 + 16", "bad"), ("copy", "none", "bad")],
      ["Bar 2 starts empty: no page copy, no pattern duplicate.", "Pads don't follow the playhead, so you hear bar 2 while you see bar 1.", "Two different strips are called “Length”."],
      ["Lengthening repeats the existing bars, or long-press a page = “copy page here”; an optional page follow."])}
{shot("beat-page2", "Length 32, page 2: empty.")}
{task("3-1-h", "Fix a mistake", "Any.",
      '''<div class="tbl"><table class="steps"><thead><tr><th>Sub-task</th><th>How</th><th>Touches</th></tr></thead><tbody>
      <tr><td>Undo</td><td>Undo chip (left of the footer, L)</td><td>1</td></tr>
      <tr><td>Remove CS from step 5</td><td>Tap CS, tap pad 5</td><td>2</td></tr>
      <tr><td>Clear the CH lane</td><td>Shift (stretch), Clear, Clear, Shift off (stretch)</td><td>4</td></tr>
      <tr><td>Clear the pattern</td><td>Clear, Clear</td><td>2</td></tr></tbody></table></div>''',
      [("undo depth", "32, lost on restart", "warn"), ("Undo with Shift latched", "becomes Redo", "bad")],
      ["While Shift is latched the Undo chip reads Redo, so “oops, undo that lane clear” does nothing until you stretch to Shift.",
       "The lane-clear label uses the cryptic name (“Clear CH?”)."],
      ["Redo only while Shift is held (or long-press Undo); Shift unlatches after Shift + Clear."])}
{shot("beat-clear-lane", "Shift + Clear armed: “Clear CH?”, and the Undo chip now reads Redo.")}
{task("3-1-i", "What's on this step?", "Any beat.",
      steps([("Long-press the pad: footer reads “step 5 · DK · also CS”", "L / R", "Y", "pads", False),
             ("Tap other sounds in the grid to see their values (each plays)", "L", "Y", "grid", False), ("Tap the display", "L", "Y", "display", False)]),
      [("touches", "2 + 1 per sound", "warn"), ("at a glance", "no", "bad")],
      ["Pads show only lane / dot / empty; velocity, length and nudge are never visible without selecting."],
      ["Stacked ticks per sound on the pad, brightness for velocity; holding a pad lights its sounds in the grid."])}
{shot("beat-step-info", "Step 5 selected: the strips show the lane's hit, the footer lists the other sounds.")}
{task("3-1-j", "Switch to another pattern", "Beat playing.",
      steps([("Tap Shift", "R", "S", "top bar, right", False), ("Drag Pattern to 2 (switches at the end of the pattern)", "R", "Y", "strip 2", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "3", "ok"), ("stretches", "2", "warn"), ("new pattern", "empty", "bad")],
      ["New patterns are empty and there's no copy; no view of which patterns hold something; each slot switches on its own."],
      ["A pattern row on the display with “has content” dots; Shift + a pattern cell copies the current one there; an “all slots” switch."])}
{task("3-1-k", "Loop a few steps live", "Beat playing.",
      steps([("Tap Shift (latch)", "R", "S", "top bar, right", False), ("Hold pad 5", "L", "Y", "pads", True), ("Hold pad 8: steps 5–8 loop", "R", "S", "pads, centre", True),
             ("Release: back in time", "both", "Y", "pads", True), ("Tap Shift off", "R", "S", "top bar, right", False)]),
      [("touches", "4", "ok"), ("stretches", "2–3", "warn"), ("thumbs free", "none", "bad")],
      ["Both thumbs are pinned, so no strip moves during the loop.", "Only the visible page and only the selected slot loop.", "A latched Shift also changes the strips, Undo and Clear."],
      ["A latching loop (double-tap the second pad); loop all slots as an option; a Shift by the strips (P4)."])}
{gaps(["No pattern or page copy.", "Velocity, nudge and length invisible at a glance.", "No drum keys on Seq; recording snaps the feel away.",
       "The step Length strip has no effect on drums (one-shots).", "Shuffle is per slot and pattern, so drums and bass can disagree."])}
</section>'''

SEC32 = f'''
<section id="s3-2"><h2><span class="num">3.2</span>A synth part</h2>
<p class="lede">A synth part lives in one slot (2–8), in one of its 16 patterns, on the Seq screen. The keys are not shown on Seq; they slide in under the pads only while a step is selected.</p>

<h3 id="s3-2-view">Viewing notes and chords</h3>
<p>A lit pad means the step plays something. Neither the pads nor the overview show <i>which</i> note. To read a step, hold its pad for about half a second and let go: it gets an amber outline, the footer reads <code>step 3 · A2</code> (a chord reads <code>step 5 · C4 E4 G4</code>), and the keys slide in. Hold the same pad again, or tap the display, to let go.</p>
<div class="tbl"><table class="params"><caption>What each place shows today</caption><tbody>
<tr><td>Pad</td><td>Step number, lit / unlit, chance bar, out-of-key ring, motion dot. No pitch, no length, no chord count.</td></tr>
<tr><td>Overview</td><td>On / off only.</td></tr><tr><td>Footer</td><td>Notes of the last selected step only.</td></tr>
<tr><td>Keys</td><td>Light only while held; never show the step's stored notes.</td></tr><tr><td>Note strip (Shift)</td><td>First note only.</td></tr></tbody></table></div>
{shot("part-overview", "A bassline: the pads and overview are on / off only.")}
{task("3-2-t1", "What's my bassline actually playing? Which note is on step 7?", "Seq, slot 2 (demo bass), playing.",
      steps([("Long-press pad 1: footer “step 1 · A2”, keys appear", "L", "Y", "pads", False),
             ("Long-press each further lit step to read it (it joins the selection)", "L / R", "S", "pads", False),
             ("Tap the display to deselect", "L", "Y", "display", False)]),
      [("touches for 11 lit steps", "12", "bad"), ("long-presses", "11 (≈5 s holding)", "bad"), ("risk", "delete or overwrite", "bad")],
      ["Pitch is never visible at a glance; reading 16 steps means 11 long-presses and memory.",
       "A long-press that wobbles counts as a tap and deletes the note; a brushed key overwrites the selected steps.",
       "The demo bass sits at A2, below the keys' range (C3–B4), and its preset plays an octave lower again: names, keys and sound disagree."],
      ["0 touches: the pitch on every lit pad and in the overview."])}
{shot("part-step-keys", "Step 3 selected: amber outline, “step 3 · A2” in the footer, keys below. A2 isn't on the keys.")}
{task("3-2-t2", "Is step 5 a chord, and which one?", "Seq, a chord on step 5.",
      steps([("Long-press pad 5: footer “step 5 · C4 E4 G4”", "L", "Y", "pads", False), ("Tap the display", "L", "Y", "display", False)]),
      [("touches", "2", "ok"), ("at a glance", "no", "bad")],
      ["A chord pad looks exactly like a single-note pad.", "The footer lists notes in the order they were touched, not low to high.",
       "With several steps selected you see only the last one; the keys don't light the chord."],
      ["A pad shows “C” plus a small “3”, or “Cmaj”; the footer sorts and names simple chords; with a step selected the keys light its notes."])}
{shot("part-chord", "A chord on step 5: the footer names the notes; the pad and keys don't show it.")}

<h3 id="s3-2-enter">Entering notes and chords</h3>
<p>Select a step, then play a key: the step now plays that note. Hold two keys together for a chord (up to 4 notes). After you've played a note, a plain tap on an empty pad enters the same note or chord again; a tap on a lit pad empties it.</p>
{task("3-2-t3", "Put A, A, C, E on 1, 5, 9, 13", "Seq, slot 2, empty pattern.",
      steps([("Long-press pad 1: keys appear", "L", "Y", "pads", False), ("Tap A3", "L", "Y", "keys", False),
             ("Tap the display to deselect (skip it and the next key writes both steps)", "L", "Y", "display", False),
             ("Tap pad 5: it enters the last note, A3", "L", "Y", "pads", False),
             ("Long-press pad 9, tap C4, tap the display", "L / R", "S", "pads, keys", False), ("Long-press pad 13, tap E4, tap the display", "R / L", "Y", "pads, keys", False)]),
      [("touches", "10", "warn"), ("long-presses", "3", "warn"), ("risk", "forgetting to deselect", "warn")],
      ["Every new pitch costs select → key → deselect.", "Bass notes below C3 can't be played in from the keys.", "A key also sets the step's velocity from where you touched it."],
      ["A strip of keys always visible on Seq: a key sets the “pen” note and a pad tap writes it. The line above becomes 6 touches, no long-press."])}
{task("3-2-t4", "Am – F – C – G on the four beats; then add the 7th", "Seq, slot 4, empty.",
      steps([("Long-press pad 1", "L", "Y", "pads", False), ("Hold A3, C4 and E4 together (three fingers)", "both + 1", "S", "keys", False),
             ("Tap the display; repeat on pads 5, 9, 13", "L", "Y", "display, pads", False)]),
      [("touches", "12", "warn"), ("fingers per chord", "3", "bad"), ("add one note", "not possible", "bad")],
      ["Two thumbs give two-note chords; a key adds to the chord only while the earlier keys are still held.",
       "To add or remove one note you replay the whole chord; one mistimed lift replaces it with a single note."],
      ["While a step is selected, the first key replaces and each further key toggles that note; the keys light the chord. Four-note chords with two thumbs; add or remove is one tap."])}

<h3 id="s3-2-edit">Editing notes</h3>
<p>With a step selected, the strips are Velocity, Length, Chance and Nudge. With Shift, strip 1 is <b>Note</b>: it moves the step (or chord) up and down, through in-key notes only when a scale is on; strip 4 is <b>Motion</b>.</p>
{task("3-2-t5", "Step 9 should be D, not C · move these four steps up a tone", "Seq, a bassline.",
      steps([("Long-press pad 1, keep holding, touch pads 5, 9, 13", "L, R", "S", "pads", False), ("Tap Shift", "R", "S", "top bar, right", False),
             ("Drag Note", "R", "Y", "strip 1", True), ("Tap Shift off, tap the display", "R, L", "S", "top bar, display", False)]),
      [("touches", "7", "warn"), ("risk", "line flattened to one note", "bad")],
      ["<b>Verified:</b> the Note strip is absolute. Every selected step's first note moves <i>to</i> the dragged pitch: A2 / C3 / E3 / G3 dragged to A3 became A3 / A3 / A3 / A3.",
       "With a scale on, transpose moves by scale degrees and can drop notes (C C♯ E in A minor became D F).",
       "On an empty step Note shows “C4” and does nothing.", "A key used to change a note overwrites the step's edited velocity."],
      ["Note moves every selected step by the same interval and reads “+2”; one step shows its root and chord notes."])}
{shot("part-flatten", "Four steps selected, Note dragged to A3: every step is now A3. With Shift latched, Undo reads Redo.")}
{task("3-2-t6", "Let the last note ring for a bar; accent the downbeats", "Seq, a bassline.",
      steps([("Long-press the pad", "L / R", "Y", "pads", False), ("Drag Length (or Velocity)", "R", "Y", "strip", False), ("Tap the display", "L", "Y", "display", False)]),
      [("touches per edit", "3", "ok"), ("visible afterwards", "no", "bad"), ("multi-select", "one value for all", "warn")],
      ["A 16-step note looks like a 1-step one, and the steps it rings over look empty.", "Several steps get one absolute value, so accents flatten."],
      ["Tails across the following pads and cells; velocity as fill height; multi-select strips move values relatively."])}
{shot("part-long-note", "A note four steps long: no tail is drawn.")}

<h3 id="s3-2-more">Recording, motion, length</h3>
{task("3-2-t7", "Jam a lead in A minor over the loop and keep it", "Seq, slot 3, stopped.",
      steps([("Shift; drag Key to A and Scale to Minor; Shift off", "R", "S", "top bar, strips 3–4", True), ("Tap the Synth tab", "L", "S", "top bar, left", False),
             ("Tap Play, then Record (short tap)", "R", "S", "top bar, right", False), ("Play", "both", "Y", "keys", False),
             ("Tap Record off; tap the Seq tab to see it", "R, L", "S", "top bar", False)]),
      [("touches", "10–11", "warn"), ("screen switches", "2", "bad"), ("stretches", "7", "bad")],
      ["You record blind (Synth shows no steps); on Seq the keys only appear with a step selected and then overwrite it.",
       "Held length is dropped (every note gets the pattern gate); a second pass stacks notes into accidental chords; no count-in."],
      ["Arming Record on Seq brings up the keys; record held length; Shift + Record = replace."])}
{task("3-2-t8", "Open the filter over the bar and have it repeat", "Synth screen, slot 2, Filter section, playing.",
      steps([("Hold Record", "R", "S", "top bar, right", False), ("At the same time drag Cutoff", "R2 / L across", "N", "strip 1", False),
             ("Release Record; check on Seq (coral dots on pads)", "R, L", "S", "top bar", False)]),
      [("touches", "3–4", "ok"), ("grip change", "1", "bad")],
      ["Holding Record and moving a strip needs two right-hand fingers.", "Motion edits only the latest lane; you can't add a value to a step without one; no lane delete."],
      ["Transport in reach (P2/P3); motion drawn as a curve on the overview row; a lane picker on the Motion strip."])}
{task("3-2-t9", "Triplet feel on the lead", "Seq, nothing selected.",
      steps([("Drag Rate to 1/16T", "R", "Y", "strip 4", False), ("Drag Length to 12", "R", "Y", "strip 2", False)]),
      [("touches", "2", "ok")],
      ["Pads still group by 4, so at 1/16T the grid lies.", "At 1/32 one bar is two pages and the page doesn't follow."],
      ["Beat edges follow the rate (every 3 at 1/16T); switching to triplets offers Length 12."])}
{shot("part-triplet", "1/16T with length 12: the pads still group in fours.")}

<h3 id="s3-2-views">Proposed views</h3>
<ol class="proposals">
<li><b>Note names on lit pads.</b> A small “A2” label, or “C·3” for a three-note chord. The cheapest win.</li>
<li><b>A pitch roll in the display</b> (left-thumb zone): x = step, y = pitch, bars as long as each note, stacked bars for chords, the playhead. Tap a note to select its step, so reading and selecting become one safe tap. Drums keep the sound grid.</li>
<li><b>Chord readout.</b> The footer sorts low to high and names simple chords (“C maj · C4 E4 G4”); with a step selected the keys light its notes.</li>
<li><b>Note names in the overview cells</b>, as a fallback if the roll is too big a change.</li>
<li><b>Read without risk.</b> Selecting a step doesn't arm the keys until a key is deliberately pressed; a long-press that drifts cancels instead of toggling.</li>
</ol>
{gaps(["No copy of a pattern, page or step.", "Clear also resets Length, Rate, Gate and Shuffle.", "Undo is lost on restart and doesn't cover key, scale or tempo."])}
</section>'''


def page():
    import manual_more, screens
    sec0 = screens.section(E)
    screens_css = screens.CSS.replace('{{', '{').replace('}}', '}')
    sec32b, sec8 = manual_more.build(task, steps, gaps, E)
    sec32 = SEC32.replace('<h3 id="s3-2-views">', sec32b + '\n<h3 id="s3-2-views">')
    findings = "".join(
        f'<li class="f-{lvl}"><span class="sev">{ {"high": "High", "med": "Medium", "low": "Low"}[lvl] }</span>'
        f'<span class="where">{E(where)}</span><p>{E(what)}</p><p class="fix">{E(fix)}</p></li>'
        for lvl, where, what, fix in TOP_FINDINGS)
    return f'''<title>Groovebox Manual</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=Space+Grotesk:wght@500;700&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>
/* Layout: a working manual. Sticky contents rail on wide screens, one reading column, task cards with step tables.
   Palette and faces follow the app itself (docs/adr/0002, ui/theme): ground, panel, cream text, the four strip colours. */
:root {{
  --bg:#F5F3EE; --panel:#FFFFFF; --sunk:#ECE9E2; --fg:#1B1B1E; --dim:#5F5D58; --line:#DAD6CD;
  --coral:#D9442F; --amber:#A86F00; --green:#17885A; --blue:#2F63D6;
  --coral-bg:#FBE6E2; --amber-bg:#FBF0D6; --green-bg:#E0F3EA; --blue-bg:#E4ECFB;
  --display:"Space Grotesk", "Helvetica Neue", Arial, sans-serif;
  --body:"IBM Plex Sans", "Helvetica Neue", Arial, sans-serif;
  --mono:"IBM Plex Mono", ui-monospace, Menlo, monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg:#121214; --panel:#1C1C20; --sunk:#16161A; --fg:#ECE9E2; --dim:#A9A69F; --line:#2E2E34;
  --coral:#FF6B57; --amber:#FFC23D; --green:#3DD68C; --blue:#6E9BFF;
  --coral-bg:#3A1F1B; --amber-bg:#352B13; --green-bg:#16301F; --blue-bg:#1B2540; color-scheme:dark; }} }}
:root[data-theme="dark"] {{
  --bg:#121214; --panel:#1C1C20; --sunk:#16161A; --fg:#ECE9E2; --dim:#A9A69F; --line:#2E2E34;
  --coral:#FF6B57; --amber:#FFC23D; --green:#3DD68C; --blue:#6E9BFF;
  --coral-bg:#3A1F1B; --amber-bg:#352B13; --green-bg:#16301F; --blue-bg:#1B2540; color-scheme:dark; }}
* {{ box-sizing:border-box }}
body {{ background:var(--bg); color:var(--fg); font:15px/1.55 var(--body);
  padding-left:max(16px, env(safe-area-inset-left, 0px)); padding-right:max(16px, env(safe-area-inset-right, 0px)); }}
.wrap {{ max-width:1180px; margin:0 auto; display:grid; grid-template-columns:220px minmax(0,1fr); gap:40px; padding-block:32px 80px }}
nav.toc {{ position:sticky; top:calc(env(safe-area-inset-top, 0px) + 16px); align-self:start; font-size:13px }}
nav.toc a {{ display:block; color:var(--dim); text-decoration:none; padding:3px 0 3px 10px; border-left:2px solid var(--line) }}
nav.toc a:hover, nav.toc a:focus-visible {{ color:var(--fg); border-left-color:var(--coral) }}
nav.toc .sub {{ padding-left:22px; font-size:12px }}
nav.toc p {{ font:500 11px var(--mono); letter-spacing:.08em; text-transform:uppercase; color:var(--dim); margin:18px 0 6px }}
main {{ min-width:0 }}
h1 {{ font:700 40px/1.05 var(--display); margin:0 0 6px; text-wrap:balance }}
h2 {{ font:700 28px/1.15 var(--display); margin:56px 0 10px; padding-top:18px; border-top:1px solid var(--line); text-wrap:balance; display:flex; gap:12px; align-items:baseline }}
h2 .num {{ font:500 15px var(--mono); color:var(--coral) }}
h3 {{ font:700 20px/1.25 var(--display); margin:36px 0 8px; text-wrap:balance }}
h4 {{ font:500 17px/1.3 var(--display); margin:0; text-wrap:balance }}
h5 {{ font:500 11px var(--mono); letter-spacing:.08em; text-transform:uppercase; margin:0 0 6px; color:var(--dim) }}
p {{ max-width:70ch }}
.lede {{ font-size:16px; color:var(--dim) }}
code {{ font:13px var(--mono); background:var(--sunk); padding:1px 5px; border-radius:4px }}
.meta {{ font:13px var(--mono); color:var(--dim) }}
.intro {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(260px,1fr)); gap:16px; margin-top:20px }}
.card {{ background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:16px 18px }}
.legend {{ display:flex; flex-wrap:wrap; gap:8px 14px; font-size:13px; align-items:center }}
.thumb {{ display:inline-block; min-width:22px; text-align:center; font:500 12px var(--mono); padding:1px 6px; border-radius:6px; background:var(--sunk) }}
.thumb.tl {{ background:var(--blue-bg); color:var(--blue) }} .thumb.tr {{ background:var(--green-bg); color:var(--green) }}
.zone {{ display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:7px; vertical-align:1px }}
.zone.in {{ background:var(--green) }} .zone.st {{ background:var(--amber) }} .zone.no {{ background:var(--coral) }}
.shift {{ font:500 11px var(--mono); padding:1px 6px; border-radius:6px; background:var(--amber-bg); color:var(--amber) }}
ol.findings {{ list-style:none; padding:0; display:grid; gap:10px; margin:16px 0 0 }}
ol.findings li {{ background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:12px 16px; display:grid; grid-template-columns:auto auto minmax(0,1fr); gap:4px 12px; align-items:baseline }}
ol.findings p {{ grid-column:3; margin:0; max-width:none }} ol.findings .fix {{ color:var(--dim); font-size:14px }}
ol.findings .fix::before {{ content:"→ "; color:var(--green) }}
.sev {{ font:500 11px var(--mono); text-transform:uppercase; letter-spacing:.06em; padding:2px 7px; border-radius:6px; grid-row:span 2 }}
.f-high .sev {{ background:var(--coral-bg); color:var(--coral) }} .f-med .sev {{ background:var(--amber-bg); color:var(--amber) }} .f-low .sev {{ background:var(--sunk); color:var(--dim) }}
.where {{ font:12px var(--mono); color:var(--dim); grid-row:span 2 }}
.task {{ background:var(--panel); border:1px solid var(--line); border-radius:14px; padding:18px 20px; margin:18px 0; display:grid; gap:12px }}
.task header {{ display:flex; gap:12px; align-items:baseline; flex-wrap:wrap }}
.tid {{ font:500 12px var(--mono); color:var(--coral); white-space:nowrap }}
.start {{ margin:0; color:var(--dim); font-size:14px }} .start .lbl {{ font:500 11px var(--mono); text-transform:uppercase; letter-spacing:.08em; margin-right:8px; color:var(--fg) }}
.how {{ margin:0 }}
.tbl {{ overflow-x:auto }}
table {{ border-collapse:collapse; width:100%; font-size:14px }}
th {{ text-align:left; font:500 11px var(--mono); text-transform:uppercase; letter-spacing:.06em; color:var(--dim); padding:6px 10px; border-bottom:1px solid var(--line) }}
td {{ padding:7px 10px; border-bottom:1px solid var(--line); vertical-align:top }}
td.n {{ font:12px var(--mono); color:var(--dim); width:28px }}
table.steps td:nth-child(3), table.steps td:nth-child(5) {{ white-space:nowrap }}
caption {{ text-align:left; font:500 13px var(--display); padding:0 0 6px }}
.params-grid {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(320px,1fr)); gap:18px 24px; margin:14px 0 }}
table.params td:first-child {{ font-weight:600; white-space:nowrap }}
ul.score {{ list-style:none; padding:0; margin:0; display:flex; flex-wrap:wrap; gap:6px }}
ul.score li {{ font-size:13px; padding:3px 9px; border-radius:999px; background:var(--sunk) }}
ul.score li b {{ font:500 13px var(--mono) }}
ul.score li.ok b {{ color:var(--green) }} ul.score li.warn b {{ color:var(--amber) }} ul.score li.bad b {{ color:var(--coral) }}
.fi {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(260px,1fr)); gap:12px }}
.fi > div {{ border-radius:10px; padding:10px 14px }}
.friction {{ background:var(--coral-bg) }} .ideal {{ background:var(--green-bg) }}
.fi ul {{ margin:0; padding-left:18px; font-size:14px }} .fi li {{ margin:3px 0 }}
.gaps {{ border:1px dashed var(--line); border-radius:10px; padding:10px 14px; margin:18px 0 }}
.gaps ul {{ margin:0; padding-left:18px; font-size:14px }}
figure.shot {{ margin:18px 0; background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:10px }}
figure.shot img {{ display:block; width:100%; height:auto; border-radius:8px; max-width:100% }}
figcaption {{ font-size:13px; color:var(--dim); padding:8px 4px 2px }}
ol.proposals li {{ margin:6px 0; max-width:75ch }}
.todo {{ color:var(--dim) }}
{screens_css}
.np {{ font:500 11px var(--mono); text-transform:uppercase; letter-spacing:.06em; padding:2px 7px; border-radius:6px; background:var(--coral-bg); color:var(--coral); margin-right:6px }}
.note {{ border-left:3px solid var(--amber); padding:4px 12px; background:var(--amber-bg); border-radius:0 8px 8px 0 }}
table.ideas td {{ font-size:13.5px }} table.ideas td.c {{ white-space:nowrap }} .sub2 {{ color:var(--dim); font-size:12.5px; margin-top:2px }}
.pill {{ font:500 11px var(--mono); padding:2px 7px; border-radius:6px; background:var(--sunk) }}
.pill.v-high {{ background:var(--green-bg); color:var(--green) }} .pill.v-med {{ background:var(--amber-bg); color:var(--amber) }} .pill.v-low {{ color:var(--dim) }}
a {{ color:var(--blue) }}
:focus-visible {{ outline:2px solid var(--blue); outline-offset:2px }}
@media (max-width:900px) {{ .wrap {{ grid-template-columns:minmax(0,1fr); gap:8px }} nav.toc {{ position:static }} h1 {{ font-size:32px }}
  ol.findings li {{ grid-template-columns:minmax(0,1fr) }} ol.findings p, .sev, .where {{ grid-column:1; grid-row:auto; justify-self:start }} }}
</style>
<div class="wrap">
<nav class="toc" aria-label="Contents">
  <p>Manual · draft</p>
  <a href="#method">How to read this</a><a href="#findings">Top findings</a><a href="#s0">0 Screen map</a><a class="sub" href="#glossary">Glossary</a>
  <p>Sections</p>
  <a href="#s1">1 Synth sounds</a><a class="sub" href="#s1-1">1.1 Pick a sound</a><a class="sub" href="#s1-2">1.2 Shape it</a><a class="sub" href="#s1-3">1.3 Play it</a><a class="sub" href="#s1-4">1.4 Keep it</a>
  <a href="#s2">2 Drum kit</a><a class="sub" href="#s2-1">2.1 Play the kit</a><a class="sub" href="#s2-2">2.2 Edit one sound</a>
  <a href="#s3-1">3.1 A drum beat</a>
  <a href="#s3-2">3.2 A synth part</a><a class="sub" href="#s3-2-view">Viewing notes and chords</a><a class="sub" href="#s3-2-enter">Entering</a><a class="sub" href="#s3-2-edit">Editing</a><a class="sub" href="#s3-2-more">Recording, motion, length</a><a class="sub" href="#s3-2-tricks">Fast routes</a><a class="sub" href="#s3-2-bass">Bassline</a><a class="sub" href="#s3-2-chords">Chords</a><a class="sub" href="#s3-2-melody">Melody</a><a class="sub" href="#s3-2-arrange">Editing and arranging</a><a class="sub" href="#s3-2-feel">Feel</a><a class="sub" href="#s3-2-perform">Performance</a><a class="sub" href="#s3-2-views">Proposed views</a>
  <a href="#s8">8 Ideas from other grooveboxes</a><a class="sub" href="#s8-double">Double my beat</a><a class="sub" href="#s8-quantise">Quantise my beat</a><a class="sub" href="#s8-questions">Open questions</a>
  <a href="#later">Not written yet</a>
</nav>
<main>
<h1>Groovebox Manual</h1>
<p class="meta">Draft for CX research · build-20 · 17 Oct 2026 · written from the code and offscreen renders, not yet checked on the S25+</p>
<p class="lede">Each task is written as it works today, scored, and given an ideal flow. If a task is awkward to write down, it is awkward to do. No app changes until the findings are reviewed.</p>

<div class="intro" id="method">
  <div class="card"><h5>Steps</h5><p>One row per touch: the action, which thumb, where on the screen, and whether Shift is on.</p>
  <div class="legend"><span><span class="thumb tl">L</span> left thumb</span><span><span class="thumb tr">R</span> right thumb</span><span><span class="shift">Shift</span> second layer</span></div></div>
  <div class="card"><h5>Reach (ADR 0003)</h5><p>Left thumb: left 40% of the screen, lower 75%. Right thumb: right 40%, lower 75%. The top bar's ends are a stretch, its centre is out of reach.</p>
  <div class="legend"><span><span class="zone in"></span>in reach</span><span><span class="zone st"></span>stretch</span><span><span class="zone no"></span>out of reach</span></div></div>
  <div class="card"><h5>Score</h5><p>Touches, screen switches, reaches, Shift uses, hidden gestures (hold, double-tap, swipe), and anything that can be lost.</p>
  <ul class="score"><li class="ok"><b>ok</b> fine</li><li class="warn"><b>warn</b> costly</li><li class="bad"><b>bad</b> fix</li></ul></div>
</div>

<section id="findings"><h2><span class="num">★</span>Top findings so far</h2>
<p>Ranked across the sections below; they merge with the <a href="https://github.com/HutchNBoots/PYHN/blob/ccr-8d023090-x5f91s/docs/reviews/2026-10-17-consistency-review.md">consistency review</a> into one list before any build is planned.</p>
<ol class="findings">{findings}</ol></section>
{sec0}{SEC1}{SEC2}{SEC31}{sec32}{sec8}
<section id="later"><h2><span class="num">…</span>Not written yet</h2>
<ul class="todo"><li>0 Getting started: the grip, Shift, Play and Record, gestures (the screen map is done)</li><li>3.3 Patterns and 3.4 Putting parts together</li>
<li>4 Tape (paused at the owner's request)</li><li>5 Mix and effects · 6 Performing live · 7 Session · Appendix</li></ul></section>
</main></div>'''


OUT.write_text(page(), encoding="utf-8")
print("wrote", OUT, OUT.stat().st_size // 1024, "KB")
