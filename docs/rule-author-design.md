# Rule Author Productivity Plan: Snapshots + Gestures

This document captures **what is implemented vs. design-only** in the current change set, and how the proposed APIs improve rule author workflow without relying on device-only debugging.

## High-level summary

**Implemented in this change**
- Snapshot workspace upgrades: multi-select, batch delete with confirmation, filter + grouping, and a thumbnail grid optimized for quick browsing. (See `SnapshotPage` UI changes.)

**Design-only in this change**
- Non-destructive in-app snapshot annotations (rect + blur/pixelate overlays).
- New gesture/combination action rule APIs and their execution pipeline.

---

## Part A — Snapshot system redesign (rule-author focused)

### Implemented behavior (UI + flow)
1. **Multi-select snapshot management**
   - Selection mode (toggle in app bar or long-press a tile).
   - Batch delete with explicit count confirmation to prevent accidental mass deletion.
2. **Grouping & filtering**
   - Filter text field for `appId` / `activityId`.
   - Grouping modes: **App**, **Activity**, **Date**.
   - Thumbnail grid (adaptive column width) with quick metadata under each preview.

### Data structures / APIs (implemented)
```kotlin
private enum class SnapshotGroupMode(val label: String) { App, Activity, Date }

private data class SnapshotGroup(
  val key: String,
  val title: String,
  val count: Int,
  val snapshots: List<Snapshot>,
)
```
- Selection state is maintained as a `Set<Long>` of snapshot IDs for O(1) membership checks.
- Filtering is a simple predicate over `appId` + `activityId` to keep it cheap and predictable.

### Non-destructive annotations (DESIGN-ONLY)
Goal: allow **privacy-safe masking** without rewriting the original PNG.

**Storage proposal**
```
snapshot/<id>/
  <id>.png              # original
  <id>.json             # snapshot data
  <id>.annotations.json # overlay metadata (new)
```

**Overlay model**
```kotlin
sealed interface SnapshotOverlay {
  val id: String
  val rect: RectF // relative (0..1) to screenshot bounds
}

data class RedactRect(
  override val id: String,
  override val rect: RectF,
) : SnapshotOverlay

data class PixelateRect(
  override val id: String,
  override val rect: RectF,
  val pixelSize: Int = 12,
) : SnapshotOverlay
```

**Rendering strategy**
- UI renders overlays on top of the bitmap without mutating the PNG.
- Export/share can optionally **burn in overlays** into a derived bitmap (never overwrite the original).

**Status**: design-only (requires new overlay renderer + storage updates).

---

## Part B — Gesture & combination action APIs (MOST IMPORTANT)

### Goals
- Express **human-like gesture sequences** relative to UI elements.
- Treat combinations as **one logical action**, not a fragile chain of delays.

### Proposed API definitions (DESIGN-ONLY)
TypeScript-ish pseudocode:
```ts
type Direction = "up" | "down" | "left" | "right"

type GestureAction =
  | { type: "swipeRelative"; anchor: Selector; direction: Direction; distanceRatio: number; durationMs?: number }
  | { type: "longPressThenSwipe"; anchor: Selector; direction: Direction; distanceRatio: number; holdMs?: number; durationMs?: number }
  | { type: "offsetClick"; anchor: Selector; xRatio: number; yRatio: number }
  | { type: "gestureChain"; steps: GestureAction[]; timeoutMs?: number }
  | { type: "awaitState"; selector: Selector; timeoutMs: number }
```

### Internal execution model (DESIGN-ONLY)
**Engine integration**
1. Extend the `ActionPerformer` pipeline with a `GestureActionPerformer` that can:
   - Resolve anchor nodes into screen rects.
   - Convert relative ratios to absolute coordinates.
   - Emit a single `GestureDescription` for multi-step chains.
2. Treat `gestureChain` as **atomic**:
   - The engine schedules the entire chain as **one logical action**.
   - No other rule action interleaves until the chain completes or fails.

**Scheduling**
- Each step resolves its anchor selector **at execution time**, not once at the start.
- `awaitState` is a precondition step that blocks the chain until it matches or times out.
- If a step cannot resolve its anchor, the chain **fails fast** and reports an actionable error (no silent delay fallback).

**Why this is safer than delays**
- Anchors and coordinates are computed from the current UI state.
- Result is resilient to layout changes because all offsets are relative.

**Status**: design-only (requires rule schema changes + new performer + gesture injection support).

---

## Part C — Rule-side usage examples (MANDATORY)

> Syntax shown below is **proposed** for new actions, using existing selector styles.

### 1) `swipeRelative`
**Problem solved**: scrolling a list without hardcoded coordinates.
```json
{
  "name": "下滑刷新",
  "matches": ["[vid=\"recycler\"]"],
  "action": {
    "type": "swipeRelative",
    "anchor": "[vid=\"recycler\"]",
    "direction": "down",
    "distanceRatio": 0.6
  }
}
```

### 2) `longPressThenSwipe`
**Problem solved**: long-press avatar then drag down to reveal a speed menu.
```json
{
  "name": "长按头像拖拽",
  "matches": ["[desc*=\"头像\"]"],
  "action": {
    "type": "longPressThenSwipe",
    "anchor": "[desc*=\"头像\"]",
    "direction": "down",
    "distanceRatio": 0.4,
    "holdMs": 500
  }
}
```

### 3) `offsetClick`
**Problem solved**: clicking a predictable offset (e.g., right side of a chip).
```json
{
  "name": "点选标签右侧",
  "matches": ["[text=\"清理\"]"],
  "action": {
    "type": "offsetClick",
    "anchor": "[text=\"清理\"]",
    "xRatio": 0.85,
    "yRatio": 0.5
  }
}
```

### 4) `gestureChain`
**Problem solved**: execute two gestures as one logical action.
```json
{
  "name": "连贯下拉+点击",
  "matches": ["[vid=\"panel\"]"],
  "action": {
    "type": "gestureChain",
    "steps": [
      { "type": "swipeRelative", "anchor": "[vid=\"panel\"]", "direction": "down", "distanceRatio": 0.5 },
      { "type": "offsetClick", "anchor": "[text=\"清理\"]", "xRatio": 0.5, "yRatio": 0.5 }
    ]
  }
}
```

### 5) `awaitState`
**Problem solved**: wait for a dialog before clicking, without delay hacks.
```json
{
  "name": "等待确认弹窗",
  "matches": ["[text=\"删除\"]"],
  "action": {
    "type": "gestureChain",
    "steps": [
      { "type": "awaitState", "selector": "[text=\"确认\"]", "timeoutMs": 2000 },
      { "type": "offsetClick", "anchor": "[text=\"确认\"]", "xRatio": 0.5, "yRatio": 0.5 }
    ]
  }
}
```

---

## Part D — Limitations and non-goals

- **No full image editor**: annotations are limited to rectangle + pixelate overlays only.
- **No destructive edits**: original snapshots remain intact; overlays are separate metadata.
- **No absolute coordinate scripting**: all gestures stay relative to UI elements.
- **No silent delay chains**: `awaitState` is the only allowed wait gate.
- **Rule-linked snapshot grouping is design-only**: current snapshot records do not persist rule IDs; adding this requires schema and capture changes.
