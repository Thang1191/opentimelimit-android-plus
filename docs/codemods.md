# Retired codemods

Until 20/11/2026 the repo root carried thirteen one-shot scripts that rewrote source files in place:
twelve Groovy `fix_*.gradle` files and a Windows `add_strings.ps1`. None of them was referenced by
`app/build.gradle`, `settings.gradle`, a CI workflow or any other script — they were run by hand from
the original dev box (`./gradlew -I fix_x.gradle` or similar), which is why the tree is left
**half-migrated**.

All thirteen were deleted in the phase-1 cleanup. This file is their record: the mapping each one
applied, and whether that mapping actually landed. If a pattern below still appears in the tree, that
script never ran or ran before the file was written — fix it by hand in a reviewed diff, do **not**
resurrect the script.

| Script / task | Rewrite | Landed? |
|---|---|---|
| `fix_layouts.gradle` → `fixLayouts` | `?android:textAppearanceLarge` → `?attr/textAppearanceTitleLarge`, `Medium` → `textAppearanceBodyLarge`, `Small` → `textAppearanceBodyMedium`, `ListItem` → `textAppearanceBodyLarge`; `"?colorOnSurface"` → `"?attr/colorOnSurface"` | yes — 0 leftovers |
| `fix_attr.gradle` → `fixAttr` | `"?\"?attr/colorOnSurface"` → `"?attr/colorOnSurface"` | yes — 0 leftovers |
| `fix_attr2.gradle` → `fixAttr2` | `=""?attr` → `="?attr` | yes — 0 leftovers |
| `fix_colors.gradle` → `fixColors` | `@color/colorPrimary` → `?attr/colorPrimary`, `@color/colorAccent` and `?colorAccent` → `?attr/colorSecondary` | mostly — 1 layout still on `@color/colorPrimary` |
| `fix_material_colors.gradle` → `fixMaterialColors` | `@color/text_red` / `@color/orange_text` → `?attr/colorError`; `@color/orange_background` / `@color/background_red` → `?attr/colorErrorContainer`; `android:textColor="@color/white"` and `android:drawableTint="@color/white"` → `?attr/colorOnErrorContainer`; `android:drawableTint="@color/orange_text"` → `?attr/colorError`; `android:tint="@color/gray"` → `?attr/colorOutline`; `@color/text_green` → `?attr/colorPrimary` | **no — 9 layouts still on the old alert colours** |
| `fix_checkboxes.gradle` → `fixCheckboxes` | `<CheckBox` → `<com.google.android.material.checkbox.MaterialCheckBox` (+ closing tag) | yes — 0 leftovers |
| `fix_radio.gradle` → `fixRadio` | `<RadioButton` → `<com.google.android.material.radiobutton.MaterialRadioButton` (+ closing tag) | mostly — 2 layouts still use a bare `<RadioButton` |
| `fix_dialogs.gradle` → `fixDialogs` | `AlertDialog.Builder` → `MaterialAlertDialogBuilder` in code | yes — 0 leftovers |
| `fix_fab.gradle` → `fixFab` | `.outerCircleColor(R.color.colorAccent)` → Material attribute | mostly — 1 `outerCircleColor` site left |
| `fix_actionbar.gradle` → `fixActionBar` | injects `<item name="actionBarStyle">@style/AppActionBar</item>` into `AppTheme` | yes — present |
| `fix_bind.gradle` → `fixTabs` | `@style/ThemeOverlay.AppCompat.Dark.ActionBar` → `@style/AppThemeDarkOverlay` | yes — 0 leftovers |
| `fix_homescreen.gradle` → `fixHomescreen` | patches `super.onCreate(savedInstanceState)` in `MainActivity` | n/a — one-off, superseded |
| `add_strings.ps1` | appends `category_time_limit_rules_lifeup_integration`, `_desc` and `_shop_item_name` to `res/values/strings.xml`, hardcoded path `a:\AndroidProjects\opentimelimit-android\` | yes — all three strings are in `strings.xml` |

## Why they had to go

- **Unversioned source mutation.** They rewrite files with regexes at whatever moment someone runs
  them; there is no diff, no backup, and no idempotency guarantee.
- **Half-applied state is invisible.** Nothing in the repo said which ones had run, so a reader could
  not tell "this screen is still red on purpose" from "the codemod never reached this screen". Nine
  layouts were in the second category.
- **They shadow the build.** A `fix_*.gradle` sitting at the root reads like part of the build to
  anyone new; the real build config is `app/build.gradle` and nothing else.

## Phase 2 outcome (20/11/2026)

The migration the codemods left half-finished was completed by hand. What changed:

- **22 colour references across 9 layouts.** `@color/text_red` and `@color/orange_text` →
  `?attr/colorError`; `@color/text_green` → `?attr/colorPrimary`. Files: `fragment_setup_device_permissions`,
  `manage_device_permissions_fragment`, `notify_permission_card`, `manage_notification_filter_dialog_fragment`,
  `fragment_edit_time_limit_rule_dialog`, `fragment_overview_device_item`, plus the three alert cards.
- **The three alert cards** (`manage_device_manipulation_view`, `manage_child_manipulation_warning`,
  `missing_permission_view`) use `?attr/colorError`, **not** `?attr/colorErrorContainer` as the old
  codemod intended. Deliberate: each card sets `android:theme="@style/AppThemeDarkOverlay"` and hardcodes
  `android:textColor="@color/white"` on its children, so a light container colour would put white text on
  a light surface. `colorError` keeps the white-on-red contrast in both light and dark themes. If the cards
  are ever restyled, switch the text to `?attr/colorOnErrorContainer` first, then to the container colour.
- **Two bare `<RadioButton>`** in `manage_category_networks_view.xml` → `MaterialRadioButton`.
- **Five now-dead colours removed** from `values/colors.xml`: `text_red`, `text_green`, `orange_text`,
  `orange_background`, `background_red` — zero references left anywhere in `app/src/main`. `white`, `gray`
  and the `blockedtimes_*` trio are still in use and stay.
- **`outerCircleColor` needed nothing.** The file already calls
  `outerCircleColorInt(MaterialColors.getColor(fab, R.attr.colorSecondary))`; the earlier grep was matching
  that identifier's prefix, so `fix_fab` landed semantically.

One deliberate non-change: `fragment_blocked_time_areas_minute_tile.xml` keeps `@color/colorPrimary` inside a
data-binding expression (`@{safeUnbox(selected) ? @color/colorPrimary : ...}`). A `?attr/` theme attribute
cannot appear inside a binding expression, so theming it means resolving the colour in the adapter
(`MaterialColors.getColor(view, R.attr.colorPrimary)`). Left alone; `colorPrimary` stays in `colors.xml` for
this and for `styles.xml`.

Verified: `./gradlew assembleDebug` → BUILD SUCCESSFUL, and `grep` for all five retired colour names returns
zero hits in `res/layout`.

