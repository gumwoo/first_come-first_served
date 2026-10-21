# 0.2초마다 8KB 쓰기 + fdatasync 지연(ms)을 한 줄씩 쓴다: "<UTC 시각> <ms>"
# 측정 대상과 분리된 컨테이너에서 돌려, 호스트 저장소가 느린 run을 가려낸다(IMP-043).
while true; do
  t0=$(date +%s%N)
  dd if=/dev/zero of=/tmp/p bs=8k count=1 oflag=dsync conv=notrunc 2>/dev/null
  t1=$(date +%s%N)
  echo "$(date -u +%FT%T.%3NZ) $(( (t1 - t0) / 1000000 ))"
  sleep 0.2
done
