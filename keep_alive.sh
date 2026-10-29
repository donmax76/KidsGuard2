#!/bin/bash
while true; do
    if ! pgrep -f "sync_server.py" > /dev/null; then
        echo "[$(date)] Restarting sync_server.py..." >> /tmp/keep_alive.log
        nohup python3 /app/applet/sync_server.py >> /tmp/sync_server.log 2>&1 &
    fi
    sleep 3
done
