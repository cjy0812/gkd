# Gesture API design (v1 safe step)

## 状态总览

**已实现（可编译、可被内部调用）**
- `GestureAction` / `GestureDirection` 数据结构（仅 Kotlin 内部可用）。  
- `GestureActionExecutor` 执行骨架（未接入规则 JSON）。  

**设计中（不可在规则中使用）**
- 规则侧 JSON `gesture` 字段解析与持久化。
- 规则动作 DSL（例如 `gestureChain`、`awaitState` 的 JSON 语法）。

> 注意：本文件与当前代码都明确标注 `@DesignOnly`，说明这些能力暂不属于公开规则 API。

---

## 为什么这是“安全的第一步”

- **不破坏现有规则体系**：未修改 `RawSubscription` 的 JSON 解析签名与规则字段。
- **可选、可扩展**：先冻结 Kotlin 端 API 形态，未来可以在保持向后兼容的前提下接入规则 JSON。
- **CI 友好**：当前改动可稳定通过 `:app:assembleDebug` 编译，不依赖设备或模拟器。

---

## 未来接入规则的路径（设计说明）

当需要对规则作者开放时，建议按以下步骤分阶段接入：

1. **阶段 1**：为规则 JSON 增加可选字段 `gesture`（仅在新版本解析）。
2. **阶段 2**：将 `gesture` 解析为 `GestureAction`，并在 `ResolvedRule.performAction` 中启用。
3. **阶段 3**：增加规则校验与文档示例，明确与旧 `action/position` 的优先级规则。

> 这些步骤目前**未实现**，仅作为设计说明。

---

## 规则示例（仅文档示例，不进入解析）

```json
{
  "gesture": {
    "type": "swipeRelative",
    "anchor": "[vid=\"recycler\"]",
    "direction": "down",
    "distanceRatio": 0.6
  }
}
```

```json
{
  "gesture": {
    "type": "gestureChain",
    "steps": [
      { "type": "awaitState", "selector": "[text=\"确认\"]", "timeoutMs": 2000 },
      { "type": "offsetClick", "anchor": "[text=\"确认\"]", "xRatio": 0.5, "yRatio": 0.5 }
    ]
  }
}
```

---

## 明确限制（非目标）

- **不接入规则 JSON**：当前版本不支持 `gesture` 规则字段。
- **不破坏旧规则**：原有 `action` / `position` 行为完全不变。
- **不追求功能完整**：只保证 API 形态与执行骨架可编译。
