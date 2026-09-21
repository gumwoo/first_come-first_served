// 위반 fixture: 쿼리 키를 배열 리터럴로 적는다.
// 무효화하는 쪽이 ["demo","list"]로 적으면 이 키는 접두사가 달라 갱신되지 않는다.
import { useQuery } from "@tanstack/react-query";

export function useDemo(page: number) {
  return useQuery({
    queryKey: ["demo", "items", { page }],
    queryFn: async () => [],
  });
}
