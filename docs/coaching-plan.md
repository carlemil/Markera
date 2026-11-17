# Coaching plan: help the shooter understand low scores and one-sided groups

Goal: when the saved series show a problem the numbers can actually support, Markera says what
it sees, what usually causes it and what to try. It never guesses from too little data and never nags.

## What the data can and cannot say

A series is five holes with positions in target mm (`PlottedHit`), a caliber, a tag and a time.
There is no shot order inside a series and no record of the gun, the sights or the shooter's hand.

- Five holes are too few to judge a shape. One series only supports "this was below your usual".
- A day (three or more series) or a filtered selection (15+ hits) supports group findings.
- The same offset on several separate days supports a sight finding; an offset on one day does not.
- The caliber stands in for the gun, so every finding is worked out per caliber.

## The findings

Each is a typed result from pure maths, with the numbers behind it and a confidence.

| Finding | What the maths sees | What the app says |
|---|---|---|
| Offset group | Mean point of impact off centre by more than twice its standard error, and at least 10 mm | Direction and distance. Same on 3+ days: adjust the sights. Varies by day: technique, by direction |
| Stretched group | Covariance ellipse at least 1.5 times longer than wide | Vertical: breathing, sight height, grip pressure. Horizontal: trigger pull, wrist. Diagonal: by hand |
| Fliers | A tight core plus holes far outside it | One shot per series gets away: trigger control, follow-through, calling the shot |
| Wide round group | Large spread, no direction, no offset | Hold and basics: stance, grip, dry fire |
| Low series or day | Total well under the shooter's own average for that caliber (1.5 SD, last 30 series) | Says so, and shows which finding, if any, explains it |
| Falling off | Totals drop from the first to the last series of a day, on several days | Fatigue: shorter sessions, pauses |
| Getting worse or better | The existing trend fit's slope over weeks | One line, both directions; improvement is said too |

Direction advice for technique depends on the shooting hand (low-left for a right-hander is
low-right for a left-hander), so the app needs that one setting. Advice text says "often caused by",
names two or three causes at most and one drill; it is a hint, not a verdict.

## When the app speaks

1. **Statistics, new Insights tab** (sv: Analys): the main place. Findings for the current filter,
   the ellipse and an offset arrow drawn on the target, the numbers, the advice. Always available.
2. **Home, one card**: only the strongest recent finding (last 7 days), with a link to Insights.
   Dismissed findings stay away until the data changes.
3. **Serie page**: one line comparing the series with the shooter's average, plus the day's
   finding when the day has one. No shape claims from five holes.
4. **Sight helper**: for a stable offset, the correction in mm at the target and, when the user has
   entered distance and click value, in clicks.
   Built in the Insights card of a stable offset (`series/stats/SightHelper.kt`, pure and tested):
   up/down and left/right in whole mm, the rear sight moved the way the impact should go, and with
   a per-caliber click value (shooting distance, default 25 m, and mm per click at it) the clicks,
   rounded half away from zero from the unrounded mm (under half a click: leave that axis). "I
   adjusted the sights" stores the moment per caliber; from then on the offset finding and the
   target's arrow use only series taken after it (Undo forgets it). The other findings keep the
   whole history, and the Serie page's own-day line is not cut. The setups are one JSON value in
   the settings store (`readSightSetups`), a device preference never sent to the server.

Nothing interrupts scanning or saving. Settings has one switch to turn insights off.

## Build order

1. Group-shape maths (pure, tested)
2. Findings engine with thresholds, tuned on real stored series
3. Shooter profile and the off switch in Settings
4. Advice texts, English and Swedish, mirrored by hand
5. Insights tab in Statistics
6. Home card
7. Serie page line
8. Sight helper
9. Session fatigue finding

1 to 5 are the first useful release. 6 to 9 each stand alone after that.

## Risks

- Wrong advice is worse than none: thresholds start strict and are checked against the owner's own
  history before anything ships.
- Detected hole positions carry error near ring edges; findings use many hits, so it averages out,
  but hand-placed holes count the same as detected ones.
- Mixed guns under one caliber blur the offset finding. Tags can separate them; a gun field is a
  later question.
