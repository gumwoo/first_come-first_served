// VIOLATION: 프레젠테이션(components/ui)이 features를 import → FE 계층 위반
import { login } from "@/features/auth/api/auth";

export function BadButton() {
  return login;
}
