import urllib.request
project_id = "gen-lang-client-0664045019"
database_id = "ai-studio-android-kidsguar-eb6ecfcf-afa9-4665-bff2-f057402e5c1f"
url = f"https://firestore.googleapis.com/v1/projects/{project_id}/databases/{database_id}/documents/app_controls/test_app"
req = urllib.request.Request(url, method='DELETE')
try:
    with urllib.request.urlopen(req) as resp:
        print("Cleaned:", resp.status)
except Exception as e:
    print(e)
