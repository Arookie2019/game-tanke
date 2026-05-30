"""校验 assets/levels/*.txt 地图行宽度一致。"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "levels"


def main() -> None:
    for path in sorted(ROOT.glob("*.txt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        map_lines = []
        meta = []
        for line in lines:
            s = line.strip()
            if not s:
                continue
            if s.startswith("@"):
                meta.append(s)
                continue
            map_lines.append(line.rstrip("\r"))

        widths = [len(ml) for ml in map_lines]
        if len(set(widths)) > 1:
            raise SystemExit(f"{path.name}: inconsistent widths {widths}")
        print(f"{path.name}: OK, {len(map_lines)} rows x {widths[0] if widths else 0}")


if __name__ == "__main__":
    main()
