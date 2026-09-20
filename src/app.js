import { SimulatedNode, NetworkSimulator } from './simulation/network_simulator.js';
import { NodeRole } from './mesh/peer.js';
import { MeshPacket, PacketType, UrgencyLevel } from './protocol/packet.js';

// DOM Elemanları
const canvas = document.getElementById('simCanvas');
const ctx = canvas.getContext('2d');
const statTotalEl = document.getElementById('statTotal');
const statHopsEl = document.getElementById('statHops');
const statDeliveredEl = document.getElementById('statDelivered');
const logFeedEl = document.getElementById('logFeed');
const btnSendSos = document.getElementById('btnSendSos');
const btnAddCourier = document.getElementById('btnAddCourier');
const btnAddVictim = document.getElementById('btnAddVictim');
const btnReset = document.getElementById('btnReset');
const packetInspectorEl = document.getElementById('packetInspector');
const triageListEl = document.getElementById('triageList');

// Simülatör Hazırlığı
const simulator = new NetworkSimulator({ width: 900, height: 550 });

// Canvas Boyutlandırma
function resizeCanvas() {
  const rect = canvas.parentElement.getBoundingClientRect();
  canvas.width = rect.width;
  canvas.height = Math.max(480, rect.height || 520);
  simulator.bounds.width = canvas.width;
  simulator.bounds.height = canvas.height;
}
window.addEventListener('resize', resizeCanvas);
resizeCanvas();

// Başlangıç Düğümleri Kurulumu
function initDefaultScenario() {
  simulator.reset();

  // 1. Kurtarma Merkezi (Gateway / Sink Node - Uydu Bağlantılı AFAD/AKUT Aracı)
  const gateway = new SimulatedNode({
    id: 'AFAD-UYDU-01',
    name: 'AFAD Uydu Terminali (Sink)',
    role: NodeRole.GATEWAY,
    x: canvas.width - 90,
    y: 90,
    radioRange: 130,
    speed: 0
  });
  simulator.addNode(gateway);

  // 2. Enkaz Altındaki Kazazedeler (Victim Beacons)
  const victim1 = new SimulatedNode({
    id: 'ENKAZ-BLOK-A',
    name: 'Kazazede (2 Kişi - 3. Kat)',
    role: NodeRole.VICTIM,
    x: 90,
    y: canvas.height - 100,
    radioRange: 55,
    battery: 84,
    victimStatus: { isTrapped: true, isInjured: true, isConscious: true, peopleCount: 2 }
  });
  simulator.addNode(victim1);
  victim1.createSOS('Enkaz altındayız, 1 kişi bacağından yaralı!');

  const victim2 = new SimulatedNode({
    id: 'ENKAZ-BLOK-C',
    name: 'Kazazede (1 Kişi)',
    role: NodeRole.VICTIM,
    x: 120,
    y: 120,
    radioRange: 50,
    battery: 62,
    victimStatus: { isTrapped: true, isInjured: false, isConscious: true, peopleCount: 1 }
  });
  simulator.addNode(victim2);
  victim2.createSOS('Bodrum kattayız, su sızıntısı var.');

  // 3. Bölgede Hareket Eden Vatandaşlar & Gönüllü Kuryeler (Data Mules)
  const courierPositions = [
    { x: 220, y: 300, name: 'Vatandaş Kurye #1' },
    { x: 380, y: 180, name: 'Gönüllü Devriye #2' },
    { x: 520, y: 380, name: 'Arama Ekibi Öncüsü #3' },
    { x: 680, y: 220, name: 'İHA / Dron Kurye #4', speed: 2.8, radioRange: 90 }
  ];

  courierPositions.forEach((cp, idx) => {
    const courier = new SimulatedNode({
      id: `KURYEDEV-${idx + 1}`,
      name: cp.name,
      role: NodeRole.COURIER,
      x: cp.x,
      y: cp.y,
      radioRange: cp.radioRange || 65,
      speed: cp.speed || 1.4
    });
    simulator.addNode(courier);
  });

  addLogEntry('Ağ senaryosu başlatıldı. 2 Enkaz Feneri, 4 Kurye ve 1 Uydu Merkezi aktif.', 'normal');
}

