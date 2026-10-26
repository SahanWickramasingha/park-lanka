# AGENTS.md

## Project
AI Draw & Guess — OUSL Open Day 2026

This is an existing React + Vite browser game. Improve the current project in place.
Do NOT rebuild the application from scratch and do NOT redesign the desktop UI.

## Primary Task

Make the existing UI fully responsive for:
- Desktop
- Laptop
- Tablet
- Mobile
- Small mobile screens

Also add:
1. A Home button
2. A Back button
3. Correct click/navigation behavior for both

Preserve the existing visual identity, AI behavior, Supabase leaderboard, scoring, timer,
drawing canvas, wheel, model loading, and game logic.

---

# Non-Negotiable Rules

- Do not remove existing working features.
- Do not change the AI model files unless explicitly asked.
- Do not change Supabase credentials or database logic unless required to fix a bug.
- Do not re-add Monkey as a game challenge.
- Keep the active game challenges:
  - Car
  - Cat
  - Fish
  - House
  - Star
  - Lion
  - Scorpion
- Keep `Other` / uncertainty rejection behavior.
- Preserve the current dark cinematic neon design.
- Preserve OUSL Open Day branding.
- Do not replace the UI with a generic template.
- Avoid unnecessary dependencies.
- Prefer CSS and existing React state over adding a routing library unless the project
  already uses one.
- Do not expose secret/service-role Supabase keys.
- Do not break Vercel deployment.
- Do not commit `node_modules`, `dist`, or `.env`.

---

# First: Inspect Before Editing

Before changing code:

1. Read the current project structure.
2. Inspect:
   - `src/App.jsx`
   - `src/styles.css`
   - `src/components/`
   - `src/utils/game.js`
   - `src/utils/preprocessDrawing.js`
   - `src/utils/supabaseLeaderboard.js`
3. Identify how the current screen/navigation state works.
4. Identify the current CSS layout classes before adding duplicate styles.
5. Reuse existing components/classes where reasonable.

Do not guess file names or navigation behavior without inspecting the project.

---

# Responsive UI Requirements

## General

The app must have no horizontal scrolling at normal zoom.

Test these viewport widths:
- 1440px
- 1280px
- 1024px
- 768px
- 600px
- 480px
- 390px
- 360px

Use responsive CSS with breakpoints such as:

```css
@media (max-width: 1024px) {}
@media (max-width: 768px) {}
@media (max-width: 480px) {}
```

Do not rely only on fixed pixel sizes.

Prefer:
- `width: 100%`
- `max-width`
- `min()`
- `clamp()`
- CSS Grid
- Flexbox
- `aspect-ratio`
- responsive gaps/padding

Avoid fixed widths that overflow phones.

---

# Desktop Behavior

Do not noticeably change the current desktop layout.

Desktop should continue to look premium and spacious.

Keep:
- centered layouts
- cinematic backgrounds
- neon borders/glows
- glassmorphism panels
- large wheel
- drawing + AI result layout
- leaderboard styling

---

# Tablet Behavior

At tablet widths:

- Reduce excessive page padding.
- Scale the wheel down smoothly.
- Allow main game columns to wrap or stack when needed.
- Keep controls touch-friendly.
- Avoid clipped cards/panels.
- Keep headings readable.
- Stepper/progress UI must fit without overflow.

---

# Mobile Behavior

At mobile widths, prioritize usability.

## Main layout

Desktop layouts such as:

```text
Canvas | AI Result
```

must become:

```text
Canvas
AI Result
Action buttons
```

Stack major panels vertically.

## Header

On mobile:
- Reduce header padding.
- Keep logo/branding readable.
- Allow items to wrap cleanly.
- Hide or shorten non-essential status text if necessary.
- Never let header content overflow horizontally.

## Wheel

The wheel must scale with viewport width.

Use behavior similar to:

```css
width: min(500px, 88vw);
height: min(500px, 88vw);
```

Do not allow the wheel to leave the viewport.

Wheel text must remain reasonably readable.

## Drawing Canvas

