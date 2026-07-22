#!/usr/bin/env python3
"""
TerraQuest coverage spike.

Purpose: answer one question before we build anything else --
is Mapillary imagery good enough to support a geography game?

Deliberately a throwaway Python script, not Java. This is a diagnostic that
runs once and gets deleted; wiring it into the Spring app would be premature.

Usage:
    export MAPILLARY_ACCESS_TOKEN=MLY|...
    pip install requests
    python coverage_spike.py --samples 500 --out spike_results.json

Interpreting the output -- decision thresholds I would hold us to:

    panoramic_pct >= 30% and countries_with_10plus >= 50
        -> Green. Build Option B (proxied custom viewer). The game works.

    panoramic_pct 15-30% or countries_with_10plus 25-50
        -> Yellow. Playable but regionally limited. Reposition the product
           around region modes and Learning Mode rather than global Classic.

    panoramic_pct < 15% or countries_with_10plus < 25
        -> Red. Mapillary alone cannot carry this. Options: accept a
           flat-photo quiz (different, smaller product), supplement with
           other open sources (KartaView, Wikimedia Commons geotagged),
           or reconsider the concept.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from collections import Counter
from dataclasses import dataclass, field

import requests

GRAPH_URL = "https://graph.mapillary.com/images"

# API ceiling is 0.01 degrees square (formalized 2026-01-16). Stay under it.
BBOX_DEGREES = 0.0098

FIELDS = "id,geometry,compass_angle,captured_at,is_pano,sequence,creator,thumb_1024_url,height,width"


@dataclass
class SpikeResults:
    """Aggregated findings. Deliberately raw -- we want to argue with the data."""

    points_probed: int = 0
    points_with_coverage: int = 0
    total_images: int = 0
    panoramic_images: int = 0
    images_by_country: Counter = field(default_factory=Counter)
    panos_by_country: Counter = field(default_factory=Counter)
    resolutions: list[tuple[int, int]] = field(default_factory=list)
    capture_years: Counter = field(default_factory=Counter)
    errors: int = 0

    def summary(self) -> dict:
        pano_pct = (100 * self.panoramic_images / self.total_images) if self.total_images else 0.0
        hit_rate = (100 * self.points_with_coverage / self.points_probed) if self.points_probed else 0.0
        countries_10plus = sum(1 for c in self.images_by_country.values() if c >= 10)
        pano_countries_10plus = sum(1 for c in self.panos_by_country.values() if c >= 10)

        # Concentration: if the top 5 countries hold most of the pool, the
        # "global" game is a fiction and we need to say so out loud.
        top5 = self.images_by_country.most_common(5)
        top5_share = (100 * sum(n for _, n in top5) / self.total_images) if self.total_images else 0.0

        return {
            "points_probed": self.points_probed,
            "coverage_hit_rate_pct": round(hit_rate, 1),
            "total_images": self.total_images,
            "panoramic_pct": round(pano_pct, 1),
            "distinct_countries": len(self.images_by_country),
            "countries_with_10plus_images": countries_10plus,
            "countries_with_10plus_panos": pano_countries_10plus,
            "top5_country_share_pct": round(top5_share, 1),
            "top5_countries": top5,
            "median_resolution": self._median_resolution(),
            "capture_years": dict(sorted(self.capture_years.items())),
            "errors": self.errors,
            "verdict": self._verdict(pano_pct, pano_countries_10plus),
        }

    def _median_resolution(self) -> str:
        if not self.resolutions:
            return "unknown"
        widths = sorted(w for w, _ in self.resolutions)
        heights = sorted(h for _, h in self.resolutions)
        mid = len(widths) // 2
        return f"{widths[mid]}x{heights[mid]}"

    @staticmethod
    def _verdict(pano_pct: float, pano_countries: int) -> str:
        if pano_pct >= 30 and pano_countries >= 50:
            return "GREEN - build it"
        if pano_pct >= 15 and pano_countries >= 25:
            return "YELLOW - viable but reposition toward regional/learning modes"
        return "RED - Mapillary alone is insufficient; supplement or rethink"


def load_seed_points(path: str | None, n: int) -> list[tuple[float, float, str]]:
    """
    Seed points as (lat, lon, iso2).

    Pass --seeds with a GeoNames cities15000.txt export for a real run.
    Without it we fall back to a small hardcoded set spanning every inhabited
    continent -- enough for a smoke test, not enough for a real verdict.
    """
    if path:
        points = []
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                cols = line.split("\t")
                if len(cols) > 8:
                    try:
                        points.append((float(cols[4]), float(cols[5]), cols[8]))
                    except ValueError:
                        continue
        random.shuffle(points)
        return points[:n]

    print("WARNING: no --seeds file; using fallback set. Verdict will be unreliable.", file=sys.stderr)
    fallback = [
        (52.5200, 13.4050, "DE"), (59.3293, 18.0686, "SE"), (35.6762, 139.6503, "JP"),
        (40.7128, -74.0060, "US"), (-33.8688, 151.2093, "AU"), (-23.5505, -46.6333, "BR"),
        (20.5937, 78.9629, "IN"), (-1.2921, 36.8219, "KE"), (55.7558, 37.6173, "RU"),
        (48.8566, 2.3522, "FR"), (19.4326, -99.1332, "MX"), (13.7563, 100.5018, "TH"),
        (-34.6037, -58.3816, "AR"), (30.0444, 31.2357, "EG"), (37.5665, 126.9780, "KR"),
        (41.9028, 12.4964, "IT"), (-26.2041, 28.0473, "ZA"), (25.2048, 55.2708, "AE"),
        (14.5995, 120.9842, "PH"), (6.5244, 3.3792, "NG"),
    ]
    return [random.choice(fallback) for _ in range(n)]


def probe(lat: float, lon: float, token: str, session: requests.Session) -> list[dict]:
    half = BBOX_DEGREES / 2
    params = {
        "access_token": token,
        "fields": FIELDS,
        "bbox": f"{lon - half},{lat - half},{lon + half},{lat + half}",
        "limit": 50,
    }
    resp = session.get(GRAPH_URL, params=params, timeout=20)
    resp.raise_for_status()
    return resp.json().get("data", [])


def run(samples: int, seeds: str | None, token: str, delay: float) -> SpikeResults:
    results = SpikeResults()
    session = requests.Session()
    points = load_seed_points(seeds, samples)

    for i, (lat, lon, iso2) in enumerate(points, 1):
        try:
            images = probe(lat, lon, token, session)
        except Exception as exc:
            results.errors += 1
            print(f"  [{i}/{len(points)}] error: {type(exc).__name__}", file=sys.stderr)
            continue
        finally:
            results.points_probed += 1
            time.sleep(delay)

        if not images:
            continue

        results.points_with_coverage += 1
        for img in images:
            results.total_images += 1
            results.images_by_country[iso2] += 1

            if img.get("is_pano"):
                results.panoramic_images += 1
                results.panos_by_country[iso2] += 1

            if img.get("width") and img.get("height"):
                results.resolutions.append((img["width"], img["height"]))

            if img.get("captured_at"):
                year = time.gmtime(img["captured_at"] / 1000).tm_year
                results.capture_years[year] += 1

        if i % 25 == 0:
            print(f"  [{i}/{len(points)}] {results.total_images} images, "
                  f"{results.panoramic_images} panoramic")

    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Measure Mapillary coverage quality.")
    parser.add_argument("--samples", type=int, default=500)
    parser.add_argument("--seeds", help="Path to GeoNames cities15000.txt")
    parser.add_argument("--out", default="spike_results.json")
    parser.add_argument("--delay", type=float, default=0.2, help="Seconds between requests")
    args = parser.parse_args()

    token = os.environ.get("MAPILLARY_ACCESS_TOKEN")
    if not token:
        print("MAPILLARY_ACCESS_TOKEN not set.", file=sys.stderr)
        return 1

    print(f"Probing {args.samples} points...")
    results = run(args.samples, args.seeds, token, args.delay)
    summary = results.summary()

    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)

    print("\n" + "=" * 60)
    for key, value in summary.items():
        print(f"{key:>32}: {value}")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(main())
