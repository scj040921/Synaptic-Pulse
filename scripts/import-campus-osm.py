"""Import a small, attributed OSM campus extract; never downloads map tiles."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
args = parser.parse_args()
osm = ET.parse(args.source).getroot()
nodes = {n.attrib["id"]: n for n in osm.findall("node")}
selected = {
    "way/1498158957": "study", "way/1339061113": "study", "node/4445332618": "study",
    "node/12338854725": "culture", "node/4445332616": "culture", "node/13720934781": "culture",
    "way/1339061143": "culture", "way/1339061091": "leisure", "node/12251884614": "leisure",
    "way/303990342": "food", "node/5470207859": "food", "way/1092587492": "sport",
    "way/1103988597": "sport", "way/1337917424": "service", "way/1338261949": "service",
    "node/12240469338": "service", "way/1337917384": "culture", "node/13720181505": "service",
}
places, features = [], []
for element in [*osm.findall("node"), *osm.findall("way")]:
    tags = {t.attrib["k"]: t.attrib["v"] for t in element.findall("tag")}
    key = element.tag + "/" + element.attrib["id"]
    points = [element] if element.tag == "node" else [nodes[n.attrib["ref"]] for n in element.findall("nd") if n.attrib["ref"] in nodes]
    if not points:
        continue
    coords = [[float(n.attrib["lon"]), float(n.attrib["lat"])] for n in points]
    unique = coords[:-1] if len(coords) > 1 and coords[0] == coords[-1] else coords
    lng, lat = (sum(c[i] for c in unique) / len(unique) for i in (0, 1))
    if key in selected:
        places.append(dict(id=key.replace("/", "-"), name=tags["name"], campus="广州大学", category=selected[key], latitude=round(lat, 7), longitude=round(lng, 7), sourceUrl="https://www.openstreetmap.org/" + key, description="广州大学大学城校区 · OSM 公开点位，建筑中心位置，入口及开放信息请以现场为准。"))
    if element.tag != "way" or not (23.039 < lat < 23.0455 and 113.360 < lng < 113.3745):
        continue
    kind = "building" if "building" in tags else "road" if "highway" in tags else "water" if tags.get("natural") == "water" or "waterway" in tags else "green" if tags.get("leisure") in ("park", "pitch", "garden") or tags.get("landuse") in ("grass", "forest") else None
    if not kind or len(coords) < 2:
        continue
    polygon = coords[0] == coords[-1] and len(coords) >= 4 and kind != "road"
    features.append(dict(type="Feature", properties=dict(kind=kind, name=tags.get("name", "")), geometry=dict(type="Polygon" if polygon else "LineString", coordinates=[coords] if polygon else coords)))
def save(relative, value):
    target = args.root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
save("backend/src/main/resources/map/places.json", places)
save("client/static/campus/campus.geojson", dict(type="FeatureCollection", features=features))
print(f"Imported {len(places)} places and {len(features)} geometries")
