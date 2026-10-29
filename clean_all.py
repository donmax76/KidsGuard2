import json

path = "/app/applet/sync_data.json"
try:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    print("CURRENT CHILDREN IN CLOUD:", list(data.get("children", {}).keys()))
    # Remove any test children
    cleaned = {}
    for cid, c in data.get("children", {}).items():
        name = c.get("name", "").lower()
        if "тест" not in name and "test" not in name:
            cleaned[cid] = c
        else:
            print(f"DELETED TEST CHILD: {cid} -> {c.get('name')}")
    data["children"] = cleaned
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    print("CLEANED. REMAINING:", list(cleaned.keys()))
except Exception as e:
    print("Error:", e)
