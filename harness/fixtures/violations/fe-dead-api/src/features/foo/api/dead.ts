// VIOLATION: 정의만 하고 어디서도 호출하지 않는 API 함수 → 하네스가 실패해야 함
export const fetchSomethingUnused = () => fetch("/api/events");
