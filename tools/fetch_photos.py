#!/usr/bin/env python3
"""Find and download open-licence photos of Spain from Wikimedia Commons.

  tools/fetch_photos.py candidates   list candidates per place -> photos/raw/candidates.json
  tools/fetch_photos.py download     download the chosen ones (photos/raw/chosen.json)

Prefers Featured pictures, then Quality images; landscape only.
"""
import json
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "photos" / "raw"
API = "https://commons.wikimedia.org/w/api.php"
UA = "tvhome-launcher-photo-fetch/1.0 (personal Android TV launcher)"

PLACES = [
    ("La Alhambra", "Granada", "Alhambra Granada"),
    ("Sagrada Família", "Barcelona", "Sagrada Familia"),
    ("Park Güell", "Barcelona", "Park Güell"),
    ("Plaza de España", "Sevilla", "Plaza de España Sevilla"),
    ("Mezquita-Catedral", "Córdoba", "Mezquita Córdoba"),
    ("Acueducto", "Segovia", "Acueducto Segovia"),
    ("Alcázar", "Segovia", "Alcázar de Segovia"),
    ("Toledo", "Castilla-La Mancha", "Toledo panorama"),
    ("Ciudad de las Artes y las Ciencias", "Valencia", "Ciudad de las Artes y las Ciencias"),
    ("Museo Guggenheim", "Bilbao", "Guggenheim Bilbao"),
    ("Catedral", "Santiago de Compostela", "Catedral de Santiago de Compostela"),
    ("Puente Nuevo", "Ronda", "Puente Nuevo Ronda"),
    ("Casas Colgadas", "Cuenca", "Casas Colgadas Cuenca"),
    ("Catedral", "Burgos", "Catedral de Burgos"),
    ("Plaza Mayor", "Salamanca", "Plaza Mayor Salamanca"),
    ("Palacio Real", "Madrid", "Palacio Real Madrid"),
    ("San Juan de Gaztelugatxe", "Bizkaia", "Gaztelugatxe"),
    ("El Teide", "Tenerife", "Teide"),
    ("Catedral", "Palma de Mallorca", "Catedral de Palma"),
    ("Albarracín", "Teruel", "Albarracín"),
    ("Peñíscola", "Castellón", "Peñíscola"),
]

# second batch: Andalucía
PLACES_EXTRA = [
    ("Catedral", "Cádiz", "Catedral de Cádiz"),
    ("La Caleta", "Cádiz", "Caleta Cádiz"),
    ("Muelle del Tinto", "Huelva", "Muelle del Tinto Huelva"),
    ("Doñana", "Huelva", "Doñana"),
    ("Giralda y Catedral", "Sevilla", "Giralda Sevilla"),
    ("Real Alcázar", "Sevilla", "Real Alcázar Sevilla"),
    ("Torre del Oro", "Sevilla", "Torre del Oro"),
    ("Alcazaba", "Málaga", "Alcazaba Málaga"),
    ("Caminito del Rey", "Málaga", "Caminito del Rey"),
    ("Catedral", "Jaén", "Catedral de Jaén"),
    ("Úbeda", "Jaén", "Úbeda"),
    ("Alcazaba", "Almería", "Alcazaba de Almería"),
    ("Cabo de Gata", "Almería", "Cabo de Gata"),
    ("Olvera", "Cádiz", "Olvera"),
]

CATEGORIES = ["Featured pictures on Wikimedia Commons", "Quality images"]


def api(**params):
    params.update(format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(15 * (attempt + 1))
    raise RuntimeError("rate limited by Commons")


def search(query, category):
    q = f'{query} incategory:"{category}" filetype:bitmap' if category else f'{query} filetype:bitmap'
    data = api(action="query", generator="search", gsrsearch=q, gsrnamespace=6, gsrlimit=12,
               prop="imageinfo", iiprop="url|size|extmetadata|mime", iiurlwidth=2560)
    out = []
    for p in (data.get("query") or {}).get("pages", []):
        ii = (p.get("imageinfo") or [{}])[0]
        w, h = ii.get("width", 0), ii.get("height", 0)
        if not h or w / h < 1.3 or w < 1920 or ii.get("mime") != "image/jpeg":
            continue
        meta = ii.get("extmetadata", {})
        out.append({
            "title": p["title"],
            "page": ii.get("descriptionurl"),
            "url": ii.get("thumburl") or ii.get("url"),
            "size": [w, h],
            "author": strip_html(meta.get("Artist", {}).get("value", "")),
            "license": meta.get("LicenseShortName", {}).get("value", ""),
            "category": category,
        })
    return out


def strip_html(s):
    import re
    return re.sub(r"<[^>]+>", "", s).strip()


def candidates(places=None, out="candidates.json"):
    RAW.mkdir(parents=True, exist_ok=True)
    result = []
    for place, city, query in places or PLACES:
        found = []
        for cat in CATEGORIES + [None]:
            found += search(query, cat)
            if len(found) >= 4:
                break
            time.sleep(2)
        print(f"{place} ({city}): {len(found)}")
        result.append({"place": place, "city": city, "candidates": found[:6]})
    (RAW / out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")


def download():
    chosen = json.loads((RAW / "chosen.json").read_text(encoding="utf-8"))
    for i, c in enumerate(chosen):
        dest = RAW / f"{i:02d}.jpg"
        if dest.exists():
            continue
        req = urllib.request.Request(c["url"], headers={"User-Agent": UA})
        for attempt in range(6):
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    dest.write_bytes(r.read())
                break
            except urllib.error.HTTPError as e:
                if e.code != 429:
                    raise
                time.sleep(15 * (attempt + 1))
        print(dest.name, c["place"], dest.stat().st_size // 1024, "KB")
        time.sleep(2)


if __name__ == "__main__":
    {"candidates": candidates, "download": download,
     "candidates-extra": lambda: candidates(PLACES_EXTRA, "candidates_extra.json")}[sys.argv[1]]()
