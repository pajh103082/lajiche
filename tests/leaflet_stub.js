window.__calls=[];
(function(){const log=(n,a)=>window.__calls.push(n+':'+JSON.stringify(a).slice(0,80));
function chain(name){const o={_ll:null,addTo(){return o},bindPopup(){return o},on(e,f){o._h=f;return o},openPopup(){log(name+'.openPopup',[]);return o},
 getElement(){if(!o._el){o._el=document.createElementNS('http://www.w3.org/2000/svg','path');}return o._el},
 setLatLng(ll){o._ll=ll;return o},setIcon(){return o},setPopupContent(){return o},setZIndexOffset(){return o}};return o;}
window.L={map(id){const m={fitBounds(b){log('fitBounds',b);return m},on(){return m},flyTo(ll,z){log('flyTo',[ll,z]);return m},removeLayer(){return m},remove(){}};log('map',id);return m},
 tileLayer(){return chain('tile')},polyline(){return chain('poly')},circleMarker(){return chain('stop')},marker(ll){log('marker',ll);return chain('marker')},
 divIcon(o){return o},latLngBounds(a){return {pad(){return a}}}};})();
(function(){const base=L.map;L.map=function(id){const m=base(id);m._h={};m.on=function(e,f){e.split(' ').forEach(x=>m._h[x]=f);return m};m.closePopup=function(){};window.__maps=(window.__maps||[]).concat([m]);return m};
L.circle=function(ll,o){window.__calls.push('circle:'+JSON.stringify([ll,o.radius]));const c={addTo(){return c}};return c};})();
(function(){const pl=L.polyline;L.polyline=function(ll,o){const c=pl(ll,o);c._ll=ll;c.setLatLngs=function(x){c._ll=x;window.__lines=window.__lines||{};window.__lines[o&&o.className]=x.length;return c};return c};})();
(function(){const mk=L.marker;L.marker=function(ll,o){const c=mk(ll,o);const hs=[];c.on=function(e,f){hs.push(f);return c};c._fire=()=>hs.forEach(f=>f());if(o&&o.title)(window.__gm=window.__gm||{})[o.title]=c;return c};
L.popup=function(){const p={setLatLng(){return p},setContent(){return p},openOn(){window.__calls.push('popup');return p}};return p};
const bm=L.map;L.map=function(id){const m=bm(id);m.getCenter=()=>({lat:1,lng:2});m.getZoom=()=>15;m.setView=(c,z)=>{window.__calls.push('setView:'+z);return m};return m};})();
(function(){const bm=L.map;L.map=function(id){const m=bm(id);m.invalidateSize=()=>{window.__calls.push('invalidate');return m};return m};})();
(function(){
 const grp=()=>{const g={_l:[],addTo(){return g},clearLayers(){g._l=[];return g},addLayer(x){g._l.push(x);return g}};return g};
 L.layerGroup=grp;
 const bm=L.map;L.map=function(id){const m=bm(id);m._z=15;m.getZoom=()=>m._z;m.getContainer=()=>document.getElementById(id);
  m.getBounds=()=>({pad(){return {contains(){return true}}}});
  m.latLngToLayerPoint=ll=>{const a=Array.isArray(ll)?ll:[ll.lat,ll.lng];return {x:a[1]*1e5,y:-a[0]*1e5}};
  m.fitBounds=b=>{window.__calls.push('fitBounds');return m};
  (window.__mapObjs=window.__mapObjs||[]).push(m);return m};
 const pl=L.polyline;L.polyline=function(ll,o){const c=pl(ll,o);(window.__poly=window.__poly||[]).push({o,c});return c};
})();
// 檢查「地圖還沒定視角就加圖層」：真的 Leaflet 會把這些圖層排隊，定視角時灰色點線比 SVG 畫布先加，
// 會出錯（2026-11-02 地圖失效）。initMap 必須先 setView/fitBounds 再加圖層。
(function(){
 const bm=L.map;L.map=function(id){const m=bm(id);m._viewSet=false;
  const sv=m.setView,fb=m.fitBounds;m.setView=function(){m._viewSet=true;return sv.apply(m,arguments)};m.fitBounds=function(){m._viewSet=true;return fb.apply(m,arguments)};return m};
 const wrap=name=>{const f=L[name];L[name]=function(){const o=f.apply(this,arguments);const add=o.addTo;
  o.addTo=function(t){ if(t&&t._viewSet===false&&name!=='tileLayer'){(window.__layerBeforeView=window.__layerBeforeView||[]).push(name);} return add?add.apply(o,arguments):o; };return o}};
 ['polyline','layerGroup','marker','circle'].forEach(wrap);
})();
