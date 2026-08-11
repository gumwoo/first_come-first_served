// 토큰 사전 발급. 측정 구간에 로그인(bcrypt CPU)이 섞이지 않게 미리 받아 파일로 둔다.
const fs=require('fs');
const B='https://flow-ticket.com/api', PW='LoadSeed!2026', N=120, CONC=8;
(async()=>{
  const out=[]; let i=1, fail=0;
  async function worker(){
    while(i<=N){
      const n=i++;
      const r=await fetch(B+'/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({email:`loadseed+${n}@example.com`,password:PW,remember:false})});
      if(r.status===200){ const t=(await r.json())?.data?.accessToken; if(t) out.push({n,t}); else fail++; }
      else fail++;
    }
  }
  await Promise.all(Array.from({length:CONC},worker));
  fs.writeFileSync(process.argv[2], JSON.stringify(out));
  console.log('  발급 '+out.length+'개 / 실패 '+fail);
})();
