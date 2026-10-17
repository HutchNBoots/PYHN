"""Section 0: a labelled map of every screen. Imported by build_manual.py.

Marker positions are pixels in the 1170 × 540 screenshots in img/ (converted to % here).
"""

W, H = 1170, 540

TOP_BAR = [
    ("A", 200, 42, "Screen tabs", "Synth · Drum · Seq · Tape · Mixer. The current screen is cream."),
    ("B", 600, 42, "Slot buttons 1–8", "Slot 1 = drum kit, 2–8 = synths. The selected slot is coral. Long-press 2–8 for presets. Not shown on Tape."),
    ("C", 868, 42, "Tempo readout", "BPM; read-only here (set it on Seq · Shift)."),
    ("D", 968, 42, "Play / Stop", "Green triangle; a green square while playing."),
    ("E", 1035, 42, "Record", "Tap to arm; hold + move a strip records motion."),
    ("F", 1112, 42, "Shift", "Tap to latch the second layer, hold for a momentary one. Amber when on."),
]

SCREENS = [
    ("synth", "synth-main", "Synth", "One synth slot (2–8): pick a section on the rail, edit it on the strips, play the keys.", [
        ("1", 52, 215, "Section rail", "Osc · Filter · Env · Mod. Picks what the strips edit."),
        ("2", 165, 116, "Display title and subtitle", "Engine name (Brook), then preset · slot · voice mode, and the key when a scale is on."),
        ("3", 683, 118, "Layer label", "Which section and layer the strips show (MAIN / SHIFT)."),
        ("4", 430, 215, "Sound picture", "Oscillator, filter curve and envelope, drawn live; the current section is bright."),
        ("5", 400, 321, "Legend", "The four strips' names and values, in strip colours."),
        ("6", 965, 240, "Colour strips 1–4", "Drag up or down to edit. Double-tap resets, a still hold locks."),
        ("7", 823, 128, "Strip label and value", "The value sits under the label, above your thumb."),
        ("8", 585, 445, "Keys", "Two octaves (C3–B4): sharps on the top row, naturals below. Where you hit sets the velocity."),
        ("9", 62, 514, "Key labels", "C3 and C4 mark the octaves."),
    ]),
    ("drum", "drum-keys", "Drum", "The kit (slot 1): play a sound to select it, then shape it on the strips.", [
        ("1", 145, 117, "Display title and subtitle", "“Kit”, then the selected sound · key number · slot."),
        ("2", 605, 118, "Layer label", "MAIN: pitch / decay / tone / level; SHIFT: pan / start / rev / choke."),
        ("3", 330, 215, "Sound picture", "The selected sound's decay shape."),
        ("4", 705, 215, "Kit map", "4 × 4 picture of the 16 sounds; the selected one is amber. Not touchable (review item R1)."),
        ("5", 360, 321, "Legend", "The four strips' names and values."),
        ("6", 965, 240, "Colour strips 1–4", "Edit the selected sound."),
        ("7", 300, 470, "Kit keys", "The lowest 16 keys, one sound each. Playing one selects it."),
        ("8", 985, 470, "Unused keys", "E4–B4 play nothing on the drum slot (dimmed)."),
        ("9", 63, 512, "Key labels", "Short sound names (currently cut from the real names: DK, CS, Cras…)."),
    ]),
    ("seq-drum", "beat-lane-hat", "Seq · drum slot", "Writing a beat one sound at a time.", [
        ("1", 52, 190, "Page rail", "Pages 1–4 = steps 1–16, 17–32, 33–48, 49–64."),
        ("2", 52, 323, "Clear", "Tap twice to clear the pattern. Shift + Clear clears one sound."),
        ("3", 300, 117, "Display title and subtitle", "Pattern number, then slot · instrument · rate · length."),
        ("4", 690, 118, "Layer label", "MAIN · pattern, STEP · step n, or SHIFT."),
        ("5", 230, 210, "Sound grid", "The 16 kit sounds. Tap one to make it the lane (you hear it). A dot = used in the pattern."),
        ("6", 560, 195, "Pattern overview", "4 rows = 4 pages × 16 steps; the playhead moves along it. View only."),
        ("7", 152, 315, "Undo", "Undo the last sequencer edit (Shift = Redo)."),
        ("8", 370, 315, "Footer status", "Pattern settings, or the selected step's contents."),
        ("9", 965, 240, "Colour strips 1–4", "No step selected: Shuffle, Length, Gate, Rate. Step selected: Velocity, Length, Chance, Nudge."),
        ("10", 580, 470, "Step pads 1–16", "Tap to add or remove the lane's sound; long-press to select (amber outline)."),
        ("11", 478, 395, "Lane hit (bright pad)", "This step plays the lane's sound."),
        ("12", 335, 449, "Other-sound dot", "Only other sounds play here."),
    ]),
    ("seq-synth", "part-step-keys", "Seq · synth slot, step selected", "Editing one step of a synth part: the keys slide in under the pads.", [
        ("1", 410, 151, "Pattern overview", "Lit cells = steps that play. No pitch shown."),
        ("2", 191, 393, "Selected step", "Amber outline; stays selected after you let go."),
        ("3", 246, 316, "Footer: step contents", "Step number and its notes (e.g. “step 3 · A2”)."),
        ("4", 965, 135, "Step strips", "Velocity · Length · Chance · Nudge (Shift: Note · — · — · Motion)."),
        ("5", 585, 485, "Keys", "Play a key to set the selected step's note; hold several for a chord."),
        ("6", 760, 393, "Step pads (shortened)", "Pads shrink to make room for the keys while a step is selected."),
    ]),
    ("tape", "tape-main", "Tape", "Four tracks of audio, looping with the sequencer.", [
        ("1", 455, 42, "Undo (top bar)", "Undo the last tape change; Shift = Redo. Replaces the slot buttons on this screen."),
        ("2", 588, 42, "Export WAV", "Renders the tape to a file."),
        ("3", 190, 115, "Display title and subtitle", "“Tape”, then length · loop · dub."),
        ("4", 410, 140, "Loop bracket", "The loop region (Start and Loop strips)."),
        ("5", 55, 225, "Lane names T1–T4", "One lane per track."),
        ("6", 400, 205, "Lanes", "Tap = select a track; double-tap = record onto it; hold = clear it."),
        ("7", 305, 255, "Tape head", "The playback position."),
        ("8", 350, 323, "Footer hint", "What the selected track is doing and what you can do next."),
        ("9", 965, 240, "Colour strips 1–4", "Speed, Start, Loop, Dub (Shift: Vol, Pan, Rev, Commit)."),
        ("10", 585, 445, "Keys", "Play the selected slot over the tape."),
    ]),
    ("mixer", "mixer-main", "Mixer", "Levels, pan and effect sends for every channel.", [
        ("1", 150, 115, "Display title and subtitle", "“Mixer”, then the selected channel."),
        ("2", 390, 220, "Level columns", "One per channel: live meter, white fader line, clip light on top. Tap to select; Shift + tap mutes."),
        ("3", 57, 145, "Column value", "The fader setting (dB or %); a coral M when muted."),
        ("4", 390, 297, "Column names", "1–8 slots, T1–T4 tape tracks, DLY, REV, MST."),
        ("5", 360, 322, "Legend", "The four strips' names and values."),
        ("6", 965, 240, "Colour strips 1–4", "Volume, Pan, Delay, Reverb for the selected channel."),
        ("7", 580, 470, "Channel pads", "Same 15 channels; tap to select, Shift + tap to mute."),
        ("8", 128, 395, "Selected pad", "Cream fill."),
    ]),
]

