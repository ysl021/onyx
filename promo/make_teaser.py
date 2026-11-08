"""Renders the ONYX teaser (1080x1920, 30 fps) with PIL and encodes it with ffmpeg.

Usage:
    python promo/make_teaser.py --src <folder with screenshots> --out promo/onyx-teaser.mp4

Expected screenshots in --src (any missing scene is skipped):
    player.png, lyrics.png, music.png, visualizer.png, buds.png, settings.png
"""

from __future__ import annotations

import argparse
import math
import random
import subprocess
import wave
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont
import imageio_ffmpeg

W, H = 1080, 1920
FPS = 30
INTRO = 2.6
SCENE = 2.9
OUTRO = 3.2
FADE = 0.38

FONT_BOLD = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_REG = r"C:\Windows\Fonts\segoeui.ttf"
FONT_LIGHT = r"C:\Windows\Fonts\segoeuil.ttf"

SCENES = [
    ("player.png", "ВСЯ МУЗЫКА", ["Spotify и локальные файлы", "в одном пульте."]),
    ("lyrics.png", "ТЕКСТЫ", ["Тайминги, перевод", "и мгновенный кэш."]),
    ("music.png", "ЛОКАЛЬНАЯ МУЗЫКА", ["Свои папки, теги", "и обложки."]),
    ("visualizer.png", "ВИЗУАЛИЗАТОР", ["Реактивный спектр", "вокруг обложки."]),
    ("buds.png", "GALAXY BUDS", ["ANC, режим одного уха", "и заряд на виду."]),
    ("", "СВОИ ТЕМЫ", ["Темы, иконки, шрифты", "и настоящее стекло."]),
]


def ease(t: float) -> float:
    return t * t * (3 - 2 * t)


def ease_out(t: float) -> float:
    return 1 - (1 - t) ** 3


def tracked(draw: ImageDraw.ImageDraw, xy, text, font, fill, spacing=0):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += draw.textlength(ch, font=font) + spacing


def tracked_width(draw, text, font, spacing=0):
    return sum(draw.textlength(ch, font=font) + spacing for ch in text) - (spacing if text else 0)


