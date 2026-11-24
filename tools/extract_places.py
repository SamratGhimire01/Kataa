"""Extracts named shops and everyday places from a Nepal OpenStreetMap extract into a small CSV.

Usage: python3 tools/extract_places.py nepal-latest.osm.pbf out.tsv  (then gzip -9; the app reads app/src/main/assets/places/nepal.tsv.gz)
Columns: ref, name, brand (brand tag or name), kind (OSM shop/amenity value), lat, lon.
Data (c) OpenStreetMap contributors, ODbL. Kataa shows that credit in Settings.
"""
import sys, osmium

AMENITY = {"pharmacy", "bank", "atm", "hospital", "clinic", "fuel", "post_office", "marketplace", "cafe",
           "restaurant", "fast_food", "school", "college", "university", "library", "police", "dentist",
           "doctors", "bus_station", "cinema", "place_of_worship", "veterinary", "car_wash", "gym"}
SHOP_SKIP = {"vacant", "no", "yes;vacant"}

class H(osmium.SimpleHandler):
    def __init__(self, out):
        super().__init__(); self.out = out; self.n = 0
    def emit(self, ref, tags, lat, lon):
        name = tags.get("name:en") or tags.get("name") or tags.get("brand")
        if not name: return
        shop, amenity, leisure = tags.get("shop"), tags.get("amenity"), tags.get("leisure")
        if shop and shop not in SHOP_SKIP: kind = shop
        elif amenity in AMENITY: kind = amenity
        elif leisure in ("fitness_centre", "park", "stadium"): kind = leisure
        else: return
        brand = tags.get("brand:en") or tags.get("brand") or name
        clean = lambda s: s.replace("\t", " ").replace("\n", " ").strip()
        kind = kind.split(";")[0].strip()
        self.out.write(f"{ref}\t{clean(name)}\t{clean(brand)}\t{clean(kind)}\t{lat:.5f}\t{lon:.5f}\n"); self.n += 1
    def node(self, n):
        if n.location.valid(): self.emit(f"n{n.id}", n.tags, n.location.lat, n.location.lon)
    def way(self, w):
        if "name" not in w.tags and "brand" not in w.tags: return
        try:
            pts = [(nd.lat, nd.lon) for nd in w.nodes if nd.location.valid()]
        except Exception:
            return
        if pts: self.emit(f"w{w.id}", w.tags, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))

with open(sys.argv[2], "w") as out:
    h = H(out); h.apply_file(sys.argv[1], locations=True); print("places", h.n)
