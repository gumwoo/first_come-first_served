-- 위반 fixture: 존재하지 않는 문서 번호를 참조한다(TS-999).
-- 근거를 가리키는 척하는 주석은 근거가 없는 것보다 나쁘다 — 읽는 사람이 찾다가 빈손으로 돌아온다.
CREATE TABLE dangling_ref_demo (id bigserial PRIMARY KEY);
