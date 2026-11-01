#!/usr/bin/env python3
"""Builds the Pac-Man theme's two images from the original sprites in art/pacman/.

  pacman_sprites.png  16x16 cells: Pac-Man frames, ghosts, frightened ghosts, eyes, cherries
  pacman_maze.png     the 224x248 level-1 maze walls, blue on transparent

Run from anywhere; it writes into app/src/main/res/drawable-nodpi/. Needs Pillow and numpy.
Cell order is up, left, down, right everywhere (the game's direction order and tie-break order).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter, ImageSequence

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "..", "art", "pacman")
RES = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")

# The 28x31 level-1 maze: # wall, . dot, o energizer, - ghost house door, space empty.
MAZE = [
    "############################",
    "#............##............#",
    "#.####.#####.##.#####.####.#",
    "#o####.#####.##.#####.####o#",
    "#.####.#####.##.#####.####.#",
    "#..........................#",
    "#.####.##.########.##.####.#",
    "#.####.##.########.##.####.#",
    "#......##....##....##......#",
    "######.##### ## #####.######",
    "     #.##### ## #####.#     ",
    "     #.##          ##.#     ",
    "     #.## ###--### ##.#     ",
    "######.## #      # ##.######",
    "      .   #      #   .      ",
    "######.## #      # ##.######",
    "     #.## ######## ##.#     ",
    "     #.##          ##.#     ",
    "     #.## ######## ##.#     ",
    "######.## ######## ##.######",
    "#............##............#",
    "#.####.#####.##.#####.####.#",
    "#.####.#####.##.#####.####.#",
    "#o..##.......  .......##..o#",
    "###.##.##.########.##.##.###",
    "###.##.##.########.##.##.###",
    "#......##....##....##......#",
    "#.##########.##.##########.#",
    "#.##########.##.##########.#",
    "#..........................#",
    "############################",
]
COLS, ROWS = 28, 31

WALL_BLUE = (33, 33, 255)
DOOR_PINK = (255, 184, 255)
DOT_PEACH = (255, 184, 174)
FRIGHT_BODY = (33, 33, 255)
FRIGHT_FACE = (255, 184, 174)
FLASH_BODY = (222, 222, 255)
FLASH_FACE = (255, 0, 0)
EYE_WHITE = (222, 222, 255)
EYE_PUPIL = (33, 33, 255)
BODY_COLORS = {  # sampled from the supplied sprites
    "blinky": (255, 0, 0),
    "pinky": (255, 183, 255),
    "inky": (0, 255, 255),
    "clyde": (255, 183, 81),
}
UP, LEFT, DOWN, RIGHT = range(4)


def check_maze():
    assert all(len(r) == COLS for r in MAZE) and len(MAZE) == ROWS
    dots = sum(r.count(".") for r in MAZE)
    pills = sum(r.count("o") for r in MAZE)
    assert (dots, pills) == (240, 4), (dots, pills)


def frames(name):
    im = Image.open(os.path.join(ART, name))
    return [f.convert("RGBA").copy() for f in ImageSequence.Iterator(im)]


def cell():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def pacman_cells():
    """Closed, medium, wide for up/left/down/right, from the right-facing 16x16 chomp frames."""
    src = frames("Pac-chomp.gif")  # frame 0 medium, 1 closed, 2 medium, 3 wide
    out = []
    for d in (UP, LEFT, DOWN, RIGHT):
        for idx in (1, 0, 3):
            body = src[idx].crop((1, 1, 14, 14))  # the 13x13 body, centred on pixel (6, 6)
            if d == LEFT:
                body = body.transpose(Image.FLIP_LEFT_RIGHT)
            elif d == UP:
                body = body.rotate(90)
            elif d == DOWN:
                body = body.rotate(-90)
            c = cell()
            c.paste(body, (1, 1))
            out.append(c)
    return out


def ghost_body(frame, color, d):
    """Recoloured, direction-specific 14x14 ghost from a right-looking template frame."""
    px = np.array(frame, dtype=np.uint8)
    rgb = px[..., :3]
    alpha = px[..., 3]
    white = np.all(rgb == EYE_WHITE, axis=-1) & (alpha > 0)
    pupil = np.all(rgb == EYE_PUPIL, axis=-1) & (alpha > 0)
    body = (alpha > 0) & ~white & ~pupil
    img = np.zeros_like(px)
    img[body] = (*color, 255)
    eye_area = white | pupil
    img[eye_area] = (*EYE_WHITE, 255)
    for ox in (0, 6):  # left eye, right eye
        if d == RIGHT:
            cols, rows = (5, 6), (5, 6)
        elif d == LEFT:
            cols, rows = (3, 4), (5, 6)
        elif d == UP:
            cols, rows = (4, 5), (3, 4)
        else:
            cols, rows = (4, 5), (6, 7)
        for r in rows:
            for c in cols:
                img[r, c + ox] = (*EYE_PUPIL, 255)
    # the template's own pupils may sit outside the new 2x2 spot: they were reset to white above
    return Image.fromarray(img)


def ghost_cells(templates):
    out = []
    for name in ("blinky", "pinky", "inky", "clyde"):
        for d in (UP, LEFT, DOWN, RIGHT):
            for t in templates:
                c = cell()
                c.paste(ghost_body(t, BODY_COLORS[name], d), (1, 1))
                out.append(c)
    return out


def frightened_cells(templates):
    out = []
    for body_color, face_color in ((FRIGHT_BODY, FRIGHT_FACE), (FLASH_BODY, FLASH_FACE)):
        for t in templates:
            px = np.array(t, dtype=np.uint8)
            img = np.zeros_like(px)
            img[px[..., 3] > 0] = (*body_color, 255)
            for r in (5, 6):
                for c in (4, 5, 8, 9):
                    img[r, c] = (*face_color, 255)
            for c in (2, 3, 6, 7, 10, 11):
                img[9, c] = (*face_color, 255)
            for c in (1, 4, 5, 8, 9, 12):
                img[10, c] = (*face_color, 255)
            c16 = cell()
            c16.paste(Image.fromarray(img), (1, 1))
            out.append(c16)
    return out


def eyes_cells(template):
    out = []
    for d in (UP, LEFT, DOWN, RIGHT):
        g = np.array(ghost_body(template, (0, 0, 0), d))
        eyes = np.zeros_like(g)
        keep = np.all(g[..., :3] == EYE_WHITE, axis=-1) | np.all(g[..., :3] == EYE_PUPIL, axis=-1)
        eyes[keep] = g[keep]
        c = cell()
        c.paste(Image.fromarray(eyes), (1, 1))
        out.append(c)
    return out


CHERRY = [
    "..........SSS.",
    "........SSS..S",
    ".......S..S..S",
    "......S....S.S",
    ".....S.....SS.",
    "..RRRR....RRR.",
    ".RRWRRR..RRWRR",
    ".RWRRRR..RWRRR",
    ".RRRRRR..RRRRR",
    ".RRRRRR..RRRRR",
    "..RRRR....RRR.",
    "..............",
    "..............",
    "..............",
]
CHERRY_COLORS = {"S": (222, 151, 81), "R": (255, 0, 0), "W": (255, 255, 255)}


def cherry_cell():
    c = cell()
    for y, row in enumerate(CHERRY):
        for x, ch in enumerate(row):
            if ch in CHERRY_COLORS:
                c.putpixel((x + 1, y + 1), (*CHERRY_COLORS[ch], 255))
    return c


def build_sprites():
    blinky = frames("Blinkysprite.gif")
    assert len(blinky) == 2
    cells = pacman_cells()                       # 0..11
    cells += ghost_cells(blinky)                 # 12..43: ghost*8 + dir*2 + foot
    cells += frightened_cells(blinky)            # 44..47: blue a, blue b, white a, white b
    cells += eyes_cells(blinky[0])               # 48..51
    cells.append(cherry_cell())                  # 52
    per_row = 16
    rows = (len(cells) + per_row - 1) // per_row
    sheet = Image.new("RGBA", (per_row * 16, rows * 16), (0, 0, 0, 0))
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % per_row) * 16, (i // per_row) * 16))
    return sheet, len(cells)


def wall_mask():
    """Hi-res wall mask; the tunnel rows run on past the screen edge so their walls stay open-ended."""
    k = 8                      # supersampling per native pixel
    t = 8 * k                  # tile size in mask pixels
    pad = 2                    # tiles of padding on the left and right
    m = np.zeros((ROWS * t, (COLS + 2 * pad) * t), dtype=np.uint8)
    for y, row in enumerate(MAZE):
        for x in range(-pad, COLS + pad):
            src = min(max(x, 0), COLS - 1)
            outside = x < 0 or x >= COLS
            if outside and not (13 <= y <= 15):
                continue
            if row[src] == "#":
                m[y * t:(y + 1) * t, (x + pad) * t:(x + pad + 1) * t] = 255
    return m, k, t, pad


def build_maze():
    mask, k, t, pad = wall_mask()
    img = Image.fromarray(mask)
    sigma = 2.0 * k
    blur = np.array(img.filter(ImageFilter.GaussianBlur(sigma)), dtype=np.float32) / 255
    inset0, inset1 = 1.5 * k, 3.0 * k
    from math import erf, sqrt
    phi = lambda z: 0.5 * (1 + erf(z / sqrt(2)))
    lo, hi = phi(inset0 / sigma), phi(inset1 / sigma)
    ring = (blur >= lo) & (blur < hi) & (mask > 0)
    ring = ring[:, pad * t:(pad + COLS) * t]
    native = Image.fromarray((ring * 255).astype(np.uint8)).resize((COLS * 8, ROWS * 8), Image.BOX)
    lines = np.array(native) >= 110
    out = np.zeros((ROWS * 8, COLS * 8, 4), dtype=np.uint8)
    out[lines] = (*WALL_BLUE, 255)
    # the pink door, 16x2 px across the middle of the door tiles
    for y in (12 * 8 + 3, 12 * 8 + 4):
        for x in range(13 * 8, 15 * 8):
            out[y, x] = (*DOOR_PINK, 255)
    return Image.fromarray(out)


def preview(maze, sheet, path):
    bg = Image.new("RGBA", (28 * 8, 36 * 8), (0, 0, 0, 255))
    bg.alpha_composite(maze, (0, 24))
    px = bg.load()
    for y, row in enumerate(MAZE):
        for x, ch in enumerate(row):
            if ch == ".":
                for dy in (3, 4):
                    for dx in (3, 4):
                        px[x * 8 + dx, 24 + y * 8 + dy] = (*DOT_PEACH, 255)
            elif ch == "o":
                for dy in range(1, 7):
                    for dx in range(1, 7):
                        if (dx - 3.5) ** 2 + (dy - 3.5) ** 2 <= 9.5:
                            px[x * 8 + dx, 24 + y * 8 + dy] = (*DOT_PEACH, 255)

    def sprite(i, cx, cy):
        c = sheet.crop(((i % 16) * 16, (i // 16) * 16, (i % 16) * 16 + 16, (i // 16) * 16 + 16))
        bg.alpha_composite(c, (cx - 8, 24 + cy - 8))

    sprite(11 - 0, 112, 188)       # pac-man facing right, closed -> index 9..11 is right; 10 = medium
    sprite(10, 112, 188)
    for g, (x, y, d) in enumerate([(112, 92, LEFT), (112, 116, DOWN), (96, 116, UP), (128, 116, UP)]):
        sprite(12 + g * 8 + d * 2, x, y)
    sprite(44, 30, 30 - 24)
    sprite(46, 60, 30 - 24)
    sprite(48, 90, 30 - 24)
    sprite(52, 112, 140)
    bg.resize((bg.width * 4, bg.height * 4), Image.NEAREST).save(path)


def main():
    check_maze()
    sheet, n = build_sprites()
    maze = build_maze()
    os.makedirs(RES, exist_ok=True)
    sheet.save(os.path.join(RES, "pacman_sprites.png"))
    maze.save(os.path.join(RES, "pacman_maze.png"))
    print(f"wrote pacman_sprites.png ({n} cells, {sheet.size}) and pacman_maze.png {maze.size}")
    if len(sys.argv) > 1:
        preview(maze, sheet, sys.argv[1])
        print("preview", sys.argv[1])


if __name__ == "__main__":
    main()