The visible drawing canvas must fit the phone.

Important:
- Do not break pointer/touch coordinate mapping.
- Do not simply change the canvas HTML bitmap width/height without checking drawing logic.
- Keep internal canvas resolution stable if needed.
- Make the displayed canvas responsive through its container/CSS.
- Touch drawing must continue working.

The canvas card should use approximately:

```css
width: 100%;
max-width: 100%;
```

and fit inside the viewport.

## Drawing toolbar

Draw / Eraser / Brush size / Clear controls must:
- wrap correctly
- remain touch friendly
- have at least roughly 44px touch targets when practical
- not overflow

On small phones, use a 2-column layout or full-width controls.

## Main action buttons

Buttons such as:
- Spin
- Ask AI
- Next Round
- Play Again
- Home
- Back

must be easy to tap.

On narrow mobile screens, important action buttons may use:

```css
width: 100%;
```

## Timer / score

Timer and score indicators must not overlap other controls.

Use wrapping or smaller sizing on mobile.

## Prediction panel

Prediction results must fit the screen:
- no clipped percentages
- no horizontal overflow
- bars scale to container width
- long labels wrap safely

## Final score / leaderboard

Leaderboard cards/rows must:
- fit at 360px width
- keep player name, score and time visible
- truncate/wrap long player names safely
- not overflow horizontally

---

# Small Phone Requirements (<= 480px)

For 480px and below:

- Reduce large heading sizes using `clamp()`.
- Reduce outer padding.
- Reduce card padding slightly.
- Make major action buttons full width if needed.
- Stack controls where necessary.
- Keep canvas and wheel fully visible.
- Keep neon effects but reduce anything that creates layout overflow.
- Ensure text remains readable at 360px width.

Example typography approach:

```css
font-size: clamp(1.8rem, 8vw, 3rem);
```

Use this only where appropriate.

---

# Home Button Requirement

Add a clearly visible Home button to game screens where returning home is useful.

Recommended behavior:

- Home screen itself does not need a redundant Home button.
- Show Home on:
  - player setup
  - challenge/wheel screen
  - drawing screen
  - result/final screen
  - leaderboard-related game views if applicable

Use the current design language:
- glass/neon button
- existing icon library if available
- compact on desktop
- touch-friendly on mobile

## Home click behavior

When Home is clicked:

1. Navigate to the application's true home/landing screen.
2. Stop/clear any active drawing round timer.
3. Clear transient current-round UI state as needed:
   - selected object
   - current predictions
   - drawing state
   - temporary round result
4. Do NOT delete the shared Supabase leaderboard.
5. Do NOT clear stored leaderboard records.
6. Do NOT accidentally submit a score.
7. Do NOT create duplicate Supabase leaderboard entries.
8. Do not reload the full browser page unless absolutely necessary.

Preserve the player name only if that matches the existing UX.
Otherwise reset it consistently with the existing "new game" behavior.

---

# Back Button Requirement

Add a Back button on screens where a previous logical screen exists.

Do NOT use blind `window.history.back()` unless the current app already uses proper URL routing
and it is clearly correct.

For this project, prefer logical in-app navigation based on the existing screen/state system.

Expected navigation concept:

```text
Home
  -> Player Setup
  -> Challenge / Wheel
  -> Draw
  -> Result / Final
```

Back should go to the previous sensible in-app screen.

Examples:
- Player Setup -> Home
- Challenge/Wheel -> Player Setup
- Draw -> Challenge/Wheel, only if doing so will not corrupt game state
- Final -> previous result/game screen only if safe; otherwise Back can go to Home or a
  clearly defined previous stable screen

Inspect the actual app flow and implement the safest UX.

## Back safety

Back must not:
- duplicate a round
- submit scores twice
- decrease/increase score incorrectly
- start multiple timers
- leave stale predictions
- create inconsistent selected challenges
- duplicate Supabase inserts

If navigating backwards from an active timed round is unsafe, cancel/reset that round cleanly
before leaving.

---

# Navigation Button Placement

Use a consistent navigation area.

