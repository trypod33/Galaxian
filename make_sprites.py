from PIL import Image, ImageDraw

SC = 8  # supersample factor for crisp pixel art

def grid_to_img(rows, cmap, scale=SC):
    h, w = len(rows), len(rows[0])
    img = Image.new("RGBA", (w*scale, h*scale), (0,0,0,0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '.': continue
            c = cmap[ch]
            d.rectangle([x*scale, y*scale, x*scale+scale-1, y*scale+scale-1], fill=c)
    return img

ALIEN_F0 = [
"..A..........A..",
"..A..........A..",
"....B......B....",
"..WWBBBBBBBBWW..",
".WWWBEBBBBEBWWW.",
"WWWWBBBBBBBBWWWW",
"WWWBBBBBBBBBBWWW",
"WWW.BBBBBBBB.WWW",
".WW..BBBBBB..WW.",
"..W..BBBBBB..W..",
".....BB..BB.....",
".....B....B.....",
]
ALIEN_F1 = [
"................",
"..A..........A..",
"...B........B...",
"...WWBBBBBBWW...",
"...WWBEBBEBWW...",
"..WWWBBBBBBWWW..",
"..WWBBBBBBBBWW..",
"..WW.BBBBBB.WW..",
"...W..BBBB..W...",
"......B..B......",
"................",
"................",
]

FLAG_F0 = [
"...A..........A...",
"...A..........A...",
".....B......B.....",
"..YYYBBBBBBBYYY...",
".YYYYBEBBBBEBYYYY.",
"YYYYYBBBBBBBBYYYYY",
"YYYBBBBBBBBBBBYYY.",
"YY..BBBBBBBB..YY..",
".W...BBBBBB...W...",
".W...BB..BB...W...",
".....B....B.......",
"..................",
]
FLAG_F1 = [
"..................",
"...A..........A...",
".....B......B.....",
"....YBBBBBBBY.....",
"....YYBEBBEYY.....",
"...YYBBBBBBYY.....",
"...YBBBBBBBBY.....",
"..YY.BBBBBB.YY....",
"..W...BBBB...W....",
"..W...BB..BB.W....",
"......B..B........",
"..................",
]

SHIP = [
".......W.......",
"......WWW......",
"......WCW......",
".....WWCWW.....",
".....WWWWW.....",
"....WWWWWWW....",
"...RWWWWWWWR...",
"..RRWWWWWWWRR..",
".RRR.WWWWW.RRR.",
".RR..WWWWW..RR.",
".R...WWWWW...R.",
"....RRRRRRR....",
]

EXPL0 = [
"......Y......",
"..Y..OYO..Y..",
"...YOOOOOY...",
"..YOOOWWOOY..",
"YYOOWWWWWOOYY",
"YOOOWWWWWOOOY",
".YOOOWWWOOOY.",
"YOOOOWWOOOOY.",
"YYOOOOOOOOYY.",
".Y.YOOOOY.Y..",
"....Y..Y.....",
]
EXPL1 = [
".............",
"...O..Y..O...",
"..Y.OOOOO.Y..",
"O..OOWWWOO..O",
".YOWWOWWOWOY.",
"OOWWOO.OOWWOO",
".YOWWOWWOWOY.",
"O..OOWWWOO..O",
"..Y.OOOOO.Y..",
"...O..Y..O...",
".............",
]
EXPL2 = [
".............",
"....Y...Y....",
"..Y..O.O..Y..",
"....OWOWO....",
".Y.OWOOOW O.Y".replace(" ",""),
"....OWOWO....",
"..Y..O.O..Y..",
"....Y...Y....",
".............",
]

def make(colors, fname, rows):
    img = grid_to_img(rows, colors)
    img.save(fname)
    print(fname, img.size)

alien_cmap = lambda body, wing: {"B": body, "W": wing, "E": (20,20,30,255), "A": wing}
blue = (70,150,255,255); red = (240,70,70,255); purple = (200,90,255,255)
wing_w = (230,240,255,255)
yellow = (255,220,60,255); flag_body = (245,245,255,255)

D = "app/src/main/res/drawable-nodpi/"
make(alien_cmap(blue, wing_w), D+"spr_blue_0.png", ALIEN_F0)
make(alien_cmap(blue, wing_w), D+"spr_blue_1.png", ALIEN_F1)
make(alien_cmap(red, wing_w), D+"spr_red_0.png", ALIEN_F0)
make(alien_cmap(red, wing_w), D+"spr_red_1.png", ALIEN_F1)
make(alien_cmap(purple, wing_w), D+"spr_purple_0.png", ALIEN_F0)
make(alien_cmap(purple, wing_w), D+"spr_purple_1.png", ALIEN_F1)

flag_cmap = {"B": flag_body, "W": (255,90,90,255), "E": (20,20,30,255), "A": yellow, "Y": yellow}
make(flag_cmap, D+"spr_flag_0.png", FLAG_F0)
make(flag_cmap, D+"spr_flag_1.png", FLAG_F1)

ship_cmap = {"W": (240,240,255,255), "C": (90,180,255,255), "R": (255,60,60,255)}
make(ship_cmap, D+"spr_ship.png", SHIP)

expl_cmap = {"Y": yellow, "O": (255,120,30,255), "W": (255,255,240,255)}
make(expl_cmap, D+"spr_expl_0.png", EXPL0)
make(expl_cmap, D+"spr_expl_1.png", EXPL1)
make(expl_cmap, D+"spr_expl_2.png", EXPL2)

# icon
icon = Image.new("RGBA", (432, 432), (0,0,0,255))
d = ImageDraw.Draw(icon)
import random
random.seed(7)
for _ in range(90):
    x, y = random.randint(0,431), random.randint(0,431)
    c = random.choice([(255,255,255),(255,90,90),(90,200,255),(255,230,90),(140,120,255)])
    d.point((x,y), fill=c)
flag_big = grid_to_img(FLAG_F0, flag_cmap).resize((252, 168), Image.NEAREST)
icon.paste(flag_big, (90, 110), flag_big)
d.text((216, 320), "GALAXIAN", fill=(255,255,255), anchor="mm")
icon.save("app/src/main/res/drawable/ic_game.png")
print("icon done")

# preview sheet
prev = Image.new("RGBA", (900, 520), (8, 6, 20, 255))
d = ImageDraw.Draw(prev)
for _ in range(140):
    x, y = random.randint(0,899), random.randint(0,519)
    c = random.choice([(255,255,255),(255,90,90),(90,200,255),(255,230,90),(140,120,255)])
    d.point((x,y), fill=c)
def paste_scaled(name, box):
    im = Image.open(D+name)
    w, h = im.size
    s = 4
    im2 = im.resize((w*s//SC, h*s//SC), Image.NEAREST) if False else im.resize((im.width//SC*3, im.height//SC*3), Image.NEAREST)
    prev.paste(im2, box, im2)
x = 40
for nm in ["spr_blue_0.png","spr_blue_1.png","spr_red_0.png","spr_red_1.png","spr_purple_0.png","spr_purple_1.png"]:
    paste_scaled(nm, (x, 40)); x += 80
paste_scaled("spr_flag_0.png", (560, 40))
paste_scaled("spr_flag_1.png", (650, 40))
paste_scaled("spr_ship.png", (60, 220))
paste_scaled("spr_expl_0.png", (180, 220))
paste_scaled("spr_expl_1.png", (300, 220))
paste_scaled("spr_expl_2.png", (420, 220))
prev.save("preview_sprites.png")
print("preview done")
