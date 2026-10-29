import threading
from sync_server import ReusableThreadingTCPServer, CustomHandler, PORT, mqtt_bridge_worker

if __name__ == "__main__":
    t = threading.Thread(target=mqtt_bridge_worker, daemon=True)
    t.start()
    with ReusableThreadingTCPServer(("0.0.0.0", PORT), CustomHandler) as httpd:
        print(f"Serving at http://0.0.0.0:{PORT}", flush=True)
        httpd.serve_forever()
