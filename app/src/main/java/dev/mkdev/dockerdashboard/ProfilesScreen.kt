package dev.mkdev.dockerdashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * La liste des dashboards mémorisés (un par NAS). Toucher = ouvrir ;
 * ✎ modifie, 🗑 supprime (après confirmation). Chaque dashboard garde sa
 * propre session : la WebView sépare cookies et stockage par origine.
 */
@Composable
fun ProfilesScreen(
    profiles: List<Prefs.Profile>,
    activeId: String?,
    onOpen: (Prefs.Profile) -> Unit,
    onEdit: (Prefs.Profile) -> Unit,
    onDelete: (Prefs.Profile) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Prefs.Profile?>(null) }
    BackHandler { onBack() }

    Column(
        Modifier.fillMaxSize().background(DashBg).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Text("🐋", fontSize = 48.sp)
            Spacer(Modifier.height(6.dp))
            Text("Mes dashboards", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Un par NAS ; chacun garde sa session.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE))
            Spacer(Modifier.height(20.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.id }) { p ->
                    val active = p.id == activeId
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(p) },
                        color = if (active) Color(0xFF1B2A6B) else Color(0xFF13151B),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (active) "● " else "") + p.label, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text(p.url, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0AE))
                            }
                            TextButton(onClick = { onEdit(p) }) { Text("✎") }
                            TextButton(onClick = { confirmDelete = p }) { Text("🗑") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = activeId != null) { Text("Retour") }
                Button(onClick = onAdd) { Text("＋ Ajouter un dashboard") }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer « ${p.label} » ?") },
            text = { Text("${p.url}\n\nLa session enregistrée pour ce dashboard reste dans le navigateur interne ; l'adresse est oubliée.") },
            confirmButton = { TextButton(onClick = { onDelete(p); confirmDelete = null }) { Text("Supprimer", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}
