import urllib.request
import json

project_id = "gen-lang-client-0664045019"
database_id = "ai-studio-android-kidsguar-eb6ecfcf-afa9-4665-bff2-f057402e5c1f"

# Write test app control
doc_data = {
    "fields": {
        "childId": {"integerValue": 101},
        "packageName": {"stringValue": "com.instagram.android"},
        "appName": {"stringValue": "Instagram"},
        "isBlocked": {"booleanValue": False}
    }
}

req_data = json.dumps(doc_data).encode('utf-8')
url = f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/{database_id}/documents/app_controls/test_app"

req = urllib.request.Request(url, data=req_data, headers={'Content-Type': 'application/json'}, method='PATCH')
try:
    with urllib.request.urlopen(req) as resp:
        print("Success app control write:", resp.status)
        print(resp.read().decode('utf-8')[:200])
except Exception as e:
    print("Error:", e)
