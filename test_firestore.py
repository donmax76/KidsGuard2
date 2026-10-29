import urllib.request
import json

project_id = "gen-lang-client-0664045019"
database_id = "ai-studio-android-kidsguar-eb6ecfcf-afa9-4665-bff2-f057402e5c1f"

urls = [
    f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/{database_id}/documents/children",
    f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/(default)/documents/children"
]

for url in urls:
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as resp:
            print("OK:", url, resp.status)
            print(resp.read().decode('utf-8')[:300])
    except urllib.error.HTTPError as e:
        print("HTTP Error:", url, e.code, e.read().decode('utf-8')[:300])
    except Exception as e:
        print("Error:", url, str(e))
