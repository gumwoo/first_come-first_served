import QueueView from "./QueueView";

// 대기열 화면은 정적 경로로 둔다. 화면 자체는 클라이언트 컴포넌트(QueueView)라 서버가 요청마다 그릴 것이 없는데,
// 동적 경로([id])는 기본이 "요청마다 서버 렌더"라 오픈 순간 화면 이동(RSC 요청)이 web 렌더로 몰렸다(로컬 프로덕션 빌드로 확인).
// 공연 id는 미리 알 수 없어 빌드 때 만들지 않고(generateStaticParams가 빈 목록), 처음 요청된 id만 한 번 만들어 캐시한다.
export const dynamic = "force-static";
export const dynamicParams = true;

export function generateStaticParams() {
  return [];
}

export default function QueuePage() {
  return <QueueView />;
}
