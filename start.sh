#!/bin/bash
# Starts the relay server + ngrok tunnel
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DOMAIN="geotropic-wilford-unannunciative.ngrok-free.app"
PORT=8080

# Kill any existing instances
pkill -f "server.py" 2>/dev/null
pkill -f "ngrok http" 2>/dev/null
sleep 1

echo "[Relay] Starting relay server on port $PORT..."
python3 "$SCRIPT_DIR/server.py" $PORT &
SERVER_PID=$!

sleep 2

echo "[ngrok] Starting tunnel -> https://$DOMAIN"
# GODEBUG=netdns=go forces IPv4 DNS — fixes CRL timeout on broken IPv6
GODEBUG=netdns=go "$SCRIPT_DIR/ngrok" http --url="https://$DOMAIN" $PORT &
NGROK_PID=$!

echo ""
echo "================================================"
echo "  ESP32 HID Remote Controller"
echo "================================================"
echo "  Local:  http://localhost:$PORT"
echo "  Public: https://$DOMAIN"
echo ""
echo "  ESP32 must be connected to WiFi and polling"
echo "  Press Ctrl+C to stop"
echo "================================================"

wait $SERVER_PID
