---
navigation:
  parent: ae2:items-blocks-machines/items-blocks-machines-index.md
  title: Auto Pattern Terminal
  icon: aeautopatterns:auto_pattern_terminal
  position: 211
categories:
  - devices
item_ids:
  - aeautopatterns:auto_pattern_terminal
---

# Auto Pattern Terminal

<ItemImage id="aeautopatterns:auto_pattern_terminal" />

Automatically writes AE patterns for a target and its missing dependencies. **It consumes blank patterns, not recipe ingredients.** Finishing a plan means its patterns are ready; crafting still requires installed patterns, working machines and supplied base materials.

## Opening this guide

Aim directly at the installed terminal and press **G**. Change this binding under **Controls → AE Auto Patterns → Terminal guide** if needed. In an inventory, hover over the terminal item and **hold GuideME's guide key** (G by default). This page is also listed under AE's **Items, Blocks and Machines**.

## First patterns

1. Install the terminal on an ME cable. Supply power and a free channel; right-click to open it. Its panel inherits the cable color.
2. Search by item name or ID and click a result. Alternatively, hold an item on the inventory cursor and use **Select cursor item** to preserve its exact components. Selecting a target does not consume it.
3. Click **Analyze**. Review the missing, network, buffered and external materials. When a native recipe needs confirmation, click the marked material, browse the **3×3 recipe previews** with the arrows and confirm a recipe. Independent branches can be analyzed while a choice is pending.
4. Put AE **blank patterns** in the input slot. Once every route is resolved, click **Start / Resume**. The terminal writes at most one pattern per tick into its **27-slot output buffer**. The required count is the number of new patterns.
5. Install the results in [pattern providers](ae2:items-blocks-machines/pattern_provider.md). Native crafting, stonecutting and smithing patterns use [molecular assemblers](ae2:items-blocks-machines/molecular_assembler.md); processing patterns need the corresponding machines and input/output connections.

## Reviewing and adjusting a route

After analysis, open **Recipe tree**. The target is at the top; ingredients branch downward. Drag empty space to pan and use the wheel to zoom. Search or scroll the material list on the left, then click an entry to jump to it. Click a tree node to open its floating details and controls.

You can change a recipe, cycle a tagged ingredient or mark a material as **External supply** to stop expanding that branch. Shared materials may appear in several branches; changing one updates all of them and reanalyzes the plan. Encoding still deduplicates materials. The display stops at 4096 occurrences and marks truncated branches; this is separate from the analysis limit.

## Pattern options

- **Processing patterns**, off by default: generates supported machine recipes. Disabling it still allows installed or buffered patterns to be reused. Recipe IDs appear in the generated processing pattern's tooltip.
- **Item / fluid substitution**: AE's native pattern options. Defaults are items off, fluids on. They apply only to supported recipe inputs, not arbitrary machine requirements.
- **Automatic upload**: select a visible network pattern provider, then enable upload. Results go to that provider instead of the buffer. A missing or full target pauses generation; select another target or free space.

Machine recipes require a compatible adapter. Fluid and chemical routes need the matching integrations; Mekanism chemicals additionally require **Applied Mekanistics**. Chance byproducts and machine conditions are warnings, not guaranteed AE outputs. Supply energy, fuel and machine conditions yourself. **JEI is optional**; without it, analysis uses server recipes directly.

## How it works

For `A ← B + C` and `B ← D`, the terminal examines the supported server recipes and installed/buffered patterns, then expands the inputs. It writes missing dependencies before their parents: **B before A**. A material with no supported recipe becomes external supply; explicitly unsupported recipes remain a problem until you choose another route or set an external boundary.

An existing pattern skips encoding that product, but its inputs are still checked. Items merely sitting in network storage do not stop expansion; pattern items in ordinary storage do not count as deployed. Shared dependencies are planned once.

Analysis works in small server steps, groups cycles and tries alternative routes. Compression/decompression loops can make the repeated raw ingredient external; a manually locked cyclic route instead reports a problem. Changing a material reuses unaffected analysis. Default limits are **512 levels and 16384 candidate materials**; very large packs may take longer.

## When it pauses

| Status | What to do |
|---|---|
| Choose recipe | Click the marked material, browse previews and confirm. |
| Unresolved route / cycle | Click the bottom warning to locate the failing step; change its recipe/ingredient or set external supply. |
| No blanks / output full | Add blank patterns or remove outputs. Generation can continue as space becomes available. |
| Offline | Restore power and channels. |
| Stale / analyze again | Reanalyze after network membership, selected patterns, recipes or world loading change. Existing outputs remain. |
| Unsupported | Choose a supported route, install a matching adapter or explicitly supply this material externally. |

**Pause** waits for manual resume. **Cancel** ends the task and keeps inventory. Hover over the status line for analysis progress and diagnostics. Route choices and terminal inventory are shared by everyone using the same terminal.

## Recipe

<RecipeFor id="aeautopatterns:auto_pattern_terminal" />