GLOSSARY = [
    ("Slot", "One of 8 instruments: slot 1 is the drum kit, 2–8 are synths."),
    ("Screen", "Synth, Drum, Seq, Tape or Mixer (the tabs)."),
    ("Display", "The big panel in the middle row of every screen."),
    ("Strips", "The four colour sliders on the right; what they edit depends on the screen, section and layer."),
    ("Layer", "MAIN or SHIFT: Shift swaps every strip to its second job."),
    ("Rail", "The column of buttons left of the display (Synth sections, Seq pages)."),
    ("Keys", "The two-octave keyboard (capsule pads)."),
    ("Pads", "The 16 step pads on Seq and the 15 channel pads on Mixer."),
    ("Pattern", "One of 16 sequences per slot, up to 64 steps."),
    ("Page", "16 steps of a pattern (pages 1–4)."),
    ("Step", "One position in a pattern; it can hold up to 8 notes or drum sounds (hits)."),
    ("Hit", "One note or drum sound on a step, with its own velocity, length, chance and nudge."),
    ("Lane", "On the drum slot: the one sound the step pads currently show and edit."),
    ("Motion", "A strip value recorded per step (up to 8 per pattern)."),
    ("Track", "One of the 4 tape tracks (T1–T4)."),
    ("Channel", "A Mixer column: a slot, a tape track, a return or the master."),
]