// Log Ekleme
function addLogEntry(text, type = 'normal') {
  if (!logFeedEl) return;
  const time = new Date().toLocaleTimeString();
  const div = document.createElement('div');
  div.className = `feed-item ${type}`;
  div.innerHTML = `<span class="feed-time">[${time}]</span> ${text}`;
  logFeedEl.prepend(div);

  // Maksimum 20 kayıt tut
  while (logFeedEl.children.length > 20) {
    logFeedEl.removeChild(logFeedEl.lastChild);
  }
}

// Kurtarma Merkezine Paket Ulaştığında
simulator.onDeliveryCallback = (packet) => {
  addLogEntry(`🚨 <b>SOS ULAŞTI!</b> ${packet.senderId} enkaz sinyali uydu merkezine aktarıldı! (${packet.hopCount} sıçrama)`, 'delivered');
  updateTriageList();
  updatePacketInspector(packet);
};

// Triage Listesini Güncelle
function updateTriageList() {
  if (!triageListEl) return;
  const delivered = simulator.stats.deliveredPackets;
  if (delivered.length === 0) {
    triageListEl.innerHTML = '<div style="color:var(--text-muted); font-size:0.8rem; text-align:center; padding:1rem;">Henüz merkeze ulaşan paket yok. Kuryelerin enkaz yakınına geçmesi bekleniyor...</div>';
    return;
  }

  triageListEl.innerHTML = delivered.map(item => {
    const p = item.packet;
    const routeStr = item.route.join(' ➔ ');
    return `
      <div style="background:rgba(239, 68, 68, 0.1); border:1px solid rgba(239, 68, 68, 0.3); border-radius:8px; padding:0.75rem; margin-bottom:0.5rem; font-size:0.8rem;">
        <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
          <strong style="color:#f87171;">${p.senderId}</strong>
          <span style="background:#ef4444; color:#fff; font-size:0.65rem; padding:2px 6px; border-radius:4px; font-weight:700;">KRİTİK SOS</span>
        </div>
        <div style="color:var(--text-primary); margin-bottom:4px;">"${p.payload}"</div>
        <div style="color:var(--text-secondary); font-size:0.72rem; margin-bottom:4px;">
          Kişi Sayısı: <b>${p.status.peopleCount}</b> | Yaralı: <b>${p.status.isInjured ? 'EVET' : 'HAYIR'}</b> | Pil: <b>%${p.status.batteryPercent}</b>
        </div>
        <div style="font-family:var(--font-mono); font-size:0.68rem; color:var(--accent-cyan);">
          Rota: ${routeStr} (${item.totalHops} hop)
        </div>
      </div>
    `;
  }).join('');
}

// Paket İkili Formatını Göster
function updatePacketInspector(packet) {
  if (!packetInspectorEl || !packet) return;
  const byteSize = packet.getEstimatedByteSize();
  const hexSample = packet.id.toUpperCase();

  packetInspectorEl.innerHTML = `
    <div style="font-family:var(--font-mono); font-size:0.75rem; line-height:1.6;">
      <div style="color:var(--accent-cyan); font-weight:700; margin-bottom:4px;">[DMPv1 BLE FRAME - ${byteSize} BYTE]</div>
      <div><b>Magic:</b> 0xD54D | <b>Ver:</b> 0x01 | <b>Type:</b> 0x${packet.type.toString(16).padStart(2,'0')}</div>
      <div><b>UUID:</b> ${hexSample} | <b>TTL:</b> ${packet.ttl} | <b>Hop:</b> ${packet.hopCount}</div>
      <div><b>GPS:</b> ${packet.latitude.toFixed(5)}, ${packet.longitude.toFixed(5)}</div>
      <div><b>Flags:</b> Trapped=${packet.status.isTrapped?1:0}, Injured=${packet.status.isInjured?1:0}, People=${packet.status.peopleCount}</div>
      <div style="color:var(--warning-amber); margin-top:4px;"><b>Payload:</b> "${packet.payload}"</div>
    </div>
  `;
}

