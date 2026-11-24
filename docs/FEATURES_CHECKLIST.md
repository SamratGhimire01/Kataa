# Kataa feature checklist (owner's asks, 23-24 Nov 2026)

Every item must work in the core (test or simulation proves it) AND be reachable from the app (voice or screen).

## Understanding and speed
1. Rules first; on-device AI only when rules are unsure; every AI call has a 1.5 s budget, then rules answer.
2. Alarms: "wake me up at 5", "set an alarm for 7".
3. Agenda: "what do I have today/tomorrow", "what are my reminders" (includes today's to-do list).
4. Fillers ignored ("uh", "ugh", "stupid", "bro"); "remind to X" without "me".
5. Two requests in one sentence are split by rules.
6. Ambiguous times ask: "call Didi at 7:13" -> AM/PM + today/tomorrow; "after 3 days at 7" keeps the day, asks AM/PM; clear hints (wake, dinner, pm) don't ask. Card with buttons in the app.
7. Unknown fact ("what's my mom's birthday?") -> "When is it?" with a date wheel; the answer is saved; birthdays/anniversaries get a yearly reminder.
8. "Show me everything": timeline by date with a date wheel to jump.

## Passwords
9. All wordings save as a password ("write the password of wifi X", "save my instagram password X", "password for gmail is X", "netflix password is X"); "change my X password to Y" replaces.
10. Spoken passwords are confirmed before saving (card with editable value and app/service, Save / Don't save); typed ones save at once.
11. Never spoken aloud; masked in every list until fingerprint/PIN; vault "show me all my passwords" after unlock.
12. Copy button after unlock (sensitive clip, cleared after 30 s). Edit button on memories (service field for passwords).

## Idea mode, journal, lists
13. "I've got an idea" -> long listening (pauses don't stop it), saved under Ideas.
14. Mic setting: tap auto-stop / tap to start + tap to stop / hold to talk; long press always holds.
15. Journal: "today's journal (about me)" -> long listening, saved on that day; quick summary at once, AI summary in the background while charging; "show my journal for yesterday", "what did I do on Monday"; journal screen with day arrows and date wheel.
16. To-do lists: dictate ("write my to-do list"), inline ("tomorrow's to-do list: X and Y"), day/date/month lists ("November 19th to-do list", "this month's goals"), named lists ("shopping list"), add/remove by voice.
17. "How many tasks are done/left", tick/untick by voice ("mark X done", "tick number 2") and by tick boxes on screen.
18. Cost and time per task ("buy milk 100 rupees", "study 2 hours"); "cost of the least task" / cheapest / quickest / total cost / total time.
19. Unfinished tasks carry to today's list (marked "from <day>").

## Location
20. Glass map button bottom-right on home; map to pin a place with a name and radius (lat/long); My places (move/delete); "where is X?" offers "Pin on map"; reminders for a pinned name fire there (pinned names beat brands). Internet only for map tiles.

## Voice
21. Online Google recognizer when there is internet, Kataa's own offline recognizer otherwise; best of 5 guesses.

## Data
22. Lists and journal are in the encrypted backup; schema change via Migrations (v2).
