import json

path = "/app/applet/sync_data.json"
try:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    print("KEYS IN CLOUD:", list(data.keys()))
    if "children" in data:
        print("CHILDREN BEFORE:", data["children"])
        # Keep ONLY real children, drop anything with test/тест
        filtered = {k: v for k, v in data["children"].items() if "тест" not in v.get("name", "").lower() and "test" not in v.get("name", "").lower()}
        data["children"] = filtered
        print("CHILDREN AFTER:", data["children"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
except Exception as e:
    print("ERROR:", e)
