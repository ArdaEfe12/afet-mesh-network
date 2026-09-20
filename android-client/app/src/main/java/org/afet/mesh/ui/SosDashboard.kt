package org.afet.mesh.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.afet.mesh.mesh.dutycycle.DutyCycleProfile

// ═══════════════════════════════════════════════════════════════════════
// AMOLED PURE BLACK SOS KONTROL PANELİ
//
// Tasarım İlkeleri:
//  • %100 saf siyah arka plan — OLED piksellerini kapatır, pil tüketimini minimize eder
//  • Tek dokunuşla "ENKAZ ALTINDAYIM" SOS feneri
//  • Büyük, yüksek kontrast öğeler — afet stresinde hızlı kullanım
//  • Canlı durum göstergesi: Pil, bağlı cihaz sayısı, kuyruk boyutu
//  • Nabız atmalar animasyonu: Ağın "canlı" olduğunu gösterir
// ═══════════════════════════════════════════════════════════════════════

// ── Renk Paleti (AMOLED Pure Black) ──────────────────────────────────
object AfetColors {
    val Background    = Color(0xFF000000)  // Tam siyah — OLED pikseli OFF
    val Surface       = Color(0xFF0D0D0D)  // Çok koyu gri — kart yüzeyi
    val SurfaceAlt    = Color(0xFF141414)  // Alternatif kart yüzeyi
    val SosRed        = Color(0xFFFF3B30)  // Canlı kırmızı — SOS butonu
    val SosRedGlow    = Color(0x55FF3B30)  // Yarı saydam SOS aura
    val ActiveGreen   = Color(0xFF34C759)  // Aktif / Çevrimiçi yeşili
    val WarningAmber  = Color(0xFFFF9F0A)  // Uyarı / Düşük pil
    val CriticalOrange = Color(0xFFFF6B00) // Kritik pil
    val AccentBlue    = Color(0xFF0A84FF)  // Vurgu mavisi
    val TextPrimary   = Color(0xFFFFFFFF)  // Birincil metin
    val TextSecondary = Color(0xFF8E8E93)  // İkincil metin
    val TextMuted     = Color(0xFF48484A)  // Soluk metin
}

