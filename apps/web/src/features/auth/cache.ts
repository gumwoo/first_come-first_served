import type { QueryClient } from "@tanstack/react-query";
import { adminKeys } from "@/features/admin/queryKeys";
import { meKeys } from "@/features/order/queryKeys";

/**
 * 로그인 사용자에 종속된 캐시의 루트. 여기 없는 키는 공개 데이터로 본다.
 *
 * 쿼리 키에 사용자 식별자가 들어 있지 않기 때문에 이 목록이 필요하다. 키에 토큰을 섞는 방법도
 * 있지만 토큰은 재발급으로 바뀌므로 캐시 신원으로 쓰기에 맞지 않는다(같은 사용자가 계속 새 캐시를
 * 만든다). 사용자 종속 데이터는 로그아웃 시점에 지우는 쪽이 단순하다.
 */
const USER_SCOPED_ROOTS = [adminKeys.all, meKeys.all];

/**
 * 로그아웃 시 사용자 종속 캐시를 지운다.
 *
 * 지우지 않으면 같은 브라우저에서 다음 사용자가 로그인했을 때 이전 사용자의 응답이 잠깐 화면에
 * 남는다. staleTime이 0이라 곧 재조회되지만, 그 사이 다른 계정의 주문 목록이나 운영 지표가
 * 보이는 상태를 만들 이유가 없다.
 *
 * queryClient.clear()를 쓰지 않는다. 공개 목록(공연·검색)까지 버리면 로그아웃했다는 이유로
 * 로그인과 무관한 데이터를 다시 받게 된다.
 */
export function clearUserScopedCache(queryClient: QueryClient) {
  for (const root of USER_SCOPED_ROOTS) {
    queryClient.removeQueries({ queryKey: root });
  }
}
