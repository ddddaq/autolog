#!/bin/zsh
# 오토로그 시연 각본을 Virtual Console HTTP API로 재생한다.
# 전제: 에뮬레이터 실행 중, adb forward tcp:8088 tcp:8088.
# Virtual Console은 브라우저 세션(하트비트)이 살아 있어야 쓰기를 받으므로, 이 스크립트가 직접 하트비트를 보낸다.
# 사용: ./tools/demo_scenario.sh [주행km=32] [route]
#   route 를 붙이면 출발 전에 내비 목적지(문서의 예시 POI, 42dot)를 넣는다. 내장 내비가 화면 앞으로 나온다.
#   끝나면 AutoLog 화면을 다시 앞으로 가져온다.
# 계기판 거리는 줄일 수 없으므로(Virtual Console이 400으로 거절) 현재 값에서 출발한다.
# 에뮬레이터를 새로 켜면 계기판이 0km로 돌아간다. 예시 운행을 넣기 전에 ./tools/vc.sh odo 12340 으로 먼저 맞춰 둔다.
VC=http://127.0.0.1:8088
DIST=${1:-32}
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
( while true; do curl -s -o /dev/null -X POST --max-time 2 "$VC/browser/session?client=autolog-demo"; sleep 1; done ) &
HB=$!
trap "kill $HB 2>/dev/null" EXIT
sleep 3
START=$(curl -s --max-time 10 "$VC/vehicle/state" | python3 -c "import sys,json; print(int(json.load(sys.stdin)['odometer']['traveled_distance_km']))")
[ -n "$START" ] || { echo "실패: 현재 계기판 거리를 읽지 못함"; exit 1; }
put() { code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT --max-time 10 "$VC$1"); echo "$code  $1"; [ "$code" = "200" ] || { echo "실패: 에뮬레이터와 포트 포워딩을 확인"; exit 1; }; }
echo "1) 운전석 문 닫힘, 현재 계기판 ${START}km"
put "/vehicle/body?door_fl=false"; sleep 2
if [ "$2" = "route" ]; then
  echo "1-1) 내비 목적지 설정 (42dot)"
  "$ADB" -e shell am broadcast --user 10 -n com.autolog.app/.debug.DemoRouteReceiver > /dev/null
  sleep 6
fi
echo "2) 기어 D, 출발"
put "/vehicle/gear?gear=Drive"; sleep 2; put "/vehicle/controls?speed=40&accelerator=20&brake=0"; sleep 4
echo "3) 계기판 $((START + DIST))km"
put "/vehicle/odometer?traveled_distance_km=$((START + DIST))"; sleep 3
echo "4) 정지, 기어 P"
put "/vehicle/controls?speed=0&accelerator=0&brake=100"; sleep 2; put "/vehicle/gear?gear=Park"; sleep 2
echo "5) 운전석 문 열림 (운행 종료)"
put "/vehicle/body?door_fl=true"; sleep 2
"$ADB" -e shell am start -n com.autolog.app/.ui.MainActivity > /dev/null 2>&1
echo "끝"
# 참고: 좌석 착석(/vehicle/seat?kind=occupied&area=driver&value=...)은 에뮬레이터 v2.1.0에서
# Virtual Console 표시와 Vehicle SDK 값이 반대로 나온다. 각본에서는 쓰지 않는다.
