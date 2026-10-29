"""Print the most useful parts of a Gradle log as GitHub error annotations (readable via the API)."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "build.log"
text = open(path, errors="ignore").read()
lines = text.splitlines()


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def emit(title: str, body: str) -> None:
    if body.strip():
        print(f"::error title={title}::{esc(body[:3500])}")


m = re.search(r"\* What went wrong:.*?(?=\n\* Try:|\Z)", text, re.S)
emit("gradle-what-went-wrong", m.group(0) if m else "")

errs = [l for l in lines if l.startswith("e: ") or " FAILED" in l or "error:" in l.lower() or "Unresolved" in l]
emit("gradle-errors", "\n".join(errs[:40]))

emit("gradle-tail", "\n".join(lines[-45:]))
