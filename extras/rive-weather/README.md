# Animated weather icon

Source of `launcher/app/src/main/res/raw/weather_icons.riv` (artboards Sun, Cloud, Rain, Snow, each with
"State Machine 1"). `make_scene.py` writes `scene.rml`; the Rive CLI turns it into the `.riv` file.
The launcher shows it left of the weather line (theme key `weather_icon`, on by default).
