# Third-Party Notices

This project uses public APIs from several Minecraft mod projects. Their source code, game assets, and trademarks remain the property of their respective authors.

## Applied Energistics 2

- Project: https://github.com/AppliedEnergistics/Applied-Energistics-2
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Usage in this project: public AE2 API types for grids, AE keys, generic stacks, pattern encoding, pattern containers, and inventories.
- Derived or redistributed AE2 assets: none.

The AE2 API is used as a runtime dependency and is not copied into this repository or bundled into the AE Auto Patterns source tree.

## JEI

- Project: https://github.com/mezz/JustEnoughItems
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Usage in this project: optional client-side recipe lookup integration.
- Derived or redistributed JEI assets: none.

JEI is optional. AE Auto Patterns falls back to the server-side RecipeManager when JEI is absent.

## Optional machine integrations

The following projects are detected at runtime and are not bundled or modified by this project:

- Mekanism: https://github.com/mekanism/Mekanism
- Applied Mekanistics: https://github.com/AppliedEnergistics/Applied-Mekanistics
- Create: https://github.com/Creators-of-Create/Create
- Thermal Series: https://github.com/CoFH/CoFHCore
- Immersive Engineering: https://github.com/BluSunrize/ImmersiveEngineering
- PneumaticCraft: https://github.com/Desh701/PneumaticCraft
- Modern Industrialization: https://github.com/AztechMC/Modern-Industrialization

Each integration is optional and uses the target mod's public recipe or integration API where available. If a target mod is not installed, its adapter is disabled. No target mod classes, textures, models, or license files are copied into this repository.

The applicable license terms for an optional integration are provided by that mod's own distribution. Users redistributing a build that bundles any optional dependency must also follow that dependency's license and notice requirements.

## Original assets in this repository

The following resources are AE Auto Patterns assets created for this project. They are included in the mod distribution, but are not licensed for independent reuse; see `ART_ASSETS_NOTICE.md`:

- `src/main/resources/assets/aeautopatterns/models/part/auto_pattern_terminal_on.json`
- `src/main/resources/assets/aeautopatterns/models/part/auto_pattern_terminal_off.json`
- `src/main/resources/assets/aeautopatterns/models/item/auto_pattern_terminal.json`
- `src/main/resources/assets/aeautopatterns/textures/part/auto_pattern_terminal.png`
- `src/main/resources/assets/aeautopatterns/textures/part/auto_pattern_terminal_bright.png`
- `src/main/resources/assets/aeautopatterns/textures/part/auto_pattern_terminal_medium.png`
- `src/main/resources/assets/aeautopatterns/textures/part/auto_pattern_terminal_dark.png`
- `src/main/resources/assets/aeautopatterns/textures/gui/terminal.png`

The model was authored for this project and uses AE2 model parents at runtime
where declared in the JSON. The `credit` field in the item model identifies
AE2 display geometry. AE2-derived portions remain under AE2's LGPL-3.0 license;
the project's original panel textures and other original artwork remain
reserved under `ART_ASSETS_NOTICE.md`. AE2 parent models and textures are not
copied or relicensed here.

## Project license

Original Java source code is distributed under LGPL-3.0-or-later in `LICENSE`. Artwork and other non-code resources are excluded from that license; see `ART_ASSETS_NOTICE.md`.
