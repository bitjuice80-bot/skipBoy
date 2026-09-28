import os
from PIL import Image

# 源图：ImageGen 生成的最新图标（少年Jump）
SRC = r"C:/Users/dai79/WorkBuddy/2026-09-25-08-44-42/generated-images/Android_app_launcher_icon_for__2026-09-28T06-07-26.png"
RES = r"C:/Users/dai79/WorkBuddy/2026-09-25-08-44-42/SkipAd/app/src/main/res"

# 传统 launcher 图标密度 -> 尺寸(px)
DENS = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def main():
    im = Image.open(SRC).convert("RGBA")
    print("source size:", im.size)
    for d, sz in DENS.items():
        out_dir = os.path.join(RES, d)
        os.makedirs(out_dir, exist_ok=True)
        r = im.resize((sz, sz), Image.LANCZOS)
        r.save(os.path.join(out_dir, "ic_launcher.png"))
        r.save(os.path.join(out_dir, "ic_launcher_round.png"))
        print("wrote", d, sz)
    print("OK")


if __name__ == "__main__":
    main()
