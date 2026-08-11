// 시드 사용자들을 대기열에 넣고 ADMITTED가 된 것만 골라 토큰 파일을 갱신한다.
// 정원이 100이라 120명 중 최대 100명이 통과한다 — 그 상한 자체가 이 경로의 설계다.
const fs=require('fs');
const B='https://flow-ticket.com/api';
const EV=process.argv[2], IN=process.argv[3], OUT=process.argv[4];
const users=JSON.parse(fs.readFileSync(IN,'utf8'));
const H=(t)=>({'Content-Type':'application/json',Authorization:'Bearer '+t});
(async()=>{
  // 1) 전원 대기열 진입
  let issued=0;
  await Promise.all(users.map(async u=>{
    const r=await fetch(`${B}/events/${EV}/queue/token`,{method:'POST',headers:H(u.t)});
    if(r.status===200){ const j=await r.json(); u.q=j?.data?.token; if(u.q) issued++; }
  }));
  console.log('  대기열 토큰 발급: '+issued+'/'+users.length);
  // 2) 승격 대기 (워커 주기 1.5초)
  for(let round=0; round<12; round++){
    await new Promise(r=>setTimeout(r,2000));
    let admitted=0;
    await Promise.all(users.filter(u=>u.q).map(async u=>{
      const r=await fetch(`${B}/queue/status?token=${encodeURIComponent(u.q)}`,{headers:H(u.t)});
      if(r.status===200){ const j=await r.json(); u.status=j?.data?.status; if(u.status==='ADMITTED') admitted++; }
    }));
    console.log(`  [${(round+1)*2}s] ADMITTED ${admitted}`);
    if(admitted>=100) break;
  }
  const ok=users.filter(u=>u.status==='ADMITTED');
  fs.writeFileSync(OUT, JSON.stringify(ok));
  console.log('  최종 ADMITTED: '+ok.length+'명 → '+OUT);
})();