def make_background():
    img = Image.new("RGB", (W, H), (6, 6, 7))
    px = img.load()
    for y in range(H):
        t = y / H
        v = int(6 + 10 * t)
        for x in range(W):
            px[x, y] = (v, v, v + 1)
    glow = Image.new("L", (W, H), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse((W * 0.08, H * 0.12, W * 0.92, H * 0.78), fill=44)
    glow = glow.filter(ImageFilter.GaussianBlur(180))
    white = Image.new("RGB", (W, H), (250, 250, 252))
    img = Image.composite(white, img, glow.point(lambda v: v // 7))
    return img


def rounded_phone(shot: Image.Image, height: int) -> Image.Image:
    ratio = shot.width / shot.height
    width = int(height * ratio)
    shot = shot.resize((width, height), Image.LANCZOS)
    radius = 46
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius, fill=255)
    out = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    out.paste(shot.convert("RGBA"), (0, 0), mask)
    border = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(border).rounded_rectangle(
        (0, 0, width - 1, height - 1), radius, outline=(255, 255, 255, 34), width=3
    )
    return Image.alpha_composite(out, border)


def phone_card(shot: Image.Image, height: int, zoom: float) -> Image.Image:
    base = rounded_phone(shot, height)
    if abs(zoom - 1.0) > 0.001:
        nw, nh = int(base.width * zoom), int(base.height * zoom)
        base = base.resize((nw, nh), Image.LANCZOS)
    return base


def draw_shadow(canvas: Image.Image, box, radius=56):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x0, y0, x1, y1 = box
    d.rounded_rectangle((x0 - 18, y0 + 26, x1 + 18, y1 + 42), radius, fill=(0, 0, 0, 150))
    layer = layer.filter(ImageFilter.GaussianBlur(38))
    canvas.alpha_composite(layer)


def draw_icon_glyph(d: ImageDraw.ImageDraw, cx: float, cy: float, index: int):
    col = (246, 246, 247, 255)
    w = 4
    if index == 1:
        d.ellipse((cx - 27, cy - 27, cx + 27, cy + 27), outline=col, width=w)
        for i, h in enumerate([10, 20, 27, 20, 10]):
            x = cx - 20 + i * 10
            d.line((x, cy - h / 2, x, cy + h / 2), fill=col, width=4)
    elif index == 2:
        d.ellipse((cx - 29, cy - 29, cx + 29, cy + 29), outline=col, width=5)
        d.ellipse((cx - 13, cy - 13, cx + 13, cy + 13), outline=col, width=3)
    elif index == 3:
        d.ellipse((cx - 27, cy - 27, cx + 27, cy + 27), fill=col)
        d.ellipse((cx - 13, cy - 13, cx + 13, cy + 13), fill=(8, 8, 9, 255))
        d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), fill=col)
    elif index == 4:
        pts = []
        for i in range(41):
            x = cx - 30 + i * 1.5
            pts.append((x, cy + 11 * math.sin(i / 40 * math.pi * 4)))
        d.line(pts, fill=col, width=5, joint="curve")
    elif index == 5:
        d.polygon([(cx + 6, cy - 30), (cx - 18, cy + 4), (cx - 3, cy + 4),
                   (cx - 8, cy + 30), (cx + 18, cy - 6), (cx + 2, cy - 6)], fill=col)
    elif index == 6:
        pts = []
        for i in range(8):
            a = -math.pi / 2 + i * math.pi / 4
            r = 31 if i % 2 == 0 else 12
            pts.append((cx + r * math.cos(a) * 0.72, cy + r * math.sin(a) * 0.72 + 4))
        d.polygon(pts, fill=col)
    elif index == 7:
        d.ellipse((cx - 27, cy - 27, cx + 27, cy + 27), fill=col)
        d.ellipse((cx - 15, cy - 33, cx + 39, cy + 21), fill=(8, 8, 9, 255))
    elif index == 8:
        d.polygon([(cx, cy - 31), (cx + 30, cy), (cx, cy + 31), (cx - 30, cy)],
                  outline=col, width=5)
    elif index == 9:
        d.ellipse((cx - 28, cy - 28, cx + 28, cy + 28), outline=col, width=4)
        d.polygon([(cx - 10, cy - 14), (cx + 16, cy), (cx - 10, cy + 14)], fill=col)
    else:
        d.ellipse((cx - 12, cy - 12, cx + 12, cy + 12), outline=col, width=4)
        d.ellipse((cx - 31, cy - 15, cx + 31, cy + 15), outline=col, width=3)
        d.ellipse((cx - 4, cy - 4, cx + 4, cy + 4), fill=col)


def render_make_it_yours(canvas: Image.Image, local_t: float):
    draw = ImageDraw.Draw(canvas)
    float_y = int(6 * math.sin(local_t * 1.1))
    x0, y0, x1, y1 = 92, 560 + float_y, 988, 1580 + float_y
    draw.rounded_rectangle((x0, y0, x1, y1), 54, fill=(13, 13, 15, 255),
                           outline=(255, 255, 255, 26), width=2)
    label = ImageFont.truetype(FONT_BOLD, 22)
    tracked(draw, (x0 + 40, y0 + 34), "ТЕМЫ", label, (150, 150, 158, 255), 6)

    themes = [
        ("Graphite", (10, 10, 11), (20, 20, 22), (154, 164, 178), (242, 242, 244)),
        ("Paper", (247, 245, 240), (255, 255, 255), (47, 107, 255), (20, 20, 22)),
        ("Midnight", (5, 10, 24), (11, 19, 38), (111, 168, 255), (234, 240, 255)),
        ("Sakura", (255, 246, 248), (255, 255, 255), (214, 91, 133), (35, 26, 30)),
    ]
    name_font = ImageFont.truetype(FONT_BOLD, 24)
    for i, (name, bg, surface, accent, text) in enumerate(themes):
        col = i % 2
        row = i // 2
        cx = x0 + 40 + col * 418
        cy = y0 + 80 + row * 200
        draw.rounded_rectangle((cx, cy, cx + 390, cy + 176), 26, fill=bg,
                               outline=(255, 255, 255, 24), width=2)
        draw.rounded_rectangle((cx + 22, cy + 78, cx + 368, cy + 150), 16, fill=surface)
        draw.ellipse((cx + 40, cy + 100, cx + 70, cy + 130), fill=accent)
        draw.text((cx + 86, cy + 98), name, font=name_font, fill=text)
        draw.text((cx + 24, cy + 32), "ONYX", font=label, fill=(*text, 190) if len(text) == 3 else text)

    grid_top = y0 + 500
    for i in range(10):
        col = i % 5
        row = i // 5
        cx = x0 + 52 + col * 172
        cy = grid_top + row * 168
        draw.rounded_rectangle((cx, cy, cx + 128, cy + 128), 30,
                               fill=(8, 8, 9, 255), outline=(255, 255, 255, 26), width=2)
        draw_icon_glyph(draw, cx + 64, cy + 64, i + 1)

    footer = ImageFont.truetype(FONT_REG, 24)
    text = "10 ИКОНОК · 10 ШРИФТОВ · JSON-ТЕМЫ"
    tw = tracked_width(draw, text, footer, 4)
    tracked(draw, ((x0 + x1 - tw) / 2, y1 - 92), text, footer,
            (150, 150, 158, 255), 4)
    return canvas


