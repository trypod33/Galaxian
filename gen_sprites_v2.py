from PIL import Image, ImageDraw

S = 8  # supersample

# char -> (r,g,b)
def pal_for(m):
    return {
        'G': (0, 200, 60),   # boss green
        'g': (0, 130, 40),   # boss green dark
        'P': (180, 60, 220), # boss damaged purple
        'p': (110, 30, 140),
        'B': (60, 120, 255), # bee blue
        'b': (30, 70, 180),
        'W': (236, 236, 236),# white
        'w': (150, 150, 160),
        'R': (220, 40, 40),  # butterfly red
        'r': (150, 20, 30),
        'Y': (250, 200, 40), # yellow
        'y': (220, 140, 30), # orange
        '.': None,
    }[m]

def grid(name, rows):
    rows = [r if len(r) == 16 else r.ljust(16, '.') for r in rows]
    img = Image.new("RGBA", (16*S, 16*S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '.': continue
            c = pal_for(ch)
            if c is None: continue
            d.rectangle([x*S, y*S, (x+1)*S-1, (y+1)*S-1], fill=c+(255,))
    img = img.resize((64, 64), Image.NEAREST)
    img.save(f"app/src/main/res/drawable-nodpi/{name}.png")
    return img

# ---- BOSS GALAGA: green dome, blue wing pods, yellow mandibles ----
boss0 = [
"....G......G....",
"...GGY....YGG...",
"..GGYY....YYGG..",
"..GYYGGGGGGYYG..",
".BGGGGYGGYGGGGB.",
".BGGGGGGGGGGGGB.",
".BBGGWGGGGWGGBB.",
"..BBGGGGGGGGBB..",
"..BBGGGGGGGGBB..",
"...BBGGGGGGBB...",
"....BBGGGGBB....",
".....BGGGGB.....",
".....YY..YY.....",
"....YY....YY....",
"....Y......Y....",
"................",
]
boss1 = [
"....G......G....",
"....GY....YG....",
"....YY....YY....",
"....GGGGGGGG....",
"....GGYGGYGG....",
"...BBGGGGGGBB...",
"..BBGGWGGWGBB...",
"..BBGGGGGGGBB...",
"..BBGGGGGGGBB...",
"...BBGGGGGBB....",
"....BBGGGBB.....",
".....BGGGB......",
".....YY.YY......",
"....YY...YY.....",
"....Y.....Y.....",
"................",
]
# damaged boss: purple over green bones
bossh0 = [
"....P......P....",
"...PPY....YPP...",
"..PPYY....YYPP..",
"..PYYPPPPPPYYP..",
".BPPPPYPPYPPP B.".replace(" ", "B"),
".BPPPPPPPPPPPPB.",
".BBPPWPPPPWPBB..",
"..BBPPPPPPPBB...",
"..BBPPPPPPPBB...",
"...BBPPPPPBB....",
"....BBPPPBB.....",
".....BPPPB......",
".....YY..YY.....",
"....YY....YY....",
"....Y......Y....",
"................",
]
bossh1 = [
"....P......P....",
"....PY....YP....",
"....YY....YY....",
"....PPPPPPPP....",
"....PPYPPYPP....",
"...BBPPPPPBB....",
"..BBPPWPPWPBB...",
"..BBPPPPPBB.....",
"..BBPPPPPBB.....",
"...BBPPPBB......",
"....BBPPB.......",
".....BPPB.......",
".....YY.YY......",
"....YY...YY.....",
"....Y.....Y.....",
"................",
]

# ---- ZAKO BEE: blue body, white swept wings, yellow feet ----
bee0 = [
"..WW........WW..",
".WWWY......YWWW.",
".WWWY......YWWW.",
"..WWYBBBBBBYWW..",
"..WWBBBBBBBBWW..",
"...WBBBBBBBBW...",
".....BBYYBB.....",
".....BYYYYB.....",
".....BBYYBB.....",
"....BBBBBBBB....",
"....BBwWWwBB....",
".....BB..BB.....",
"....YY....YY....",
"....Y......Y....",
"................",
"................",
]
bee1 = [
"................",
"................",
"..WW........WW..",
"..WWYBBBBBBYWW..",
".WWWBBBBBBBBWWW.",
".WWWBBBBBBBBWWW.",
"..WWBBYYBBWW....",
"....BBYYBB......",
"....BBYYBB......",
"....BBBBBBBB....",
"....BBwWWwBB....",
".....BB..BB.....",
"....YY..YY......",
"....Y....Y......",
"................",
"................",
]

# ---- GOEI BUTTERFLY: red moth, white-edged swept wings, blue accents ----
red0 = [
"................",
"....R......R....",
"...RRR....RRR...",
"...RRRR..RRRR...",
"..RRRRRRRRRRRR..",
".WWRRRRRRRRRRWW.",
"WRRRRRRRRRRRRRRW",
"WWRRRRRRRRRRRRWW",
".WRRRRRRRRRRRRW.",
"..RRWWRRRRWWRR..",
"...RRRRRRRRRR...",
"....RRRRRRRR....",
".....RR..RR.....",
"....RR....RR....",
"....R......R....",
"................",
]
red1 = [
"................",
"................",
"....R......R....",
"....RR....RR....",
"....RRRRRRRR....",
"..WWRRRRRRRRWW..",
".WWRRRRRRRRRRWW.",
".WRRRRRRRRRRRRW.",
"..RRWWRRRRWWRR..",
"..RRRRRRRRRRRR..",
"...RRRRRRRRRR...",
"....RRRRRRRR....",
".....RR..RR.....",
"....RR....RR....",
"....R......R....",
"................",
]

# ---- FIGHTER: white arrow, red nose, blue cockpit ----
ship0 = [
".......W.......",
"......WWW......",
"......WRW......",
".....WWWWW.....",
".....WWRWW.....",
".....WWWWW.....",
"....WWWWWWW....",
"....WWBBBWW....",
"...WWWWWWWWW...",
"..WWWWWWWWWWW..",
"..WWW.WWW.WWW..",
".WWWWWWWWWWWWW.",
".WWW.WWWWW.WWW.",
".WW..WWWWW..WW.",
".W....W.W....W.",
"................",
]
ship1 = ship0

# captured fighter flies red
def redify(rows):
    out = []
    for row in rows:
        out.append("".join({'W':'R','w':'r'}.get(c, c) for c in row))
    return out
shipred0 = redify(ship0)
shipred1 = redify(ship1)

# ---- EXPLOSIONS: 3 frames, radial ----
exp0 = [
"................",
"................",
".....W....W.....",
"....W......W....",
"..........W.....",
"....Y......Y....",
".....Y.WW.Y.....",
"......YWWY......",
"......YWWY......",
".....Y.WW.Y.....",
"....Y......Y....",
".........W......",
"....W......W....",
".....W....W.....",
"................",
"................",
]
exp1 = [
"................",
"..W.........W...",
"...Y.......Y....",
"....Y.....Y.....",
".....R...R......",
".....RR.RR......",
"....RRWWWRR.....",
"....RWWWWWR.....",
"....RWWWWWR.....",
"....RRWWWRR.....",
".....RR.RR......",
".....R...R......",
"....Y.....Y.....",
"...Y.......Y....",
"..W.........W...",
"................",
]
exp2 = [
"................",
".W.....W....W...",
"..Y...RRR..Y....",
"...R.RYYYYR.R...",
"....RRYWWYRR....",
"...RYYWWWWYR....",
"..RYYW....WYYR..",
"..RYW......WYR..",
"..RYW......WYR..",
"..RYYW....WYYR..",
"...RYYWWWWYR....",
"....RRYWWYRR....",
"...R.RYYYYR.R...",
"..Y...RRR..Y....",
".W.....W....W...",
"................",
]

made = 0
for name, rows in [
    ("gal_boss0", boss0), ("gal_boss1", boss1),
    ("gal_bossh0", bossh0), ("gal_bossh1", bossh1),
    ("gal_bee0", bee0), ("gal_bee1", bee1),
    ("gal_red0", red0), ("gal_red1", red1),
    ("gal_ship0", ship0), ("gal_ship1", ship1),
    ("gal_shipred0", shipred0), ("gal_shipred1", shipred1),
    ("gal_exp0", exp0), ("gal_exp1", exp1), ("gal_exp2", exp2),
]:
    grid(name, rows); made += 1
print("sprites ok:", made)
