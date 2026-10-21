"""Draws the README's coverage and mutation badges as SVG files, from JaCoCo's and PIT's XML reports.

Usage: badges.py OUT_DIR
Standard library only, so CI needs nothing extra.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CORE_COVERAGE = Path("core/build/reports/jacoco/test/jacocoTestReport.xml")
APP_COVERAGE = Path("app/build/reports/coverage/test/debug/report.xml")
CORE_MUTATIONS = Path("core/build/reports/pitest/mutations.xml")


def line_coverage(report: Path) -> float:
    """The report-wide LINE counter is the last direct child counter of <report>."""
    root = ET.parse(report).getroot()
    line = [c for c in root.findall("counter") if c.get("type") == "LINE"][0]
    covered, missed = int(line.get("covered")), int(line.get("missed"))
    return 100 * covered / (covered + missed)


def mutation_score(report: Path) -> float:
    statuses = [m.get("status") for m in ET.parse(report).getroot()]
    killed = sum(s in ("KILLED", "TIMED_OUT", "MEMORY_ERROR") for s in statuses)
    return 100 * killed / len(statuses)


def color(percent: float) -> str:
    if percent >= 90:
        return "#2e7d32"
    if percent >= 75:
        return "#689f38"
    if percent >= 50:
        return "#f9a825"
    return "#c62828"


def badge(label: str, percent: float) -> str:
    value = f"{percent:.0f}%"
    # Rough Verdana 11px widths: good enough for short labels.
    left, right = 6 * len(label) + 12, 7 * len(value) + 12
    width = left + right
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="20" role="img" aria-label="{label}: {value}">
<title>{label}: {value}</title>
<clipPath id="r"><rect width="{width}" height="20" rx="3"/></clipPath>
<g clip-path="url(#r)"><rect width="{left}" height="20" fill="#555"/><rect x="{left}" width="{right}" height="20" fill="{color(percent)}"/></g>
<g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">
<text x="{left / 2}" y="14">{label}</text><text x="{left + right / 2}" y="14">{value}</text></g>
</svg>
"""


def main() -> None:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    badges = {
        "core-coverage.svg": ("core coverage", line_coverage(CORE_COVERAGE)),
        "app-coverage.svg": ("app coverage", line_coverage(APP_COVERAGE)),
        "core-mutation.svg": ("core mutation score", mutation_score(CORE_MUTATIONS)),
    }
    for name, (label, percent) in badges.items():
        (out / name).write_text(badge(label, percent))
        print(f"{label}: {percent:.1f}%")


if __name__ == "__main__":
    main()
