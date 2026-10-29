import urllib.request
import json

project_id = "gen-lang-client-0664045019"
database_id = "ai-studio-android-kidsguar-eb6ecfcf-afa9-4665-bff2-f057402e5c1f"

# Test POST/Write to custom DB
doc_data = {
    "fields": {
        "name": {"stringValue": "Тест Ребенок"},
        "pairingCode": {"stringValue": "777-888"},
        "isPaired": {"booleanValue": False}
    }
}

req_data = json.dumps(doc_data).encode('utf-8')
url = f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/{database_id}/documents/children?documentId=test1"

req = urllib.request.Request(url, data=req_data, headers={'Content-Type': 'application/json'}, method='POST')
try:
    with urllib.request.urlopen(req) as resp:
        print("Success:", resp.status)
        print(resp.read().decode('utf-8'))
except urllib.error.HTTPError as e:
    print("HTTP Error:", e.code, e.read().decode('utf-8'))
except Exception as e:
    print("Error:", str(e))