def render_scene(src: Path, index: int, local_t: float, total_local: float):
    canvas = background.copy().convert("RGBA")
    file, kicker, lines = SCENES[index]
    path = src / file
    draw = ImageDraw.Draw(canvas)

    intro_in = ease(min(1.0, local_t / 0.55))
    kick_font = ImageFont.truetype(FONT_BOLD, 30)
    title_font = ImageFont.truetype(FONT_BOLD, 76)
    sub_font = ImageFont.truetype(FONT_REG, 36)

    alpha = int(255 * intro_in)
    kw = tracked_width(draw, kicker, kick_font, 9)
    tracked(draw, ((W - kw) / 2, 156), kicker, kick_font, (150, 150, 158, alpha), 9)

    for i, line in enumerate(lines):
        size = 76 if i == 0 else 64
        f = ImageFont.truetype(FONT_BOLD if i == 0 else FONT_REG, size)
        tw = draw.textlength(line, font=f)
        draw.text(((W - tw) / 2, 216 + i * 88), line, font=f,
                  fill=(246, 246, 247, int(alpha * (0.55 if i else 1))))

    if index == 5:
        return render_make_it_yours(canvas, local_t)

    if path.exists():
        zoom = 1.0 + 0.045 * ease(local_t / max(total_local, 0.01))
        card = phone_card(Image.open(path), 1280, zoom)
        x = (W - card.width) // 2
        y = int(560 - 10 * ease(local_t / max(total_local, 0.01)))
        draw_shadow(canvas, (x, y, x + card.width, y + card.height))
        canvas.alpha_composite(card, (x, y))
    return canvas


def render_intro(t: float):
    canvas = background.copy().convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    p = ease(min(1.0, t / 1.1))
    alpha = int(255 * p)
    big = ImageFont.truetype(FONT_BOLD, 150)
    text = "ONYX"
    w = tracked_width(draw, text, big, 26)
    tracked(draw, ((W - w) / 2, H / 2 - 175), text, big, (246, 246, 247, alpha), 26)
    small = ImageFont.truetype(FONT_REG, 34)
    sub = "MUSIC CONTROL CENTER"
    sw = tracked_width(draw, sub, small, 12)
    a2 = int(255 * ease(max(0.0, min(1.0, (t - 0.55) / 0.9))))
    tracked(draw, ((W - sw) / 2, H / 2 + 40), sub, small, (150, 150, 158, a2), 12)
    return canvas


def render_outro(t: float):
    canvas = background.copy().convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    p = ease(min(1.0, t / 0.8))
    alpha = int(255 * p)
    big = ImageFont.truetype(FONT_BOLD, 120)
    text = "ONYX"
    w = tracked_width(draw, text, big, 20)
    tracked(draw, ((W - w) / 2, H / 2 - 190), text, big, (246, 246, 247, alpha), 20)
    ver = ImageFont.truetype(FONT_LIGHT, 54)
    v = "0.6"
    draw.text(((W - draw.textlength(v, font=ver)) / 2, H / 2 - 26), v, font=ver,
              fill=(240, 240, 242, alpha))
    sub = ImageFont.truetype(FONT_REG, 33)
    s = "music control center"
    sw = draw.textlength(s, font=sub)
    draw.text(((W - sw) / 2, H / 2 + 48), s, font=sub, fill=(150, 150, 158, alpha))

    link = ImageFont.truetype(FONT_BOLD, 30)
    l = "github.com/ysl021/onyx"
    lw = draw.textlength(l, font=link)
    a2 = int(255 * ease(max(0.0, min(1.0, (t - 0.7) / 0.7))))
    draw.rounded_rectangle(
        ((W - lw) / 2 - 34, H / 2 + 190, (W + lw) / 2 + 34, H / 2 + 258),
        34, outline=(255, 255, 255, 40), width=2,
    )
    draw.text(((W - lw) / 2, H / 2 + 207), l, font=link, fill=(228, 228, 232, a2))
    return canvas


