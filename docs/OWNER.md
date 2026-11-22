# Working with Tae (read before any work)

This is a model of the owner, Tae. It is built from his own words across Runs 4–9. It exists so he never has to say
the same thing twice. Read it with `CLAUDE.md` before you plan, design or ship anything. When he asks for something
new, or repeats something, add it here in the same PR.

## Who he is and how he works
- He rides two commutes on a Samsung phone (a **Galaxy A17**, a budget phone with little memory) and sleeps on the bus.
  Anything heavy, like the Gemma model, must be proven on that phone before a trip relies on it. "To work" is two buses and "From work" is three.
  He usually has earbuds in, but he forgets them.
- He works from his phone only. He sends `/goal` lines, reads the PR on his phone, installs the `debug-latest` APK
  and tests on real buses. He has no laptop and won't debug for you.
- His standing order is: **"Do all. Merge them. Don't bother me."** Finish the run, merge it when CI is green, and
  report in a few lines. Don't ask questions you can answer from the code, the docs or sensible defaults.
- He writes fast, with typos ("buss", "baiier" = barrier, "dtop" = stop). Read for intent and don't correct him.
- He gets frustrated when a problem comes back after he reported it. Each repeat costs trust. Say so plainly when
  something is the second or third report, and show what is different this time.

## What he cares about, in order
1. **The alarm must get through, every time.** He has reported this many times:
   - Run 4: "4 failed alarms today", including while he was using the phone.
   - Run 6: "the buzz isn't ringing, my phone is on Mute, can't you make it ignore that?" and "the alarm didn't
     go off when the bus entered my circle".
   - Run 10: "no buzz again, nor any screen appearing… make sure the buzz comes through, it's not working."

   Treat any change near the ring path as dangerous. Prove it with tests that run in CI, and make the app say
   after each trip whether it rang and buzzed. Never rely on him sending logs: he finds that friction, so the app
   must surface the evidence itself.
2. **Minimum friction, lots of defaults, and everything customizable.**
   - No typing: "never wants to type times", default names like "Stop 1", "Stop 2", and "Route 1".
   - Rename only if he wants to.
   - Open the map straight away to add a stop.
   - One or two stops and it's ready to go.
   - No dialogs that ask for something a default could answer.
   - Anything he does often must be one tap from where he already is. Example: "I'm off this bus" from the
     notification. Opening the app, then the route, then ending the trip is too much.
3. **Simple, like the most-used apps.**
   - He rejected v1's look ("I hate the colours, lack of simplicity and aesthetic").
   - He approved v2's black and white ("Looks bloody amazing").
   - He wants maps to look like **Google Maps**, and he dislikes the grey recoloured map.
   - Google Maps is his reference for maps, links and search.
4. **The map is the main surface while riding.** He likes how it frames him and his stop when it opens; keep that.
   He wants more map and less panel, and he wants to do things on the map directly, like dragging the circle or
   tapping it.
5. **Quiet, but not deaf.** Silence other notifications only while the screen is off (he's dozing). When he's using
   the phone, notifications come through normally.
6. **Finding stops is hard.** He wants to paste Google Maps links (places and routes), share from Google Maps, and
   search stops or routes in the app.

## The ring problem so far (read before touching the alarm)
- **Run 4:** the screen turns on; a heads-up while he's using the phone; re-ring every minute until "I'm awake".
- **Run 6:** Buzz on Mute, the speaker after 30 s if the buzz may not get through, and the circle rings on entry.
- **Run 10:**
  - Found and closed silent failures:
    - nothing resumed a trip after an update or a reboot;
    - the alarm channel couldn't get through Do Not Disturb, because it was made before access was granted;
    - quiet trip stayed on during the ring;
    - nothing showed if the ring service failed to start;
    - a failing earbuds tone could stop the buzz;
    - a ring that cold-started the app wasn't logged.
  - Added "Over other apps" for the alarm screen while he's using the phone.
  - Added `RingPathTest` in CI, and a "Last trip" verdict on Now.
- If he reports a missed ring again: read Now's "Last trip" line and the `ringd` report in his log (he can tap
  Send), then add the case to `RingPathTest` before fixing it.

## Round 4 (2026-11-22): what he said after the couch test
- The ring, Mute, Do Not Disturb and earbuds worked. He tests only what he cares about ("not bothered" with
  simulated trips and kill tests), so CI must cover the rest.
- "No dot" on the circle: direct manipulation of the thing itself, never an extra handle.
- Sharing a stop from Google Maps was "too much work". He wants a whole trip (all stops, up to 3 buses) from one
  search or one paste. Prefer one action that does everything over several small ones.
- The alarm must open full screen even while he's using the phone, not as a banner.

## Things he has decided; don't change them unless he asks
- **App icon:** "I don't like it. Don't change it. I'll tell you when you should address it again." Leave the icon
  alone, and don't propose icon work.
- v2 visual language: black and white, the system font, and one orange accent meaning "trip is live". The map is
  the exception: it should look like Google Maps.
- One ring path, the dead-man's switch, and "ring earlier, never later" (`CLAUDE.md`). He has never asked to relax
  these.
- Personal stops and ride logs never go in the repo.

## What he'll probably say next (pre-empt these)
- "Why do I have to open the app for that?" Anything shown in a notification should have its action there too.
- "Too many steps." Count taps for every flow you touch; the common path should be 1–2 taps.
- "Why is it asking me that?" Prefer a default plus a way to change it over a question.
- "It still doesn't buzz." Any ring change must come with CI tests for Mute, Do Not Disturb, quiet trip, a cold
  start and no earbuds, plus the after-trip verdict.
- "Did an update break it?" Updates and reboots must never lose a running trip or its alarm. Test both.
- "The map looks bad or doesn't look like Google." Judge any map work against Google Maps on Android.
- "I can't find my stop." Every place you set a stop should accept a Google Maps link, a search and a tap on the
  map.
- Text-heavy screens: he skims. Keep one line per idea, with no jargon such as "failsafe", "leg" or "DND" on screen.

## How to report back to him
- A few short lines: what changed, what to test on the phone, and the APK link. No walls of text.
- If something he asked for wasn't done, or was done differently, say so in one line with the reason.
