"""Writes scene.rml: four 200x200 transparent artboards (Sun, Cloud, Rain, Snow), each self-playing.
Property keys (rive schema Shape --animatable): x 13, y 14, rotation 15, scaleX 16, scaleY 17, opacity 18.
Run: python -I make_scene.py  (then: rive . --once)"""
import math
import pathlib

_next = [10]


def nid():
    _next[0] += 1
    return f"0:{_next[0]}"


def fill(color):
    return f'<Fill name="Fill"><SolidColor colorValue="{color}" name="Color"/></Fill>'


def kf(frames_values, interp="cubic"):
    out = []
    for f, v in frames_values:
        fr = f' frame="{f}"' if f else ""
        out.append(f'<KeyFrameDouble value="{v:.4f}" interpolationType="{interp}"{fr}/>')
    return "".join(out)


def keyed(obj, key, frames_values, interp="cubic"):
    return f'<KeyedObject objectId="{obj}"><KeyedProperty propertyKey="{key}">{kf(frames_values, interp)}</KeyedProperty></KeyedObject>'


def cloud_shape(sid, cx, cy, s, color):
    # three puffs + a rounded base, all paths of one Shape so it moves as one
    paths = (
        f'<Ellipse x="{-28*s:.1f}" y="{4*s:.1f}" width="{52*s:.1f}" height="{46*s:.1f}" name="Puff L"/>'
        f'<Ellipse x="{6*s:.1f}" y="{-10*s:.1f}" width="{66*s:.1f}" height="{62*s:.1f}" name="Puff M"/>'
        f'<Ellipse x="{36*s:.1f}" y="{8*s:.1f}" width="{44*s:.1f}" height="{40*s:.1f}" name="Puff R"/>'
        f'<Rectangle x="{4*s:.1f}" y="{20*s:.1f}" width="{112*s:.1f}" height="{30*s:.1f}" cornerRadiusTL="{15*s:.1f}" name="Base"/>'
    )
    return f'<Shape x="{cx}" y="{cy}" name="Cloud" id="{sid}">{paths}{fill(color)}</Shape>'


def artboard(name, shapes_xml, anim_xml, duration, x):
    ab, style, sm, layer, state, anim = nid(), nid(), nid(), nid(), nid(), nid()
    return (
        f'<Artboard defaultStateMachineId="{sm}" styleId="{style}" x="{x}" y="0" width="200" height="200" name="{name}" id="{ab}">'
        f'<LayoutComponentStyle name="Artboard Style" id="{style}"/>'
        f'{shapes_xml}'
        f'<StateMachine name="State Machine 1" id="{sm}"><StateMachineLayer name="Layer 1" id="{layer}">'
        f'<EntryState><StateTransition stateToId="{state}"/></EntryState>'
        f'<AnimationState x="200" animationId="{anim}" id="{state}"/></StateMachineLayer></StateMachine>'
        f'<LinearAnimation loopValue="loop" duration="{duration}" name="Loop" id="{anim}">{anim_xml}</LinearAnimation>'
        f'</Artboard>'
    )


SUN, CLOUD, GREY, DROP, FLAKE = "FFFFC542", "FFF1F4F8", "FFB8C2CF", "FF6FB7FF", "FFFFFFFF"
parts = []

# ---- Sun: rays turn slowly, core breathes
rays, core = nid(), nid()
ray_paths = "".join(
    f'<Rectangle x="{58*math.cos(a):.2f}" y="{58*math.sin(a):.2f}" rotation="{a:.4f}" width="26" height="9" cornerRadiusTL="4.5" name="Ray {i}"/>'
    for i, a in enumerate(2 * math.pi * k / 8 for k in range(8)))
shapes = (f'<Shape x="100" y="100" name="Core" id="{core}"><Ellipse width="76" height="76" name="Disc"/>{fill(SUN)}</Shape>'
          f'<Shape x="100" y="100" name="Rays" id="{rays}">{ray_paths}{fill(SUN)}</Shape>')
anim = (keyed(rays, 15, [(0, 0), (480, 2 * math.pi)], "linear")
        + keyed(core, 16, [(0, 1), (240, 1.08), (480, 1)]) + keyed(core, 17, [(0, 1), (240, 1.08), (480, 1)]))
parts.append(artboard("Sun", shapes, anim, 480, 0))

# ---- Cloud: drifts left-right, small sun peeking behind it
c, sun2 = nid(), nid()
shapes = (cloud_shape(c, 92, 112, 1.0, CLOUD)
          + f'<Shape x="128" y="70" name="Peek" id="{sun2}"><Ellipse width="56" height="56" name="Disc"/>{fill(SUN)}</Shape>')
anim = keyed(c, 13, [(0, 86), (180, 104), (360, 86)]) + keyed(sun2, 18, [(0, 0.85), (180, 1), (360, 0.85)])
parts.append(artboard("Cloud", shapes, anim, 360, 260))


def falling(kind, color, period, x0s, delay_step, length):
    cl = nid()
    shapes = cloud_shape(cl, 96, 78, 0.9, GREY)
    anim = keyed(cl, 13, [(0, 92), (period // 2, 100), (period, 92)])
    for i, x0 in enumerate(x0s):
        sid = nid()
        geo = (f'<Rectangle width="7" height="{length}" cornerRadiusTL="3.5" name="Drop"/>' if kind == "rain"
               else '<Ellipse width="11" height="11" name="Flake"/>')
        shapes += f'<Shape x="{x0}" y="122" name="{kind} {i}" id="{sid}">{geo}{fill(color)}</Shape>'
        d = (i * delay_step) % period          # stagger: each one starts its fall at a different frame
        top, bottom = 122, 188
        fr = sorted({0, d, min(d + period * 3 // 4, period), period})
        ys, ops = [], []
        for f in fr:
            t = ((f - d) % period) / (period * 0.75)
            t = min(t, 1.0)
            ys.append((f, top + (bottom - top) * t))
            ops.append((f, 1.0 - t if t < 1 else 0.0))
        anim += keyed(sid, 14, ys, "linear") + keyed(sid, 18, ops, "linear")
        if kind == "snow":
            anim += keyed(sid, 13, [(0, x0), (period // 2, x0 + 6), (period, x0)])
    return shapes, anim


s, a = falling("rain", DROP, 60, [70, 100, 130], 20, 18)
parts.append(artboard("Rain", s, a, 60, 520))
s, a = falling("snow", FLAKE, 150, [68, 98, 128], 50, 0)
parts.append(artboard("Snow", s, a, 150, 780))

pathlib.Path(__file__).with_name("scene.rml").write_text(
    '<Rive version="1" kind="fragment">\n' + "\n".join(parts) + "\n</Rive>\n", encoding="utf-8")
pathlib.Path(__file__).with_name("rive.yaml").write_text("name: weather_icons\nmain: Sun\n", encoding="utf-8")
print("scene.rml written")
