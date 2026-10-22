# 1초마다 컨테이너의 cgroup CPU 통계를 한 줄씩 쓴다: "<UTC 시각> usage_usec=.. nr_periods=.. nr_throttled=.. throttled_usec=.."
#   bash cgroup-sampler.sh <컨테이너 전체 id> <초>
# 컨테이너 안에서 읽으면 그 컨테이너의 CPU 할당량에 같이 묶이므로, 호스트 cgroup 이름공간의 별도 컨테이너에서 읽는다(lab.sh).
id=$1 secs=$2
f=/sys/fs/cgroup/docker/$id/cpu.stat
end=$(( $(date +%s) + secs ))
while [ "$(date +%s)" -lt "$end" ]; do
  echo "$(date -u +%FT%T.%3NZ) $(awk '$1 ~ /^(usage_usec|nr_periods|nr_throttled|throttled_usec)$/ {printf "%s=%s ", $1, $2}' "$f")"
  sleep 1
done
