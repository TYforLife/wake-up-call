# How to work on Nodstop

This file covers how to do each kind of task. `CLAUDE.md` says what the code must never do, and it wins where
the two disagree. The ideas come from Lauren Tan's pstack (`cursor/plugins`, MIT) and her talk "All Code Is Slop
(Except Mine)". Only the parts that fit a one-person app, worked from a phone with CI as the only test machine,
are kept here.

## Catch a mistake as early as it can be caught
From cheapest to most expensive:
1. **The code's shape.** There's one way to do each thing, such as the one ring path or the pure engine. Wrong
   code is hard to write.
2. **Types and static checks.** Kotlin types, lint, `ArchitectureTest`, `PurityTest` and `tools/check_pr.py`.
3. **Tests.** Engine scenarios, property tests, `RingPathTest` and `ReplayTest`.
4. **Written rules.** This file and `CLAUDE.md`. Nothing fails when a session skips them, so keep them for
   judgement calls.
5. **Tae on a bus.** This is the last and most expensive place. Nothing that CI could have caught should reach him.

When something goes wrong, or Tae corrects you, fix it at the cheapest place that works. Use the next one only if
it can't be done there.

## Every task
- **Goal and finish line first.** Say the goal in one line, then what done means: a test, a command or a file
  that passes or fails. "Make it better" is not a finish line.
- **The smallest change that does it.** Remove what's dead or duplicated before adding anything. Don't add a guard,
  option or layer that nothing needs yet.
- **Name the data shape before you write logic.** Choose where the state lives (`:engine` for decisions, `:app`
  for OS adapters) and what holds it. If you're about to add another boolean that must stay in step with one
  already there, or another branch to an if/else chain, use a sealed type, a state machine or a table.
- **Experience first.** Count taps. A default beats a question, and one line per idea beats a paragraph
  (`docs/OWNER.md`).
- **Don't ask what the code, the docs or a sensible default can answer.** Make the choice if it can be undone,
  and list it under "Decisions" in the PR so Tae can overrule it.
- **Prove it works on the real thing.** A green build only proves the code compiles. The evidence is a named
  test that fails without your change, a command's output, or a screenshot from CI. What only a phone can show
  goes in "Test on phone". If you couldn't verify something, write "not verified" and give the reason.

## Bug fix (a missed ring is the worst kind)
1. **Reproduce it as a failing test first.** Start from Tae's words, a trace, or the Now screen's "Last trip"
   line and the `ringd` report. If it won't reproduce, build the trigger yourself with the trace generator or a
   `RingPathTest` case.
2. **Find the first wrong decision.** Keep asking "why" until the answer is a line of code that decided wrongly.
   Then ask whether the state it hit is legitimate:
   - If it is, handle it where the code broke.
   - If it should never happen, find what produced it. A check where it surfaced would only hide the bug.
   - No retry, delay, catch-all or clamp (`CLAUDE.md` "Code quality" 1).
3. **Fix the pattern, not only this case.** Search for the same fault elsewhere.
4. **Land the failing test first.** Commit the test, then the fix on top, when that's practical.
5. **Make it visible next time.** If finding the cause took a long dig, add the cheap signal that would have shown
   it at first look, such as a log line or a word in the "Last trip" line.

A fix PR must change a test. If no test can catch the bug, write `No test: <why, and what would catch it>` in
the PR body. `tools/check_pr.py` enforces this.

## A problem that comes back
Tae has reported it before (check `docs/OWNER.md`), so the earlier fixes all assumed something that wasn't true.
- Write that assumption down in one sentence in the PR.
- Test the assumption before writing another fix: find a fact that would come out differently if it were false.
- If it's false, fix what the failures have in common, not the latest symptom.
- In the reply, say which report this is (second, third…) and what's different this time.

## Feature
1. Read the code it touches and its `docs/DESIGN.md` section.
2. Name the data shape and where it lives, as in "Every task".
3. Build it in small steps that each end in a green check, and make one commit per step.
4. Delete what the feature made dead, in the same PR.
5. Update `docs/DESIGN.md` if behaviour or parameters changed.

## Tests
A test is a claim about what a user of the code can see. Judge each test against two imagined edits:
- **Break the behaviour.** The test must fail. It doesn't if it asserts loosely, or if its expected value was
  computed by the code under test.
- **Change only the internals.** The test must still pass.

Take expected values from DESIGN or from the real world (a timetable, a route Google would give), not from the
code. Pair every "this never happens" test with a case where it nearly does.

## Numbers
Before you report a number (planner time, alarm lead in seconds, battery use), say what limits it and how many
runs it comes from. One run is a guess.

## PR checks
`tools/check_pr.py` runs on every PR (`.github/workflows/pr.yml`). Each failure says what to do instead.

| Check | Fails when |
| --- | --- |
| Title | it isn't `type(scope): subject`, such as `fix(alarm): …` or `feat(map): …` |
| Test on phone | the body has no "Test on phone" section, or that section has no `debug-latest` APK link |
| A test with every fix | the title or a commit is `fix`, no test file changed, and the body has no `No test:` line |

## Reporting back
The PR follows the sections in `docs/PLAN.md`'s runbook, plus "Decisions" for the choices you made without
asking. The reply to Tae is a few short lines: what changed, the evidence, what to test on the phone, and the APK link.
