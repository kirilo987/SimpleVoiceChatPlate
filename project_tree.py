import os
from pathlib import Path
from contextlib import redirect_stdout

IGNORE_DIRS = {
    ".git",
    "__pycache__",
    ".venv",
    ".idea",
    ".vscode",
    "node_modules",
    ".cache",
    "log",
    ".local",
    ".gradle",
    ".next",
    "build",
    ".agent",
    "mappings",
    "mappings-cloud"
}

IGNORE_FILES = {
    ".DS_Store",
    ".bat",
    ".env",
    "project_tree.py",
    "dump_project.txt"
}

READABLE_EXTENSIONS = {
    ".py",
    ".txt",
    ".json",
    ".md",
    ".java",
    ".yml",
    ".yaml",
    ".gradle",
    ".ts",
    ".css",
    ".kts",
    ".js",
    ".html",
    ".kt"
}

ROOT = Path(__file__).parent.resolve()

DUMP_FILE = ROOT / "dump_project.txt"


def print_tree(path: Path, prefix=""):
    path = path.resolve()

    if ROOT not in path.parents and path != ROOT:
        return

    try:
        items = sorted(os.listdir(path))
    except PermissionError:
        print(prefix + "⛔ Нет доступа")
        return

    filtered_items = [
        name for name in items
        if name not in IGNORE_DIRS and name not in IGNORE_FILES
    ]

    for index, name in enumerate(filtered_items):
        full_path = path / name

        is_last = index == len(filtered_items) - 1
        branch = "└── " if is_last else "├── "
        child_prefix = prefix + ("    " if is_last else "│   ")

        if full_path.is_dir():
            print(prefix + branch + f"📁 {name}/")
            print_tree(full_path, child_prefix)

        else:
            try:
                size = full_path.stat().st_size
            except Exception:
                size = 0

            ext = full_path.suffix.lower()

            print(prefix + branch + f"📄 {name} ({size} bytes)")

            if ext in READABLE_EXTENSIONS:
                try:
                    with open(full_path, "r", encoding="utf-8") as f:
                        print(child_prefix + "──── FILE CONTENT ────")

                        for line in f:
                            print(child_prefix + line.rstrip())

                        print(child_prefix + "──── END FILE ────")

                except UnicodeDecodeError:
                    print(child_prefix + "⚠️ binary / unsupported encoding")

                except Exception as e:
                    print(child_prefix + f"⚠️ error reading file: {e}")


if __name__ == "__main__":
    if DUMP_FILE.exists():
        DUMP_FILE.unlink()

    with open(DUMP_FILE, "w", encoding="utf-8") as dump:
        with redirect_stdout(dump):
            print(f"📂 Root: {ROOT}\n")
            print_tree(ROOT)

    print(f"✅ Дамп сохранён: {DUMP_FILE}")