Preferred:
- top-left Back button
- nearby Home button, or Home at top-right if that matches the current layout

On mobile:
- keep both buttons visible
- use icon + short label if space allows
- if needed use icons with accessible `aria-label`

Do not cover:
- timer
- score
- stepper
- drawing canvas
- wheel

---

# Accessibility

Add appropriate:
- `type="button"`
- `aria-label` for icon-only buttons
- visible keyboard focus styles
- sufficient hit areas
- semantic button elements

Do not use clickable `<div>` elements when a `<button>` is appropriate.

---

# AI Logic — Do Not Break

Preserve:
- Teachable Machine model loading
- 224x224 preprocessing
- crop/center/resize preprocessing
- drawing-detail checks
- confidence threshold
- top-1 vs top-2 margin logic
- `Other` rejection
- Monkey rejection if the bundled model still contains a Monkey output class
- target class comparison behavior

Do not change model predictions merely to make UI testing look correct.

---

# Game Logic — Do Not Break

Preserve:
- 3-round game
- 20-second drawing timer
- non-repeating challenge selection within a game
- score calculation
- result transitions
- final score
- restart/new-game flow

Ensure responsive work does not affect game logic.

---

# Supabase Leaderboard — Do Not Break

Preserve shared leaderboard behavior:

- load leaderboard from Supabase
- save player name
- save score
- save total time
- highest score first
- faster time as tie breaker
- localStorage offline fallback if already implemented

Do not expose:
- secret key
- service-role key
- database password

Frontend may use only the existing Supabase publishable/browser key.

Important:
React development mode may run effects more than once.
Ensure final-score submission cannot create duplicate leaderboard rows.

Do not clear remote leaderboard data when navigating Home or Back.

---

# CSS Quality Rules

Before adding new CSS:
- inspect existing selectors
- reuse existing design tokens
- avoid duplicate conflicting media queries
- avoid `!important` unless truly required
- avoid arbitrary z-index escalation

Use `box-sizing: border-box` consistently.

Pay attention to:
- `min-width: 0` on grid/flex children
- `max-width: 100%`
- `overflow-wrap: anywhere` for long user-generated names if needed
- SVG/canvas sizing
- viewport-safe spacing

Do not hide essential content just to solve overflow.

---

# Files Likely to Change

Prefer a small focused patch.

Likely:
- `src/App.jsx`
- `src/styles.css`
- drawing canvas component
- wheel component if it has fixed inline sizing

Only change additional files when required.

Do not rewrite unrelated AI/data files.

---

# Verification

After changes, run:

```bash
npm install
npm run build
```

If available also run:

```bash
npm run lint
```

Fix errors caused by the changes.

Manually inspect the app at:
- 1440x900
- 1024x768
- 768x1024
- 390x844
- 360x800

Test full gameplay:

1. Home screen loads.
2. Player can continue.
3. Wheel works.
4. Draw screen works with mouse.
5. Draw screen works with touch/mobile pointer events.
6. Timer works.
7. AI prediction works.
8. Next round works.
9. Three rounds complete.
10. Supabase leaderboard loads.
11. Score is submitted once.
12. Home button works from relevant screens.
13. Back button works from relevant screens.
14. Back/Home do not create duplicate scores.
15. No horizontal scrollbar at 360px.
16. Desktop appearance remains essentially unchanged.

---

# Definition of Done

The task is done only when:

- Desktop UI still looks like the current premium design.
- Tablet layout is clean.
- Mobile layout works at 360px width.
- Wheel is responsive.
- Canvas is responsive and drawing coordinates remain correct.
- Prediction/result cards are responsive.
- Leaderboard is responsive.
- Home button exists and works.
- Back button exists and works.
- Navigation does not corrupt round state.
- No duplicate leaderboard submissions occur due to navigation.
- AI/model behavior remains intact.
- Supabase behavior remains intact.
- `npm run build` succeeds.

When finished, provide a concise summary of:
- files changed
- responsive changes
- Home/Back behavior
- tests/build commands run
- any remaining limitations