@Composable
fun SosDashboard(
    // ViewModel'den gelen durum
    isSosActive: Boolean,
    batteryPct: Int,
    connectedPeers: Int,
    pendingQueueSize: Int,
    currentProfile: DutyCycleProfile,
    lastKnownLocation: String,   // "41.00823, 28.97845" formatında
    deliveredCount: Int,
    nearbyEmergencies: List<org.afet.mesh.data.local.MessageEntity> = emptyList(),
    localDeviceModel: String = "",
    onSosToggle: () -> Unit,
    onAddMessage: (String) -> Unit
) {
    // SOS buton nabız animasyonu
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isSosActive) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AfetColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Başlık ─────────────────────────────────────────────
            Text(
                text = "AFET MESH AĞI",
                color = AfetColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 3.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Çevrimdışı İletişim",
                color = AfetColors.TextSecondary,
                fontSize = 14.sp
            )
            if (localDeviceModel.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Bu Cihaz: $localDeviceModel",
                    color = AfetColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Durum Kartları (Pil, Bağlı Cihaz, Kuyruk) ─────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusChip(
                    modifier = Modifier.weight(1f),
                    label = "PİL",
                    value = "%${batteryPct}",
                    color = when {
                        batteryPct < 15 -> AfetColors.SosRed
                        batteryPct < 40 -> AfetColors.CriticalOrange
                        batteryPct < 70 -> AfetColors.WarningAmber
                        else            -> AfetColors.ActiveGreen
                    }
                )
                StatusChip(
                    modifier = Modifier.weight(1f),
                    label = "BAĞLI",
                    value = "$connectedPeers",
                    color = if (connectedPeers > 0) AfetColors.AccentBlue else AfetColors.TextMuted
                )
                StatusChip(
                    modifier = Modifier.weight(1f),
                    label = "KUYRUK",
                    value = "$pendingQueueSize",
                    color = if (pendingQueueSize > 0) AfetColors.WarningAmber else AfetColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Duty Cycle profil göstergesi
            Text(
                text = "● ${currentProfile.name.replace('_', ' ')}",
                color = AfetColors.ActiveGreen,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.W600,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ── ANA SOS BUTONU ──────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.scale(pulseScale)
            ) {
                // Dış aura halkası
                if (isSosActive) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(CircleShape)
                            .background(AfetColors.SosRedGlow)
                    )
                }

                // SOS Butonu
                Button(
                    onClick = onSosToggle,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSosActive) AfetColors.SosRed else AfetColors.Surface
                    ),
                    shape = CircleShape,
                    border = if (!isSosActive) BorderStroke(2.dp, AfetColors.SosRed) else null,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🆘",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isSosActive) "SOS AKTİF" else "SOS BAŞLAT",
                            color = AfetColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.W800,
                            letterSpacing = 1.5.sp
                        )
                        if (isSosActive) {
                            Text(
                                text = "Kapatmak için dokun",
                                color = Color(0xAAFFFFFF),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Hızlı Durum Butonları ───────────────────────────────
            Text(
                text = "HIZLI DURUM BİLDİRİMİ",
                color = AfetColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 1.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            val quickChips = listOf(
                "Enkaz Altındayım" to "🚨",
                "Yaralı Var" to "🩹",
                "2 Kişiyiz" to "👥",
                "Mahsur Kaldık" to "⚠️",
                "Güvendeyim" to "✅"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickChips.forEach { (text, emoji) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(AfetColors.Surface)
                            .border(1.dp, AfetColors.SurfaceAlt, RoundedCornerShape(20.dp))
                            .clickable { onAddMessage("$emoji $text") }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "$emoji $text",
                            color = AfetColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W500
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Özel Mesaj Giriş Alanı ──────────────────────────────
            var customText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    placeholder = {
                        Text(
                            text = "Kat, oda, durum notu yazın...",
                            color = AfetColors.TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AfetColors.TextPrimary,
                        unfocusedTextColor = AfetColors.TextPrimary,
                        focusedContainerColor = AfetColors.Surface,
                        unfocusedContainerColor = AfetColors.Surface,
                        focusedBorderColor = AfetColors.SosRed,
                        unfocusedBorderColor = AfetColors.SurfaceAlt,
                        cursorColor = AfetColors.SosRed
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Button(
                    onClick = {
                        if (customText.isNotBlank()) {
                            onAddMessage(customText.trim())
                            customText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AfetColors.SosRed,
                        contentColor = AfetColors.TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    enabled = customText.isNotBlank()
                ) {
                    Text(
                        text = "Gönder",
                        fontWeight = FontWeight.W700,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Konum Bilgisi ───────────────────────────────────────
            if (lastKnownLocation.isNotBlank()) {
                InfoCard(
                    icon = "📍",
                    label = "Son Bilinen Konum",
                    value = lastKnownLocation,
                    color = AfetColors.AccentBlue
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // ── Teslim Edilen Paket Sayısı ──────────────────────────
            if (deliveredCount > 0) {
                InfoCard(
                    icon = "✅",
                    label = "Kurtarma Merkezine Ulaşan",
                    value = "$deliveredCount mesaj",
                    color = AfetColors.ActiveGreen
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── ÇEVREDEKİ ACİL DURUMLAR & ALINAN CİHAZ MESAJLARI ───────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ÇEVREDEKİ ACİL DURUMLAR",
                    color = if (nearbyEmergencies.isNotEmpty()) AfetColors.SosRed else AfetColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 1.sp
                )
                if (nearbyEmergencies.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AfetColors.SosRedGlow)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${nearbyEmergencies.size} CİHAZ",
                            color = AfetColors.SosRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.W800
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (nearbyEmergencies.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AfetColors.Surface, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Etrafta henüz başka bir cihazdan acil durum sinyali alınmadı.\nİkinci bir telefon yaklaştığında modeli ve konumu burada belirecektir.",
                        color = AfetColors.TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    nearbyEmergencies.forEach { emergency ->
                        EmergencyCard(emergency = emergency)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Bilgilendirme Notu ──────────────────────────────────
            Text(
                text = "Mesajınız yakınınızdan geçen cihazlar\naracılığıyla kurtarma merkezine ulaştırılır.",
                color = AfetColors.TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

// ── Küçük Durum Etiketi ───────────────────────────────────────────────
@Composable
private fun StatusChip(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Column(
        modifier = modifier
            .background(AfetColors.Surface, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.W700,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = AfetColors.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 1.sp
        )
    }
}

// ── Bilgi Kartı ───────────────────────────────────────────────────────
@Composable
private fun InfoCard(
    icon: String,
    label: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AfetColors.Surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                color = AfetColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
                letterSpacing = 0.5.sp
            )
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ── Çevredeki Acil Durum Kartı (Cihaz Modeli + Konum + Mesaj) ────────
@Composable
private fun EmergencyCard(emergency: org.afet.mesh.data.local.MessageEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AfetColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, AfetColors.SosRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        // Üst Satır: Model + Hop / Sıçrama rozeti
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "📱", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = emergency.senderDeviceModel.ifBlank { "Bilinmeyen Cihaz (#${emergency.senderId.take(6)})" },
                    color = AfetColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AfetColors.SurfaceAlt)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (emergency.hopCount <= 1) "📡 Doğrudan Alındı" else "⚡ ${emergency.hopCount}. Sıçrama",
                    color = AfetColors.AccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W600
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mesaj Metni
        Text(
            text = emergency.messageText.ifBlank { "🚨 Acil Durum / SOS Sinyali" },
            color = AfetColors.SosRed,
            fontSize = 15.sp,
            fontWeight = FontWeight.W700
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Konum ve Saat Bilgisi
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val locText = if (emergency.latitude != 0.0 || emergency.longitude != 0.0) {
                "📍 %.5f, %.5f".format(emergency.latitude, emergency.longitude)
            } else {
                "📍 Konum bekleniyor"
            }
            Text(
                text = locText,
                color = AfetColors.ActiveGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.W600
            )

            val timeAgo = calculateTimeAgo(emergency.receivedAtMs)
            Text(
                text = "⏱️ $timeAgo",
                color = AfetColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

private fun calculateTimeAgo(timestampMs: Long): String {
    val diffSec = (System.currentTimeMillis() - timestampMs) / 1000
    return when {
        diffSec < 60 -> "Az önce"
        diffSec < 3600 -> "${diffSec / 60} dk önce"
        else -> "${diffSec / 3600} saat önce"
    }
}

