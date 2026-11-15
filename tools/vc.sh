#!/bin/zsh
# Virtual Console에 차량 신호를 차례로 보낸다. 시나리오 점검용.
# 전제: 에뮬레이터 실행 중, adb forward tcp:8088 tcp:8088.
# 사용: ./tools/vc.sh <명령> [값] ...   명령을 여러 개 이어 쓸 수 있다.
#   gear Park|Reverse|Neutral|Drive    기어
#   speed <kph>                        속도 (0이면 브레이크를 밟은 상태로)
#   odo <km> | odo +<km>               계기판 거리 (줄일 수 없다). +는 현재 값에서 더한다
#   door open|close                    운전석 문
#   wait <초>                          기다림
#   state                              현재 기어·계기판·운전석 문 출력
# 예: ./tools/vc.sh door close gear Drive speed 40 odo +32 speed 0 gear Park door open
VC=http://127.0.0.1:8088
( while true; do curl -s -o /dev/null -X POST --max-time 2 "$VC/browser/session?client=autolog-check"; sleep 1; done ) &
HB=$!
trap "kill $HB 2>/dev/null" EXIT
sleep 2.5
put() {
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT --max-time 10 "$VC$1")
  [ "$code" = "200" ] || { echo "실패($code): $1"; exit 1; }
}
odometer() { curl -s --max-time 10 "$VC/vehicle/state" | python3 -c "import sys,json; print(int(json.load(sys.stdin)['odometer']['traveled_distance_km']))"; }
while [ $# -gt 0 ]; do
  case "$1" in
    gear)  put "/vehicle/gear?gear=$2"; echo "기어 $2"; shift 2; sleep 1.5 ;;
    speed) if [ "$2" = "0" ]; then put "/vehicle/controls?speed=0&accelerator=0&brake=100"; else put "/vehicle/controls?speed=$2&accelerator=20&brake=0"; fi
           echo "속도 $2"; shift 2; sleep 1.5 ;;
    odo)   case "$2" in +*) target=$(( $(odometer) + ${2#+} )) ;; *) target=$2 ;; esac
           put "/vehicle/odometer?traveled_distance_km=$target"; echo "계기판 ${target}km"; shift 2; sleep 2 ;;
    door)  if [ "$2" = "open" ]; then put "/vehicle/body?door_fl=true"; else put "/vehicle/body?door_fl=false"; fi
           echo "운전석 문 $2"; shift 2; sleep 1.5 ;;
    wait)  sleep "$2"; shift 2 ;;
    state) curl -s --max-time 10 "$VC/vehicle/state" | python3 -c "
import sys,json
s=json.load(sys.stdin)
print('기어', s['gear']['actual']['value'], '| 계기판', s['odometer']['traveled_distance_km'], 'km | 운전석 문', '열림' if s['body']['doors']['front_left'] else '닫힘')"
           shift ;;
    *) echo "모르는 명령: $1"; exit 1 ;;
  esac
done
