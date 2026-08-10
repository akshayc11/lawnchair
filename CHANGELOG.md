# 0.1.0

Settings search, indexed by composition rather than by hand.

- Search icon in the settings dashboard top bar, new `SettingsSearch` route with deep link `lawnchair://settings/settings-search`.
- The shared preference controls register themselves through `RegisterSearchableSetting`, so a newly added preference becomes searchable with no search-side work.
- Screens that take no arguments are declared once in `navigation/PreferenceScreens.kt`, which drives both the navigation graph and the off-screen indexing pass.
- The indexing pass composes and measures every indexable screen, then places nothing; launcher previews, wallpaper previews and smartspace setup opt out of it.
- Results show the full path to a setting, `screen › group › label`.
- Fixed `DividerColumn` measuring an empty group to a negative height, which crashed with `Size(w x -5) is out of range`.