def make_audio(path: Path, duration: float):
    rate = 44100
    n = int(rate * duration)
    samples = []
    rnd = random.Random(7)
    for i in range(n):
        t = i / rate
        env = min(1.0, t / 1.2) * min(1.0, max(0.0, (duration - t) / 1.2))
        pad = (
            0.16 * math.sin(2 * math.pi * 110 * t + 0.2 * math.sin(2 * math.pi * 0.13 * t))
            + 0.10 * math.sin(2 * math.pi * 164.81 * t + 0.2 * math.sin(2 * math.pi * 0.11 * t + 1))
            + 0.09 * math.sin(2 * math.pi * 220 * t + 0.2 * math.sin(2 * math.pi * 0.09 * t + 2))
            + 0.055 * math.sin(2 * math.pi * 329.63 * t + 0.2 * math.sin(2 * math.pi * 0.07 * t + 3))
        )
        pad *= 0.55 + 0.45 * (0.5 + 0.5 * math.sin(2 * math.pi * 0.09 * t))
        kick = 0.0
        if t > 2.4:
            tau = (t - 2.4) % 0.8
            kick = 0.30 * math.sin(2 * math.pi * 54 * tau) * math.exp(-tau * 13)
        hat = 0.0
        tau2 = t % 0.4
        if t > 2.4:
            hat = (rnd.random() * 2 - 1) * 0.025 * math.exp(-tau2 * 55)
        swoosh = 0.0
        for boundary in [INTRO + k * SCENE for k in range(1, len(SCENES) + 1)]:
            dtb = t - boundary
            if -0.45 < dtb < 0.25:
                x = (dtb + 0.45) / 0.7
                swoosh += (rnd.random() * 2 - 1) * 0.05 * math.sin(math.pi * x) ** 3
        v = (pad + kick + hat + swoosh) * env
        v = max(-1.0, min(1.0, v * 1.25))
        samples.append(int(v * 32767))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(int(s).to_bytes(2, "little", signed=True) for s in samples))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", default="promo/onyx-teaser.mp4")
    args = ap.parse_args()
    src = Path(args.src)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    global background
    background = make_background()

    duration = INTRO + SCENE * len(SCENES) + OUTRO
    wav = out.with_suffix(".wav")
    make_audio(wav, duration)

    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [
        ffmpeg, "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
        "-i", "-",
        "-i", str(wav),
        "-c:v", "libx264", "-preset", "medium", "-crf", "18",
        "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        "-c:a", "aac", "-b:a", "160k", "-shortest",
        str(out),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)

    total_frames = int(duration * FPS)
    prev = None
    for frame_index in range(total_frames):
        t = frame_index / FPS
        if t < INTRO:
            frame = render_intro(t)
            local = t
        elif t < INTRO + SCENE * len(SCENES):
            st = t - INTRO
            idx = min(len(SCENES) - 1, int(st // SCENE))
            local = st - idx * SCENE
            frame = render_scene(src, idx, local, SCENE)
            # fade out/in around scene changes
            edge = min(local, SCENE - local)
            if edge < FADE / 2:
                k = edge / (FADE / 2)
                frame = Image.blend(Image.new("RGBA", frame.size, (5, 5, 6, 255)), frame, k)
        else:
            frame = render_outro(t - INTRO - SCENE * len(SCENES))

        if prev is not None:
            # short crossfade for extra smoothness
            frame = Image.blend(prev, frame, 0.55)
        prev = frame.copy()
        proc.stdin.write(frame.convert("RGB").tobytes())

    proc.stdin.close()
    proc.wait()
    wav.unlink(missing_ok=True)
    print(out, out.stat().st_size // 1024 // 1024, "MB")


if __name__ == "__main__":
    main()
