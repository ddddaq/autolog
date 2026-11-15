#!/bin/zsh
# Pleos Connect 에뮬레이터를 cold boot로 띄우고 Virtual Console 포트를 연다.
# 사용: ./tools/start_emulator.sh   (끝나면 브라우저에서 http://127.0.0.1:8088/ 을 열어 둘 것)
export ANDROID_HOME="$HOME/Library/Android/sdk"
ADB="$ANDROID_HOME/platform-tools/adb"
if ! "$ADB" devices | grep -q "emulator-"; then
  nohup "$ANDROID_HOME/emulator/emulator" -avd "Pleos_Connect_v2.1" -no-snapshot -timezone Asia/Seoul -netdelay none -netspeed full > /tmp/pleos_emulator.log 2>&1 &
  echo "에뮬레이터 시작. 부팅을 기다립니다..."
fi
until [ "$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
"$ADB" -e forward tcp:8088 tcp:8088 > /dev/null
echo "부팅 완료. Virtual Console: http://127.0.0.1:8088/"
echo "주의: Virtual Console 페이지가 브라우저에 열려 있어야 차량 신호 쓰기가 동작합니다."
open "http://127.0.0.1:8088/"
