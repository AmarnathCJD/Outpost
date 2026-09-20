"""Export Outpost's supplied geometric mark. Requires Pillow (pip install Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / 'assets/branding'
RES = ROOT / 'app/src/main/res'
BLUE = '#2F97FC'
DARK = '#181818'
# Clean trace of the owner's supplied artwork; the original checkerboard was opaque.
OUTER = [(0, 0), (180, 0), (180, 180), (108, 180), (108, 138), (72, 138), (72, 180), (0, 180)]
INNER = [(33, 33), (33, 147), (60, 147), (60, 126), (120, 126), (120, 147), (147, 147), (147, 33)]
PATH = 'M0,0H180V180H108V138H72V180H0Z M33,33V147H60V126H120V147H147V33Z'


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8', newline='\n')


def raster(size, tile=False):
    side = size * 4
    image = Image.new('RGBA', (side, side))
    if tile:
        ImageDraw.Draw(image).rounded_rectangle((0, 0, side - 1, side - 1), radius=side * .22, fill=DARK)
    inset = side * (.18 if tile else .10)
    scale = (side - inset * 2) / 180
    mask = Image.new('L', image.size)
    draw = ImageDraw.Draw(mask)
    for points, fill in ((OUTER, 255), (INNER, 0)):
        draw.polygon([(round(inset + x * scale), round(inset + y * scale)) for x, y in points], fill=fill)
    image.paste(Image.new('RGBA', image.size, BLUE), (0, 0), mask)
    return image.resize((size, size), Image.Resampling.LANCZOS)


write(BRAND / 'outpost-mark.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180"><path fill="{BLUE}" fill-rule="evenodd" d="{PATH}"/></svg>\n')
write(BRAND / 'outpost-mark-white.svg', f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180"><path fill="#FFFFFF" fill-rule="evenodd" d="{PATH}"/></svg>\n')
raster(1024).save(BRAND / 'outpost-mark.png', optimize=True)
raster(1024, tile=True).save(BRAND / 'outpost-icon.png', optimize=True)
raster(256, tile=True).save(ROOT / 'desktop/outpost.ico', sizes=[(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)])

vector = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{width}dp" android:height="{width}dp" android:viewportWidth="{width}" android:viewportHeight="{width}">{body}</vector>\n'
path = f'<path android:fillColor="{BLUE}" android:fillType="evenOdd" android:pathData="{PATH}"/>'
write(RES / 'drawable/ic_outpost_mark.xml', vector.format(width=180, body=path))
# 46dp square fits inside the 66dp adaptive-icon safe circle, including its corners.
foreground = '<group android:translateX="31" android:translateY="31" android:scaleX="0.25555556" android:scaleY="0.25555556">' + path + '</group>'
write(RES / 'drawable/ic_outpost_foreground.xml', vector.format(width=108, body=foreground))
write(RES / 'drawable/ic_outpost_monochrome.xml', vector.format(width=108, body=foreground.replace(BLUE, '#FFFFFF')))
write(RES / 'values/icon_colors.xml', f'<resources><color name="outpost_icon_background">{DARK}</color></resources>\n')
adaptive = '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/outpost_icon_background"/><foreground android:drawable="@drawable/ic_outpost_foreground"/>{mono}</adaptive-icon>\n'
write(RES / 'mipmap-anydpi-v26/ic_launcher.xml', adaptive.format(mono=''))
write(RES / 'mipmap-anydpi-v33/ic_launcher.xml', adaptive.format(mono='<monochrome android:drawable="@drawable/ic_outpost_monochrome"/>'))
for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    target = RES / f'mipmap-{density}/ic_launcher.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    raster(size, tile=True).save(target, optimize=True)
print('Exported Android, Windows and reusable branding assets.')