// Görsel Çizim Döngüsü
let lastTime = performance.now();
function render(currentTime) {
  const dt = Math.min(0.1, (currentTime - lastTime) / 1000);
  lastTime = currentTime;

  simulator.step(dt);

  // İstatistikleri Güncelle
  if (statTotalEl) statTotalEl.textContent = simulator.stats.totalGenerated;
  if (statHopsEl) statHopsEl.textContent = simulator.stats.totalHops;
  if (statDeliveredEl) statDeliveredEl.textContent = simulator.stats.totalDelivered;

  // Çizim
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // 1. Izgara ve Koordinat Arka Planı
  drawGrid();

  // 2. Kapsama Alanları & Sinyal Dalgaları
  const now = currentTime / 1000;
  for (const node of simulator.nodes) {
    drawNodeCoverage(node, now);
  }

  // 3. Paket Sıçrama / Aktarım Animasyonları
  for (const trans of simulator.activeTransmissions) {
    drawTransmissionArc(trans);
  }

  // 4. Düğümler (İkonlar ve Etiketler)
  for (const node of simulator.nodes) {
    drawNode(node);
  }

  requestAnimationFrame(render);
}

function drawGrid() {
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)';
  ctx.lineWidth = 1;
  const gridSize = 40;
  for (let x = 0; x < canvas.width; x += gridSize) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, canvas.height);
    ctx.stroke();
  }
  for (let y = 0; y < canvas.height; y += gridSize) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(canvas.width, y);
    ctx.stroke();
  }
}

function drawNodeCoverage(node, t) {
  ctx.save();
  ctx.beginPath();
  ctx.arc(node.x, node.y, node.radioRange, 0, Math.PI * 2);

  if (node.role === NodeRole.VICTIM) {
    ctx.fillStyle = 'rgba(239, 68, 68, 0.04)';
    ctx.strokeStyle = 'rgba(239, 68, 68, 0.25)';
    ctx.setLineDash([4, 4]);
  } else if (node.role === NodeRole.GATEWAY) {
    ctx.fillStyle = 'rgba(16, 185, 129, 0.06)';
    ctx.strokeStyle = 'rgba(16, 185, 129, 0.35)';
  } else {
    ctx.fillStyle = 'rgba(168, 85, 247, 0.04)';
    ctx.strokeStyle = 'rgba(168, 85, 247, 0.2)';
    ctx.setLineDash([2, 4]);
  }

  ctx.fill();
  ctx.stroke();

  // Fener Sinyal Dalgalanması (Pulse effect)
  if (node.role === NodeRole.VICTIM) {
    const pulseR = (t * 25) % node.radioRange;
    ctx.beginPath();
    ctx.arc(node.x, node.y, pulseR, 0, Math.PI * 2);
    ctx.strokeStyle = `rgba(239, 68, 68, ${1 - pulseR / node.radioRange})`;
    ctx.stroke();
  }

  ctx.restore();
}

function drawTransmissionArc(trans) {
  ctx.save();
  const curX = trans.fromX + (trans.toX - trans.fromX) * trans.progress;
  const curY = trans.fromY + (trans.toY - trans.fromY) * trans.progress;

  // Işık çizgisi
  ctx.beginPath();
  ctx.moveTo(trans.fromX, trans.fromY);
  ctx.lineTo(curX, curY);
  ctx.strokeStyle = 'rgba(6, 182, 212, 0.8)';
  ctx.lineWidth = 2;
  ctx.shadowColor = '#06b6d4';
  ctx.shadowBlur = 8;
  ctx.stroke();

  // Uçan paket parçacığı
  ctx.beginPath();
  ctx.arc(curX, curY, 4, 0, Math.PI * 2);
  ctx.fillStyle = '#ffffff';
  ctx.fill();

  ctx.restore();
}

