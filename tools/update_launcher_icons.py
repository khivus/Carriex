"""Import artwork into icon.png. Gradle generates launcher resources on build."""
import argparse
import shutil
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    target = root / "icon.png"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=target)
    source = parser.parse_args().source.resolve()
    if not source.is_file():
        parser.error(f"Artwork does not exist: {source}")
    if source != target.resolve():
        shutil.copyfile(source, target)
    print(f"Launcher source: {target}. Build the app to regenerate all icon sizes.")


if __name__ == "__main__":
    main()