def section(E):
    def figure(fid, img, title, lede, marks):
        dots = "".join(
            f'<span class="mk" style="left:{x / W * 100:.2f}%;top:{y / H * 100:.2f}%">{E(n)}</span>'
            for n, x, y, _name, _d in marks)
        legend = "".join(f'<li><span class="mk-l">{E(n)}</span><b>{E(name)}</b> {E(d)}</li>' for n, _x, _y, name, d in marks)
        return f'''
<div class="screen" id="map-{fid}"><h3>{E(title)}</h3><p>{E(lede)}</p>
<div class="map-wrap"><div class="map"><img src="img/{img}.jpg" alt="{E(title)} screen with numbered areas" width="1170" height="540" loading="lazy">{dots}</div>
<ol class="map-legend">{legend}</ol></div></div>'''

    top = figure("top", "synth-main", "Top bar (every screen)", "The same row on every screen.", TOP_BAR)
    figs = "".join(figure(*s) for s in SCREENS)
    gl = "".join(f"<tr><td>{E(a)}</td><td>{E(b)}</td></tr>" for a, b in GLOSSARY)
    return f'''
<section id="s0"><h2><span class="num">0</span>Screen map</h2>
<p class="lede">What every area is called. Every screen has the same three rows: the top bar, the middle row (rail, display, four strips) and the bottom zone (keys or pads). Numbers match the list beside each picture; screenshots are offscreen renders of build-20.</p>
{top}{figs}
<h3 id="glossary">Glossary</h3><div class="tbl"><table class="params"><tbody>{gl}</tbody></table></div>
</section>'''


CSS = '''
.map-wrap {{ display:grid; grid-template-columns:minmax(0,3fr) minmax(0,2fr); gap:18px; align-items:start }}
.map {{ position:relative; border-radius:10px; overflow:hidden; border:1px solid var(--line) }}
.map img {{ display:block; width:100%; height:auto; max-width:100% }}
.mk {{ position:absolute; transform:translate(-50%,-50%); min-width:22px; height:22px; padding:0 5px; border-radius:11px;
  background:#FFC23D; color:#121214; font:600 12px/22px var(--mono); text-align:center; box-shadow:0 0 0 2px #121214 }}
.mk-l {{ display:inline-block; min-width:22px; height:20px; padding:0 4px; border-radius:10px; background:var(--amber-bg); color:var(--amber);
  font:600 11.5px/20px var(--mono); text-align:center; margin-right:8px }}
ol.map-legend {{ list-style:none; padding:0; margin:0; display:grid; gap:7px; font-size:13.5px }}
ol.map-legend b {{ font-weight:600 }}
.screen {{ margin:26px 0 34px }}
@media (max-width:900px) {{ .map-wrap {{ grid-template-columns:minmax(0,1fr) }} .mk {{ min-width:18px; height:18px; font-size:10px; line-height:18px }} }}
'''