function drawNode(node) {
  ctx.save();

  let color = '#3b82f6';
  let badgeText = 'K';

  if (node.role === NodeRole.VICTIM) {
    color = '#ef4444';
    badgeText = 'SOS';
  } else if (node.role === NodeRole.GATEWAY) {
    color = '#10b981';
    badgeText = 'SINK';
  } else {
    color = '#a855f7';
    badgeText = 'MULE';
  }

  // Düğüm Çemberi
  ctx.beginPath();
  ctx.arc(node.x, node.y, 14, 0, Math.PI * 2);
  ctx.fillStyle = color;
  ctx.shadowColor = color;
  ctx.shadowBlur = 10;
  ctx.fill();

  // Kenarlık
  ctx.lineWidth = 2;
  ctx.strokeStyle = '#ffffff';
  ctx.stroke();

  // Merkez Harf
  ctx.font = 'bold 9px var(--font-sans)';
  ctx.fillStyle = '#ffffff';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(badgeText, node.x, node.y);

  // İsim ve Pil Bilgisi
  ctx.font = '10px var(--font-sans)';
  ctx.fillStyle = '#e5e7eb';
  ctx.shadowBlur = 0;
  ctx.fillText(node.name, node.x, node.y + 24);

  // Kurye üzerindeki taşınan paket rozeti
  const qSize = node.queue.size();
  if (qSize > 0 && node.role === NodeRole.COURIER) {
    ctx.beginPath();
    ctx.arc(node.x + 10, node.y - 10, 7, 0, Math.PI * 2);
    ctx.fillStyle = '#f59e0b';
    ctx.fill();
    ctx.font = 'bold 8px var(--font-mono)';
    ctx.fillStyle = '#000000';
    ctx.fillText(qSize, node.x + 10, node.y - 10);
  }

  ctx.restore();
}

// Buton Etkileşimleri
if (btnSendSos) {
  btnSendSos.addEventListener('click', () => {
    const text = document.getElementById('sosMessage')?.value || 'Enkaz altındayız, yardım!';
    const people = parseInt(document.getElementById('peopleCount')?.value || '2', 10);
    const injured = document.getElementById('hasInjury')?.checked ?? true;

    // Haritanın sol alt köşesine yeni bir kazazede ekle
    const id = `ENKAZ-${Math.floor(Math.random()*900)+100}`;
    const newVictim = new SimulatedNode({
      id: id,
      name: `Yeni Kazazede (${people} Kişi)`,
      role: NodeRole.VICTIM,
      x: Math.random() * 200 + 40,
      y: Math.random() * (canvas.height - 150) + 70,
      radioRange: 55,
      battery: 95,
      victimStatus: { isTrapped: true, isInjured: injured, isConscious: true, peopleCount: people }
    });

    simulator.addNode(newVictim);
    const p = newVictim.createSOS(text);
    simulator.stats.totalGenerated++;

    addLogEntry(`🚨 Yeni SOS yayını oluşturuldu: [${id}] "${text}"`, 'critical');
    updatePacketInspector(p);
  });
}

if (btnAddCourier) {
  btnAddCourier.addEventListener('click', () => {
    const idx = simulator.nodes.filter(n => n.role === NodeRole.COURIER).length + 1;
    const courier = new SimulatedNode({
      id: `KURYEDEV-${idx}`,
      name: `Kurye Devriye #${idx}`,
      role: NodeRole.COURIER,
      x: Math.random() * (canvas.width - 200) + 100,
      y: Math.random() * (canvas.height - 200) + 100,
      radioRange: 65,
      speed: 1.6
    });
    simulator.addNode(courier);
    addLogEntry(`🏃 Yeni hareketli kurye eklendi: ${courier.name}`, 'normal');
  });
}

if (btnAddVictim) {
  btnAddVictim.addEventListener('click', () => {
    btnSendSos.click();
  });
}

if (btnReset) {
  btnReset.addEventListener('click', () => {
    initDefaultScenario();
  });
}

// Canvas Tıklaması ile Düğüm Ekleme
canvas.addEventListener('click', (e) => {
  const rect = canvas.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const y = e.clientY - rect.top;

  // Yeni bir kuryeyi o noktaya yönlendir veya yeni kurye bırak
  const courier = new SimulatedNode({
    id: `KURYEDEV-${Math.floor(Math.random()*900)+100}`,
    name: 'Gönüllü Vatandaş',
    role: NodeRole.COURIER,
    x: x,
    y: y,
    radioRange: 65,
    speed: 1.5
  });
  simulator.addNode(courier);
  addLogEntry(`📍 Koordinata (${Math.round(x)}, ${Math.round(y)}) kurye yerleştirildi.`, 'normal');
});

// Başlangıç
initDefaultScenario();
requestAnimationFrame(render);
