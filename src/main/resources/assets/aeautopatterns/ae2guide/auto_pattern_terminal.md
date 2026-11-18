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

## Use It

1. **Connect the terminal.** Attach it to the side of an ME cable. Make sure the network has power and an available channel, then right-click the terminal.
2. **Choose a target.** Enter an item name or ID in the search box and click a result. You can also hold an item on the cursor and click **Select cursor item**. With JEI installed, drag a JEI ghost item into the search field.
3. **Analyze the route.** Click **Analyze** and wait for **Ready**. The left queue lists materials that need a recipe choice or have an unresolved path. Select a pending material, browse its 3×3 recipe preview with the arrows, then confirm. For an unresolved material, click it to locate the path and change its recipe, ingredient, or external-supply setting.
4. **Write patterns.** Put AE blank patterns in the blank pattern input slot. The screen shows how many are needed. Once the route is resolved, click **Start / Resume**. Each new pattern uses one blank pattern and appears in the output buffer.
5. **Install the results.** Move patterns into AE pattern providers. Molecular assemblers handle crafting, stonecutting and smithing patterns. Processing patterns need the matching machine, fuel, power and item connections.

Scroll the list, or select several entries and set them to external supply together.

## Optional Features

- To generate supported machine recipes such as furnace recipes, enable **Processing patterns** before analysis. Configure machine fuel and operating conditions yourself.
- Open **Recipe tree** to inspect the full material route. Select a material to change its recipe, ingredient or external-supply setting.
- Open **Provider target**, select network providers, then enable **Automatic upload** to send generated patterns directly to them.

## What Analysis Does

The terminal follows supported recipes from the target down to their inputs, then writes missing patterns from ingredients upward. For `A ← B + C` and `B ← D`, the order is **D, B, A**. Shared materials are written once. Existing network patterns skip encoding for their product while the terminal still checks that product's inputs. Each new pattern uses one blank pattern; analysis and encoding do not consume recipe ingredients.

- **Choose recipe:** select the material in the left queue, preview a recipe and confirm it.
- **Unresolved / unsupported:** select the material, choose another route or set external supply.
- **Waiting for blanks:** add blank patterns and click **Start / Resume**.
- **Output full / offline:** take patterns from the output, or restore network power and channel access.

Aim at the terminal and press **G** to reopen this guide. In an inventory, hold GuideME's guide key while hovering over the terminal item.

## Recipe

<RecipeFor id="aeautopatterns:auto_pattern_terminal" />
