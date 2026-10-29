import urllib.request
project_id = "gen-lang-client-0664045019"
database_id = "ai-studio-android-kidsguar-eb6ecfcf-afa9-4665-bff2-f057402e5c1f"
url = f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/{database_id}/documents/children/test1"
try:
    with urllib.request.urlopen(url) as resp:
        print("Read Status:", resp.status)
        print(resp.read().decode('utf-8'))
except Exception as e:
    print("Read failed:", e)
