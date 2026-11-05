let pw;try{pw=require('playwright')}catch(e){pw=require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright')}
const {chromium}=pw;
// 用法：node test_page.js   （需要 Node.js 和 playwright；CHROME 環境變數可指定瀏覽器路徑）
// 用假的資料平台資料把整個流程跑一遍：找站→選站→兩種車標籤→點車定位→放路障→路障鬧鐘→改更新頻率→刪路障
const fs=require('fs'),path=require('path');
const HTML_FILE=['垃圾車來了.html','index.html'].map(f=>path.join(__dirname,'..',f)).find(f=>fs.existsSync(f)); // 本機專案是 垃圾車來了.html，GitHub 上是 index.html
const html=fs.readFileSync(HTML_FILE,'utf8');
const STUB=fs.readFileSync(path.join(__dirname,'leaflet_stub.js'),'utf8');
const pad=n=>String(n).padStart(2,'0');const ts=()=>{const n=new Date();return `${n.getFullYear()}-${pad(n.getMonth()+1)}-${pad(n.getDate())} ${pad(n.getHours())}:${pad(n.getMinutes())}:${pad(n.getSeconds())}`};
const now=new Date(),today=(now.getDay()+6)%7,nm=now.getHours()*60+now.getMinutes(),t=m=>pad(Math.floor(m/60))+':'+pad(m%60);
const pts=[];for(let i=0;i<12;i++)pts.push({GISY:24.9+i*0.001,GISX:120.98+i*0.001,Point_ID:100+i,Route_ID:1,RouteName:'測試路線KEJ-6523(新)',Car_Number:'KEJ-6523影',PointName:'新竹縣新豐鄉測試路'+i+'號',Distance:Math.abs(8-i)*40,Details:[{Time:t(nm-6+i*3),RoutePeriod:[today],Week:'每週一、二、四、五、六'}]});
// 假的實際道路：每兩站之間塞 5 個點
const plan=[];for(let i=0;i<pts.length-1;i++)for(let q=0;q<5;q++)plan.push({Route_ID:1,Route_GISY:Math.round((pts[i].GISY+(pts[i+1].GISY-pts[i].GISY)*q/5+(q%2?0.0002:0))*1e6),Route_GISX:Math.round((pts[i].GISX+(pts[i+1].GISX-pts[i].GISX)*q/5)*1e6)});
plan.push({Route_ID:1,Route_GISY:Math.round(pts[pts.length-1].GISY*1e6),Route_GISX:Math.round(pts[pts.length-1].GISX*1e6)});
const basicOf=()=>[{Route_ID:1,RouteName:'測試路線KEJ-6523(新)',CarUnicode:'9',Car_Number:'KEJ-6523影',StartTime:'16:00',EndTime:'21:00',LineDetails:[],
  Points:pts.map((p,i)=>({Point_ID:p.Point_ID,PointName:p.PointName.replace('新竹縣新豐鄉',''),GISX:p.GISX,GISY:p.GISY,Seq:i+1,Details:JSON.parse(JSON.stringify(p.Details))}))}];
// 假的歷史軌跡：垃圾車從視窗開始 10 分鐘後一站一站走（每站 3 分鐘）；8 號回收車晚 4 分鐘跟著；77 號只在兩站出現、時間差很多
const parseDT=x=>new Date(x.replace(' ','T')+'+08:00').getTime();
const fmtLog=ms=>{const d=new Date(ms);return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`};
let histWinStart=null, noRecycle=false, noHist=false, detour=null; // detour：行車紀錄裡多幾個點（GPS 中斷時開過的地方） // noRecycle：模擬「這個星期幾沒有回收車跟著收」
const histTrack=p=>{const a=parseDT(p.StartTime),b=parseDT(p.EndTime); if(histWinStart===null||a<histWinStart-3600e3) histWinStart=a; const base=histWinStart;
  const out=[]; const add=(ms,lat,lng)=>{if(ms>=a&&ms<=b) out.push({Log_DTime:fmtLog(ms),Log_GISY:Math.round(lat*1e6),Log_GISX:Math.round(lng*1e6)});};
  pts.forEach((q,i)=>{const t0=base+10*60000+i*3*60000;
    if(p.Car_Unicode==='9') add(t0,q.GISY,q.GISX);
    if(p.Car_Unicode==='8'&&!noRecycle) add(t0+4*60000,q.GISY,q.GISX);
    if(p.Car_Unicode==='77'&&i<2) add(t0+50*60000,q.GISY,q.GISX);});
  if(detour&&p.Car_Unicode==='9') detour.forEach(d=>add(d.t,d.lat,d.lng));
  return out;};
const resp={GetAllRouteBasicData:basicOf(),GetRoutePlanDetail:plan,GetGisFromAddr:[{GISX:120.98,GISY:24.9}],GetCustIDByLocation:[{Cust_ID:'5009999',Team_ID:'5002785'}],GetRecommandPoint:pts,
 GetRemovalStatus:[{routeId:1,removealPointToArrivalId:102,reachMinutes:2,reachDistance:150,responsibleCar:'9'}],
 GetCarStatusGarbage:[{Car_Style:'垃圾車',Car_Unicode:'9',Car_Number:'KEJ-6523影',Log_GISX:120.9815,Log_GISY:24.9015,Log_DTime:ts()},{Car_Style:'資源回收車',Car_Unicode:'8',Car_Number:'KEC-2128影',Log_GISX:120.9812,Log_GISY:24.9013,Log_DTime:ts()},{Car_Style:'資源回收車',Car_Unicode:'77',Car_Number:'KEG-9999影',Log_GISX:120.9900,Log_GISY:24.8950,Log_DTime:ts()}]};
(async()=>{
 const b=await chromium.launch(process.env.CHROME?{executablePath:process.env.CHROME}:{});const pg=await b.newPage({viewport:{width:400,height:860},deviceScaleFactor:2});
 pg.on('pageerror',e=>console.log('PAGE ERROR',e.message));
 const H=async r=>{const u=r.request().url();
   if(u.startsWith('https://app.test/'))return r.fulfill({contentType:'text/html',body:html});
   if(u.includes('leaflet.min.js'))return r.fulfill({contentType:'text/javascript',body:STUB});
   if(u.includes('router.project-osrm.org')){const cs=u.split('/driving/')[1].split('?')[0].split(';').map(x=>x.split(',').map(Number));
     const legs=[];for(let i=0;i<cs.length-1;i++){const a=cs[i],b=cs[i+1],mid=[(a[0]+b[0])/2+0.00005,(a[1]+b[1])/2];const d=Math.hypot((b[0]-a[0])*101000,(b[1]-a[1])*110574);legs.push({distance:d*1.05,steps:[{geometry:{coordinates:[a,mid,b]}}]});}
     return r.fulfill({contentType:'application/json',body:JSON.stringify({code:'Ok',routes:[{legs}],waypoints:cs.map(()=>({distance:2}))})});}
   if(u.includes('eupfin')){const p=JSON.parse(decodeURIComponent(r.request().postData().slice(6)));
     if(p.MethodName==='GetHistoryTrack'){histWinStart=null;return r.fulfill({contentType:'application/json',body:JSON.stringify({status:1,result:noHist?[]:histTrack(p),error:''})});}return r.fulfill({contentType:'application/json',body:JSON.stringify({status:1,result:resp[p.MethodName]||[],error:''})});}
   return r.abort();};
 await pg.route('**/*',H);
 const txt=async s=>(await pg.locator(s).first().innerText()).replace(/\s+/g,' ');
 const refresh=async()=>{await pg.evaluate(()=>document.dispatchEvent(new Event('visibilitychange')));await pg.waitForTimeout(400);};
 await pg.goto('https://app.test/');await pg.waitForTimeout(300);
 await pg.fill('#addr','x');await pg.click('#searchbtn');await pg.waitForTimeout(300);await pg.click('#pick-0');await pg.click('#savebtn');await pg.waitForTimeout(500);
 // 回收車往前一點，在第 4、5 站之間
 resp.GetCarStatusGarbage[1]=Object.assign({},resp.GetCarStatusGarbage[1],{Log_GISX:120.9844,Log_GISY:24.9044,Log_DTime:ts()});
 resp.GetCarStatusGarbage[0]=Object.assign({},resp.GetCarStatusGarbage[0],{Log_GISX:120.9841,Log_GISY:24.9041,Log_DTime:ts()});
 resp.GetRemovalStatus[0].removealPointToArrivalId=104;
 await refresh();
 await pg.waitForTimeout(300);
 console.log('stops from official list:',await pg.evaluate(()=>{const h=JSON.parse(localStorage.getItem('ljt-live')).setup.homes[0];return h.stops.length+' stops, stopsV='+h.stopsV+', first seq='+h.stops[0].seq;}));
 await pg.evaluate(()=>window.ljtHist());await pg.waitForTimeout(600);
 console.log('recycle from history:',await txt('#chips-0 .chip.recycle'));
 console.log('learned from history:',await pg.evaluate(()=>JSON.stringify(JSON.parse(localStorage.getItem('ljt-live')).learnDow)));
 await pg.click('#tab-stop');await pg.waitForTimeout(150);
 console.log('recycle list:',(await pg.locator('#recyclelist .stopbtn .nm').allInnerTexts()).join(' / '));
 await pg.click('#recyclelist [data-rpick="8"]');await pg.waitForTimeout(150);
 console.log('picked:',await txt('#toast'));
 await pg.click('#tab-home');await pg.waitForTimeout(150);
 console.log('path saved:',await pg.evaluate(()=>{const h=JSON.parse(localStorage.getItem('ljt-live')).setup.homes[0];return h.path?('pts='+h.path.pts.length+' si='+h.path.si.join(',')):'none'}));
 console.log('lines:',await pg.evaluate(()=>JSON.stringify(window.__lines)),'| snapped:',await pg.evaluate(()=>JSON.parse(localStorage.getItem('ljt-live')).setup.homes[0].path.snapped));
 console.log('pathnote:',await txt('#pathnote-0'));
 console.log('list numbers:',(await pg.locator('#strip-0 .sno').allInnerTexts()).join(','));
 console.log('big:',await txt('#big-0'),'|',await txt('#sub-0'));
 console.log('where:',await txt('#where-0'));
 console.log('marks:',(await pg.locator('#strip-0 .mk.g, #strip-0 .mk.r').allInnerTexts()).join(' / '));
 await pg.screenshot({path:path.join(__dirname,'g1.png'),fullPage:true});
 await pg.evaluate(()=>window.__calls.length=0);
 await pg.click('#chips-0 .chip.recycle');await pg.waitForTimeout(1000);
 console.log('chip recycle →',await pg.evaluate(()=>window.__calls.filter(c=>/flyTo|openPopup/.test(c)).join(' | ')));
 await pg.evaluate(()=>window.__calls.length=0);
 await pg.click('button.gbtn');await pg.waitForTimeout(1000);
 console.log('btn garbage →',await pg.evaluate(()=>window.__calls.filter(c=>/flyTo|openPopup/.test(c)).join(' | ')));
 await pg.click('#fsbtn-0');await pg.waitForTimeout(200);
 const box=await pg.locator('#mapbox-0').boundingBox();
 console.log('fullscreen:',Math.round(box.width)+'x'+Math.round(box.height),'| buttons inside visible:',await pg.locator('#mapbox-0 button.gbtn').isVisible(),'| nav hidden:',!(await pg.locator('nav.tabs').isVisible()),'| label:',await txt('#fsbtn-0'));
 await pg.screenshot({path:path.join(__dirname,'g_full.png')});
 await pg.click('#mapbox-0 button.gbtn');await pg.waitForTimeout(300);
 console.log('still full after tap:',await pg.evaluate(()=>document.getElementById('mapbox-0').classList.contains('full')));
 await pg.click('#fsbtn-0');await pg.waitForTimeout(200);
 console.log('back to card:',Math.round((await pg.locator('#mapbox-0').boundingBox()).height),'| nav:',await pg.locator('nav.tabs').isVisible());
 await pg.click('#mapbox-0 button.stepbtn');await pg.waitForTimeout(150);
 console.log('step on:',await pg.locator('#stepbar-0').isVisible(),'|',await txt('#steptxt-0'),'| dim others:',await pg.evaluate(()=>document.getElementById('mapbox-0').classList.contains('stepping')));
 await pg.click('#stepnext-0');await pg.waitForTimeout(100);console.log('step next:',await txt('#steptxt-0'));
 await pg.click('#stepprev-0');await pg.click('#stepprev-0');await pg.click('#stepprev-0');await pg.click('#stepprev-0');await pg.waitForTimeout(100);
 console.log('step at first:',await txt('#steptxt-0'),'| prev disabled:',await pg.locator('#stepprev-0').isDisabled());
 await pg.click('#mapbox-0 .stepx');console.log('step off:',!(await pg.locator('#stepbar-0').isVisible()));
 console.log('nav above map:',await pg.evaluate(()=>{const nav=getComputedStyle(document.querySelector('nav.tabs')).zIndex, box=getComputedStyle(document.getElementById('mapbox-0'));return 'nav z='+nav+', map isolation='+box.isolation+', map z='+box.zIndex;}));
 console.log('map buttons:',(await pg.locator('#mapbox-0 .mapbtns button').allInnerTexts()).join(' / '));
 // 放路障在第 6 站
 await pg.click('#gatebtn-0');await pg.waitForTimeout(100);
 console.log('add bar:',await txt('#modebar-0'));
 console.log('gate btn:',await txt('#gatebtn-0'));
 await pg.evaluate(()=>window.__maps[0]._h.click({latlng:{lat:24.906,lng:120.986}}));await pg.waitForTimeout(200);
 console.log('toast:',await txt('#toast'));
 console.log('circle drawn:',await pg.evaluate(()=>window.__calls.filter(c=>c.startsWith('circle')).join(' ')));
 await pg.click('#startbtn');await pg.waitForTimeout(200);
 // 一次更新就跳過路障（從 4.1 站開到 7.5 站附近）
 await pg.waitForTimeout(1100);
 resp.GetCarStatusGarbage[0]=Object.assign({},resp.GetCarStatusGarbage[0],{Log_GISX:120.9875,Log_GISY:24.9075,Log_DTime:ts()});
 resp.GetRemovalStatus[0].removealPointToArrivalId=108;resp.GetRemovalStatus[0].reachDistance=300;
 await refresh();
 console.log('route alarm:',await pg.locator('#alarm').isVisible(),'|',await txt('#a-kind'),'|',await txt('#a-big'),'|',await txt('#a-sub'));
 await pg.screenshot({path:path.join(__dirname,'g2.png')});
 await pg.click('#a-ok');await pg.waitForTimeout(700);
 console.log('gate alarm:',await pg.locator('#alarm').isVisible(),'|',await txt('#a-kind'),'|',await txt('#a-big'),'|',await txt('#a-sub'));await pg.screenshot({path:path.join(__dirname,'g2.png')});await pg.click('#a-ok');
 await pg.click('button.playbtn');await pg.waitForTimeout(1500);console.log('play label:',await txt('#playlab-0'));await pg.click('button.playbtn');
 await pg.click('#tab-alert');await pg.click('#tab-settings');await pg.click('#pl60');await pg.waitForTimeout(100);
 await pg.click('#tab-home');await pg.waitForTimeout(200);
 // 視角：使用者縮放過後，路線重新下載也不能把地圖拉走
 await pg.dispatchEvent('#map-0','wheel');
 await pg.evaluate(()=>{window.__calls.length=0;});
 await pg.click('#gatedel-0');await pg.waitForTimeout(100);
 console.log('del mode:',await txt('#gatedel-0'),'|',await txt('#modebar-0'),'|',await pg.evaluate(()=>document.getElementById('mapbox-0').classList.contains('deleting')));
 await pg.evaluate(()=>Object.values(window.__gm).slice(-1)[0]._fire());await pg.waitForTimeout(150);
 console.log('after map delete:',await txt('#toast'),'| gates left:',await pg.evaluate(()=>JSON.parse(localStorage.getItem('ljt-live')).gates.length),'| mode btn:',await txt('#gatedel-0'));
 await pg.click('#toast .toastbtn');await pg.waitForTimeout(150);
 console.log('undo:',await txt('#toast'),'| gates:',await pg.evaluate(()=>JSON.parse(localStorage.getItem('ljt-live')).gates.length));
 // ===== 離線：垃圾車 10 分鐘沒回報、回收車整台從清單消失 =====
 const tsAgo=m=>{const n=new Date(Date.now()-m*60000);return `${n.getFullYear()}-${pad(n.getMonth()+1)}-${pad(n.getDate())} ${pad(n.getHours())}:${pad(n.getMinutes())}:${pad(n.getSeconds())}`};
 const keepCars=JSON.parse(JSON.stringify(resp.GetCarStatusGarbage));
 resp.GetCarStatusGarbage=[Object.assign({},keepCars[0],{Log_DTime:tsAgo(10)})];
 await refresh();
 console.log('offline chips:',(await pg.locator('#chips-0 .chip.offline').allInnerTexts()).map(t=>t.replace(/\s+/g,' ')).join(' || '));
 console.log('offline where:',await txt('#where-0'));
 console.log('offline sub:',await txt('#sub-0'));
 await pg.locator('#rc-0').screenshot({path:path.join(__dirname,'g_off.png')});
 await pg.evaluate(()=>window.__calls.length=0);
 await pg.click('#chips-0 .chip.offline.recycle');await pg.waitForTimeout(900);
 console.log('find offline recycle →',await pg.evaluate(()=>window.__calls.filter(c=>/flyTo/.test(c)).join(' ')),'|',await txt('#toast'));
 await pg.evaluate(()=>window.__calls.length=0);
 await pg.click('#mapbox-0 button.gbtn');await pg.waitForTimeout(900);
 console.log('find offline garbage →',await pg.evaluate(()=>window.__calls.filter(c=>/flyTo/.test(c)).join(' ')),'|',await txt('#toast'));
 console.log('remembered after reload:',await pg.evaluate(()=>{const m=JSON.parse(localStorage.getItem('ljt-lastpos'));return Object.keys(m.cars).length+' cars, route car='+Object.values(m.routes)[0].car+' mate='+Object.values(m.routes)[0].mate;}));
 resp.GetCarStatusGarbage=keepCars.map(c=>Object.assign({},c,{Log_DTime:ts()})); await refresh();
 console.log('back online:',await pg.locator('#chips-0 .chip.offline').count()===0);
 // ===== 自動判斷回收車：沿著路線走的 8 號，跟停在垃圾車旁邊（不在路線上）的 77 號 =====
 await pg.click('#tab-stop');await pg.click('#recyclelist [data-rpick=""]');await pg.click('#tab-home');
 const tsAt=m=>{const n=new Date(Date.now()+m*60000);return `${n.getFullYear()}-${pad(n.getMonth()+1)}-${pad(n.getDate())} ${pad(n.getHours())}:${pad(n.getMinutes())}:${pad(n.getSeconds())}`};
 for(let i=0;i<6;i++){
   resp.GetCarStatusGarbage=[Object.assign({},keepCars[0],{Log_GISY:24.9041+i*0.0003,Log_GISX:120.9841+i*0.0003,Log_DTime:tsAt(i)}),
     {Car_Style:'資源回收車',Car_Unicode:'8',Car_Number:'KEC-2128影',Log_GISY:24.9040+i*0.0003,Log_GISX:120.9840+i*0.0003,Log_DTime:tsAt(i)},
     {Car_Style:'資源回收車',Car_Unicode:'77',Car_Number:'KEG-9999影',Log_GISY:24.9046+i*0.0003,Log_GISX:120.9835+i*0.0003,Log_DTime:tsAt(i)}];
   await refresh();
   if(i===2) console.log('after 2 min:',await txt('#chips-0 .chip:nth-child(2)'));
 }
 console.log('auto recycle:',await txt('#chips-0 .chip:nth-child(2)'));
 console.log('learned:',await pg.evaluate(()=>JSON.stringify(JSON.parse(localStorage.getItem('ljt-live')).learnDow)));
 resp.GetCarStatusGarbage=keepCars.map(c=>Object.assign({},c,{Log_DTime:ts()})); await refresh();
 // ===== 班表同步：清潔隊把我家這站改到 +7 分鐘、星期改了 =====
 resp.GetAllRouteBasicData=basicOf(); const hp=resp.GetAllRouteBasicData[0].Points[8]; hp.Details[0].Time=t(nm-6+8*3+7); hp.Details[0].RoutePeriod=[0,2,4]; hp.Details[0].Week='每週一、三、五';
 await pg.evaluate(()=>window.ljtSync());await pg.waitForTimeout(500);
 console.log('schedule changed:',(await pg.locator('#schedchange').innerText()).replace(/\s+/g,' '),'| info:',await txt('#schedinfo'));
 await pg.click('#schedok');console.log('ack hides:',!(await pg.locator('#schedchange').isVisible()));
 // 我家這站被拿掉
 resp.GetAllRouteBasicData[0].Points.splice(8,1);
 await pg.evaluate(()=>window.ljtSync());await pg.waitForTimeout(500);
 console.log('home gone:',(await pg.locator('#schedchange').innerText()).replace(/\s+/g,' '),'| big:',await txt('#big-0'));
 resp.GetAllRouteBasicData=basicOf(); await pg.evaluate(()=>window.ljtSync());await pg.waitForTimeout(500);
 console.log('restored:',await txt('#big-0'));
 await pg.click('#tab-alert');
 console.log('poll toast:',await txt('#toast'),'| pressed:',await pg.getAttribute('#pl60','aria-pressed'));
 await pg.click('#tab-alert');
 console.log('alert cards:',(await pg.locator('#view-alert h2').allInnerTexts()).join(' / '));
 console.log('settings cards:',(await pg.locator('#view-settings h2').allInnerTexts()).join(' / '));
 console.log('tabs:',(await pg.locator('nav.tabs button').allInnerTexts()).join(' / '));
 console.log('gatelist:',await txt('#gatelist'));
 await pg.click('#gatelist .delbtn');console.log('arm:',await txt('#gatelist .delbtn'));await pg.click('#gatelist .delbtn');console.log('after delete:',await txt('#gatelist'));
 await pg.click('#tab-settings');await pg.click('#rm-order');await pg.waitForTimeout(150);
 await pg.evaluate(()=>{const t=document.getElementById('toast');if(t)t.hidden=true;});await pg.screenshot({path:path.join(__dirname,'g_set.png'),fullPage:true});
 console.log('order mode:',await txt('#rmhint'),'| order segments:',await pg.evaluate(()=>window.__poly.filter(p=>p.o&&p.o.className==='rorder').length>0));
 await pg.click('#as-l');await pg.click('#arrowsw [data-ac="#FFD400"]');await pg.waitForTimeout(100);
 console.log('arrow settings:',await pg.evaluate(()=>{const s=JSON.parse(localStorage.getItem('ljt-live'));return s.arrowSize+' '+s.arrowColor+' '+s.routeMode;}));
 await pg.evaluate(()=>{window.__poly=[];window.__lines={};});
 await pg.evaluate(()=>window.ljtSync());await pg.waitForTimeout(300); // 地圖藏著時重建
 const hiddenDrawn=await pg.evaluate(()=>window.__poly.filter(p=>p.o&&/rorder|rcase/.test(p.o.className||'')).length);
 await pg.click('#tab-home');await pg.waitForTimeout(300);
 const shownDrawn=await pg.evaluate(()=>window.__poly.filter(p=>p.o&&/rorder|rcase/.test(p.o.className||'')).length);
 console.log('map hidden → drawn later:',hiddenDrawn,'→',shownDrawn,'| ok:',shownDrawn>0,'| bugline hidden:',await pg.evaluate(()=>document.getElementById('bugline').hidden));
 await pg.click('#tab-settings');
 console.log('layers added before map view:',await pg.evaluate(()=>JSON.stringify(window.__layerBeforeView||[])),'| ok:',await pg.evaluate(()=>!(window.__layerBeforeView||[]).length));
 console.log('stored poll:',await pg.evaluate(()=>JSON.parse(localStorage.getItem('ljt-live')).poll));
 await pg.click('#tab-settings');await pg.screenshot({path:path.join(__dirname,'g3.png'),fullPage:true});

 // ===== 在 Android App 裡（假的 LJTApp）=====
 const pg2=await b.newPage({viewport:{width:400,height:860}});
 pg2.on('pageerror',e=>console.log('PAGE ERROR (app)',e.message));
 await pg2.addInitScript(()=>{window.__app=[];window.__trk=false;window.LJTApp={
   start:c=>{window.__app.push('start');window.__cfg=JSON.parse(c);window.__trk=true;}, update:c=>window.__app.push('update'), stop:()=>{window.__app.push('stop');window.__trk=false;},
   isTracking:()=>window.__trk, markFired:k=>window.__app.push('fired:'+k), firedKeys:()=>'[]', speak:t=>window.__app.push('speak:'+t), vibrate:()=>window.__app.push('vib'), openBatterySettings:()=>window.__app.push('battery')};});
 await pg2.route('**/*',H);
 await pg2.goto('https://app.test/');await pg2.waitForTimeout(300);
 await pg2.fill('#addr','x');await pg2.click('#searchbtn');await pg2.waitForTimeout(300);await pg2.click('#pick-0');await pg2.click('#savebtn');await pg2.waitForTimeout(500);
 await pg2.click('#startbtn');await pg2.waitForTimeout(300);
 const cfg=await pg2.evaluate(()=>window.__cfg);
 console.log('app start:',await pg2.evaluate(()=>window.__app.filter(x=>x==='start').length),'| homes:',cfg.homes.length,'stops:',cfg.homes[0].stops.length,'| note:',(await pg2.locator('#trackingon').innerText()).replace(/\s+/g,' '));
 console.log('app speak:',await pg2.evaluate(()=>window.__app.find(x=>x.startsWith('speak'))));
 console.log('app told about alarm:',await pg2.evaluate(()=>window.__app.filter(x=>x.startsWith('fired:')).map(x=>x.replace(/^fired:\w+ \w+ \d+ \d+\|/,'')).join(',')),'| page alarm:',await pg2.locator('#alarm').isVisible());
 for(let i=0;i<4&&await pg2.locator('#alarm').isVisible();i++){await pg2.click('#a-ok');await pg2.waitForTimeout(500);}
 await pg2.click('#tab-alert');console.log('battery btn visible:',await pg2.locator('#batterybtn').isVisible());
 await pg2.click('#thr-plus');await pg2.waitForTimeout(100);console.log('update after change:',await pg2.evaluate(()=>window.__app.includes('update')));
 console.log('back from alert tab:',await pg2.evaluate(()=>window.ljtBack()),'| back on home:',await pg2.evaluate(()=>window.ljtBack()));
 await pg2.click('#stopbtn');console.log('app stop:',await pg2.evaluate(()=>window.__app.includes('stop')),'| start button back:',await pg2.locator('#startbtn').isVisible());
 // ===== 這個星期幾沒有回收車：不能拿昨天（或之前別天）的回收車來顯示 =====
 {
  noRecycle=true;
  const pg4=await b.newPage({viewport:{width:400,height:860}});
  pg4.on('pageerror',e=>console.log('PAGE ERROR (norecycle)',e.message));
  await pg4.route('**/*',H);
  await pg4.goto('https://app.test/');await pg4.waitForTimeout(300);
  // 先放一筆「昨天學到的回收車」（舊版的 S.learn），新版不能拿來用
  await pg4.evaluate(()=>{const S=JSON.parse(localStorage.getItem('ljt-live')||'{}');S.learn={'1':{id:'8',plate:'KEC-2128影',day:'Mon Nov 02 2026',n:8}};localStorage.setItem('ljt-live',JSON.stringify(S));});
  await pg4.reload();await pg4.waitForTimeout(300);
  await pg4.fill('#addr','x');await pg4.click('#searchbtn');await pg4.waitForTimeout(300);await pg4.click('#pick-0');await pg4.click('#savebtn');await pg4.waitForTimeout(800);
  await pg4.evaluate(()=>window.ljtHist());await pg4.waitForTimeout(1500);
  const chipTxt=(await pg4.locator('#chips-0 .chip').nth(1).innerText()).replace(/\s+/g,' ');
  console.log('no recycle today chip:',chipTxt,'| ok:',/沒有回收車/.test(chipTxt)&&!/KEC-2128/.test(chipTxt));
  console.log('no recycle learnDow:',await pg4.evaluate(()=>JSON.stringify(JSON.parse(localStorage.getItem('ljt-live')).learnDow)));
  await pg4.click('#mapbox-0 ~ .mapbtns [data-type="recycle"], .mapbtns [data-type="recycle"]');await pg4.waitForTimeout(200);
  console.log('no recycle find button:',(await pg4.locator('#toast').innerText()));
  noRecycle=false; await pg4.close();
 }
 // ===== 清潔隊公告的班表：沒有行車紀錄時照班表；有新的班表公告時改用行車紀錄 =====
 {
  noHist=true; // 沒有行車紀錄：只能照班表
  resp.GetCustIDByLocation=[{Cust_ID:'5002889',Team_ID:'5002785'}];
  const dw=(new Date().getDay()+6)%7, exp={0:'KEC-2128',4:'KEC-2128',5:'KEC-2128',3:'KEU-1883',1:'none'}[dw];
  const runs=pts[0].Details[0].RoutePeriod.includes(dw);
  const pg5=await b.newPage({viewport:{width:400,height:860}});
  pg5.on('pageerror',e=>console.log('PAGE ERROR (official)',e.message));
  await pg5.route('**/*',H);
  await pg5.goto('https://app.test/');await pg5.waitForTimeout(300);
  await pg5.fill('#addr','x');await pg5.click('#searchbtn');await pg5.waitForTimeout(300);await pg5.click('#pick-0');await pg5.click('#savebtn');await pg5.waitForTimeout(800);
  const chip=(await pg5.locator('#chips-0 .chip').nth(1).innerText()).replace(/\s+/g,' ');
  const okOff=!runs||!exp?true:(exp==='none'?/沒有回收車（清潔隊班表）/.test(chip):chip.includes(exp));
  console.log('official chip (星期'+'一二三四五六日'[dw]+'):',chip,'| ok:',okOff);
  console.log('official schedinfo:',await pg5.locator('#schedinfo').innerText());
  // 清潔隊公告了新的班表：不再相信舊表
  resp.GetMsg=[{RM_ID:99999,RM_Title:'12月1日起清潔車號一覽表',RM_DateTime:'2026-11-28 00:00:00',image_Blob_data:[]}];
  await pg5.evaluate(()=>window.ljtSync());await pg5.waitForTimeout(800);
  const chip2=(await pg5.locator('#chips-0 .chip').nth(1).innerText()).replace(/\s+/g,' ');
  console.log('new notice banner:',(await pg5.locator('#noticeline').innerText()).replace(/\s+/g,' ').slice(0,60),'| chip no longer official:',!/清潔隊班表/.test(chip2));
  delete resp.GetMsg; resp.GetCustIDByLocation=[{Cust_ID:'5009999',Team_ID:'5002785'}]; noHist=false; await pg5.close();
 }
 // ===== 提醒點：GPS 中斷時經過也要響 =====
 {
  const tsMs=ms=>{const n=new Date(ms);return `${n.getFullYear()}-${pad(n.getMonth()+1)}-${pad(n.getDate())} ${pad(n.getHours())}:${pad(n.getMinutes())}:${pad(n.getSeconds())}`};
  const keep=JSON.parse(JSON.stringify(resp.GetCarStatusGarbage)), keepRS=JSON.parse(JSON.stringify(resp.GetRemovalStatus));
  const run=async(label,gate,opts)=>{
    const now=Date.now();
    // 第 1 次：垃圾車在第 4 站附近（4 分多鐘前），一打開就是這個位置
    resp.GetRemovalStatus=[{routeId:1,removealPointToArrivalId:105,reachMinutes:8,reachDistance:300,responsibleCar:'9'}];
    resp.GetCarStatusGarbage=[Object.assign({},keep[0],{Log_GISY:24.904,Log_GISX:120.984,Log_DTime:tsMs(now-4.2*60000)})];
    const pg6=await b.newPage({viewport:{width:400,height:860}});
    pg6.on('pageerror',e=>console.log('PAGE ERROR (gap)',e.message));
    await pg6.route('**/*',H);
    await pg6.goto('https://app.test/');await pg6.waitForTimeout(300);
    await pg6.fill('#addr','x');await pg6.click('#searchbtn');await pg6.waitForTimeout(300);await pg6.click('#pick-0');await pg6.click('#savebtn');await pg6.waitForTimeout(1500);
    await pg6.evaluate(g=>{const S=JSON.parse(localStorage.getItem('ljt-live'));S.gates=[g];S.gateOn=true;localStorage.setItem('ljt-live',JSON.stringify(S));},gate);
    await pg6.reload();await pg6.waitForTimeout(800);
    await pg6.click('#startbtn');await pg6.waitForTimeout(200);
    const closeAll=async()=>{for(let i=0;i<5&&await pg6.locator('#alarm').isVisible();i++){await pg6.click('#a-ok');await pg6.waitForTimeout(500);}};
    await pg6.evaluate(()=>document.dispatchEvent(new Event('visibilitychange')));await pg6.waitForTimeout(600);
    const before=await pg6.locator('#alarm').isVisible()&&/提醒點/.test(await pg6.locator('#a-big').innerText()+await pg6.locator('#a-kind').innerText());
    await closeAll();
    // GPS 斷 4 分鐘：第 2 次已經在第 9 站附近
    resp.GetRemovalStatus=[{routeId:1,removealPointToArrivalId:110,reachMinutes:2,reachDistance:100,responsibleCar:'9'}];
    resp.GetCarStatusGarbage=[Object.assign({},keep[0],{Log_GISY:24.909,Log_GISX:120.989,Log_DTime:tsMs(now-30000)})];
    if(opts.detour) detour=opts.detour(now);
    await pg6.evaluate(()=>document.dispatchEvent(new Event('visibilitychange')));await pg6.waitForTimeout(1500);
    let vis=false, sub=''; for(let i=0;i<5&&await pg6.locator('#alarm').isVisible();i++){ const t=await pg6.locator('#a-sub').innerText(); if(/提醒點/.test(await pg6.locator('#a-big').innerText())){vis=true;sub=t;break;} await pg6.click('#a-ok');await pg6.waitForTimeout(500);} 
    console.log(label,'| before gap:',before,'| alarm:',vis,'|',sub,'| ok:',!before&&vis&&opts.expect.test(sub));
    detour=null; await pg6.close();
  };
  noHist=true; // A：沒有行車紀錄，只能照路線推算
  await run('gap A (route)',{id:'ga',lat:24.907,lng:120.987,r:50,name:'測試路7號附近'},{expect:/可能已經經過/});
  noHist=false; // B：提醒點在路線外 120 公尺（路線推算不到），行車紀錄有開過去
  await run('gap B (history)',{id:'gb',lat:24.9065,lng:120.9885,r:40,name:'巷子裡'},{expect:/行車紀錄/,detour:now=>[{t:now-2.5*60000,lat:24.9064,lng:120.9884}]});
  resp.GetCarStatusGarbage=keep; resp.GetRemovalStatus=keepRS;
 }
 // ===== 規劃路線沒經過最後兩站：要照 GPS 軌跡接起來，不能畫灰色點線 =====
 {
  const pg3=await b.newPage({viewport:{width:400,height:860}});
  pg3.on('pageerror',e=>console.log('PAGE ERROR (offpath)',e.message));
  const fullPlan=resp.GetRoutePlanDetail; resp.GetRoutePlanDetail=plan.slice(0,9*5+1); // 規劃路線只到第 10 站
  await pg3.route('**/*',H);
  await pg3.goto('https://app.test/');await pg3.waitForTimeout(300);
  await pg3.fill('#addr','x');await pg3.click('#searchbtn');await pg3.waitForTimeout(300);await pg3.click('#pick-0');await pg3.click('#savebtn');await pg3.waitForTimeout(2500);
  const r=await pg3.evaluate(()=>{const h=JSON.parse(localStorage.getItem('ljt-live')).setup.homes[0];const P=h.path||{};
    return {v:P.v,fixed:P.fixed,conn:(P.conn||[]).length,si:(P.si||[]).join(','),n:(P.pts||[]).length,note:(document.getElementById('pathnote-0')||{}).textContent||'',
      rconn:(window.__poly||[]).filter(p=>p.o&&p.o.className==='rconn').length,before:(window.__layerBeforeView||[]).length};});
  console.log('offpath fixed:',JSON.stringify(r.fixed),'| left dotted:',r.conn,'| v:',r.v,'| si increasing:',r.si.split(',').map(Number).every((x,i,a)=>i===0||x>=a[i-1]),'| pts:',r.n);
  console.log('offpath note:',r.note.includes('第 11、12 站不在規劃路線上，照垃圾車上次實際開的路'),'| dotted lines drawn:',r.rconn,'| layers before view:',r.before);
  resp.GetRoutePlanDetail=fullPlan; await pg3.close();
 }
 await b.close();
})().catch(e=>{console.log('FAIL',e.message);process.exit(1)});
