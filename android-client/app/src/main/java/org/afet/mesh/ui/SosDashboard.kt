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

            Spacer(modifier = Modifier.height(32.dp))

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

            Spacer(modifier = Modifier.height(32.dp))

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
