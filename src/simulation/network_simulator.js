/**
 * Afet Mesh Ağı & Kurye Simülatörü (Disaster Mesh & Data Mule Simulator)
 * Enkaz altındaki kazazedelerin, hareket eden kuryelerin ve kurtarma merkezinin
 * fiziksel mesafeler, paket sıçramaları (hop) ve paket teslimatlarını simüle eder.
 */

import { MeshPacket, PacketType, UrgencyLevel } from '../protocol/packet.js';
import { StoreAndForwardQueue } from '../mesh/store_forward.js';
import { PeerManager, NodeRole } from '../mesh/peer.js';

export class SimulatedNode {
  constructor(options) {
    this.id = options.id;
    this.role = options.role; // VICTIM | COURIER | GATEWAY
    this.name = options.name || this.id;
    this.x = options.x || 0; // Metre cinsinden koordinat
    this.y = options.y || 0;
    this.targetX = this.x;
    this.targetY = this.y;
    this.speed = options.speed || (this.role === NodeRole.COURIER ? 1.5 : 0); // m/s
    this.radioRange = options.radioRange || (this.role === NodeRole.GATEWAY ? 120 : 50); // BLE ~50m, Wi-Fi ~120m
    this.battery = options.battery !== undefined ? options.battery : 100;
    this.victimStatus = options.victimStatus || null;
    
    this.queue = new StoreAndForwardQueue();
    this.peerManager = new PeerManager(this.id, this.role);
    this.peerManager.batteryLevel = this.battery;

    this.createdPacketsCount = 0;
    this.relayedPacketsCount = 0;
    this.deliveredPacketsCount = 0;
  }

  update(dt, bounds) {
    // 1. Pil Tüketimi (Duty cycle modeline göre hafif drenaj)
    if (this.battery > 0) {
      const drainRate = this.role === NodeRole.VICTIM ? 0.002 : 0.005;
      this.battery = Math.max(0, this.battery - drainRate * dt);
      this.peerManager.batteryLevel = this.battery;
    }

    // 2. Kurye Hareketi (Data Mule Patrol)
    if (this.role === NodeRole.COURIER && this.speed > 0) {
      const dx = this.targetX - this.x;
      const dy = this.targetY - this.y;
      const dist = Math.sqrt(dx * dx + dy * dy);

      if (dist < 5) {
        // Yeni bir hedef belirle (Afet bölgesinde devriye)
        this.targetX = Math.random() * (bounds.width - 100) + 50;
        this.targetY = Math.random() * (bounds.height - 100) + 50;
      } else {
        this.x += (dx / dist) * this.speed * dt * 10;
        this.y += (dy / dist) * this.speed * dt * 10;
      }
    }
  }

  // Kazazede için yeni bir SOS paketi üret
  createSOS(message = 'Enkaz altındayız, acil yardım!') {
    const packet = new MeshPacket({
      type: PacketType.CRITICAL_SOS,
      senderId: this.id,
      latitude: 41.0082 + (this.y / 111000), // Simüle edilmiş enlem
      longitude: 28.9784 + (this.x / 111000), // Simüle edilmiş boylam
      isTrapped: this.victimStatus?.isTrapped ?? true,
      isInjured: this.victimStatus?.isInjured ?? false,
      isConscious: this.victimStatus?.isConscious ?? true,
      peopleCount: this.victimStatus?.peopleCount || 2,
      batteryPercent: Math.round(this.battery),
      urgency: UrgencyLevel.CRITICAL,
      ttl: 12,
      payload: message
    });

    this.queue.enqueue(packet);
    this.createdPacketsCount++;
    return packet;
  }
}

export class NetworkSimulator {
  constructor(bounds = { width: 900, height: 600 }) {
    this.bounds = bounds;
    this.nodes = [];
    this.activeTransmissions = []; // Görselleştirme için paket aktarım animasyonları
    this.stats = {
      totalGenerated: 0,
      totalHops: 0,
      totalDelivered: 0,
      deliveredPackets: []
    };
    this.isRunning = true;
    this.onDeliveryCallback = null;
  }

  addNode(node) {
    this.nodes.push(node);
    return node;
  }

  step(dt = 0.1) {
    if (!this.isRunning) return;

    // 1. Düğümleri güncelle
    for (const node of this.nodes) {
      node.update(dt, this.bounds);
    }

    // 2. Kapsama Alanı ve Karşılaşma (Encounter / P2P Exchange)
    const n = this.nodes.length;
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const nodeA = this.nodes[i];
        const nodeB = this.nodes[j];

        if (nodeA.battery <= 0 || nodeB.battery <= 0) continue;

        const dx = nodeA.x - nodeB.x;
        const dy = nodeA.y - nodeB.y;
        const dist = Math.sqrt(dx * dx + dy * dy);
        const maxRange = Math.max(nodeA.radioRange, nodeB.radioRange);

        if (dist <= maxRange) {
          // İki cihaz kapsama alanında -> Veri takası (Epidemic Sync)
          this.exchangePackets(nodeA, nodeB);
        }
      }
    }

    // 3. Aktarım animasyonlarını temizle
    this.activeTransmissions = this.activeTransmissions.filter(t => {
      t.progress += dt * 2.5;
      return t.progress < 1.0;
    });
  }

  exchangePackets(nodeA, nodeB) {
    this.syncOneWay(nodeA, nodeB);
    this.syncOneWay(nodeB, nodeA);
  }

  syncOneWay(sender, receiver) {
    // Receiver'ın henüz bilmediği paketleri belirle
    const knownIds = receiver.queue.getInventoryIds();
    const packetsToSend = sender.queue.getPacketsToSync(knownIds, 3);

    for (const packet of packetsToSend) {
      // Kopya oluştur ve sıçramayı kaydet
      const packetCopy = MeshPacket.deserialize(packet.serialize());
      const hopped = packetCopy.hop(receiver.id);
      
      if (hopped) {
        const added = receiver.queue.enqueue(packetCopy);
        if (added) {
          sender.relayedPacketsCount++;
          this.stats.totalHops++;

          // Görsel aktarım efekti için kaydet
          this.activeTransmissions.push({
            fromX: sender.x,
            fromY: sender.y,
            toX: receiver.x,
            toY: receiver.y,
            type: packet.type,
            progress: 0.0
          });

          // Eğer alıcı bir Kurtarma Merkezi (GATEWAY) ise -> Görev Başarılı!
          if (receiver.role === NodeRole.GATEWAY) {
            if (!receiver.queue.isDelivered(packetCopy.id)) {
              receiver.queue.markDelivered(packetCopy.id);
              this.stats.totalDelivered++;
              this.stats.deliveredPackets.push({
                packet: packetCopy,
                deliveredAt: Date.now(),
                totalHops: packetCopy.hopCount,
                route: packetCopy.hops
              });

              if (this.onDeliveryCallback) {
                this.onDeliveryCallback(packetCopy);
              }
            }
          }
        }
      }
    }
  }

  reset() {
    this.nodes = [];
    this.activeTransmissions = [];
    this.stats = {
      totalGenerated: 0,
      totalHops: 0,
      totalDelivered: 0,
      deliveredPackets: []
    };
  }
}
