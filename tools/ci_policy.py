"""Skip graphical boots only for documentation-only PRs; unknown paths require them."""
import argparse
from pathlib import PurePosixPath
import subprocess


def needs_live(paths: list[str]) -> bool:
    def documentation(path: str) -> bool:
        return not path.startswith(".github/") and (
            PurePosixPath(path).suffix.lower() in {".md", ".txt"}
            and (path.startswith("docs/") or path.startswith("changelogs/")
                 or "/" not in path)
            or path in {"LICENSE", "NOTICE"}
        )
    return not paths or any(not documentation(path) for path in paths)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("base")
    parser.add_argument("head")
    args = parser.parse_args()
    paths = subprocess.check_output(
        ["git", "diff", "--name-only", "-z", f"{args.base}...{args.head}"], text=True
    ).split("\0")
    print("true" if needs_live([p for p in paths if p]) else "false")
