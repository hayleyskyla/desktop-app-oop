package ph.agritourism.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.data.Database
import ph.agritourism.ui.*

/** Admin-only in-app SQLite studio: table browser, query runner and schema copy. */
@Composable
fun SqlStudioPage(s: AppState) {
    var sql by remember { mutableStateOf("SELECT booking_code, customer_name, farm_name, status, total_amount FROM bookings ORDER BY sort_order;") }
    var result by remember { mutableStateOf<Database.QueryResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    fun run(q: String) {
        sql = q
        runCatching { s.runAdminSql(q) }.onSuccess { result = it; error = null }.onFailure { error = it.message; result = null }
    }
    LaunchedEffect(Unit) { run(sql) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { H1("SQL Database Studio"); Sub("SQLite3 database: ${Database.defaultFile().absolutePath}") }
        SecondaryButton("Copy Schema", icon = Icons.Outlined.ContentCopy) { clipboard.setText(AnnotatedString(s.db.schemaSql())); s.showToast("Schema copied to clipboard!") }
        Spacer(Modifier.width(8.dp))
        SecondaryButton("Reload App Data", icon = Icons.Outlined.Refresh) { s.reloadFromDb() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(Modifier.width(220.dp)) {
            Label("Tables"); Spacer(Modifier.height(6.dp))
            s.db.tableNames().forEach { t ->
                Text(t, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { run("SELECT * FROM $t LIMIT 100;") }.padding(8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard {
                Label("Query")
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(sql, { sql = it }, Modifier.fillMaxWidth(), minLines = 3, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Run Query", icon = Icons.Outlined.PlayArrow) { run(sql) }
                    Spacer(Modifier.width(12.dp))
                    val err = error
                    if (err != null) Text(err, color = Palette.Red, fontSize = 12.sp) else result?.let { Sub(it.message) }
                }
            }
            result?.takeIf { it.columns.isNotEmpty() }?.let { r ->
                GlassCard(padding = 0.dp) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Column {
                                Row(Modifier.background(Palette.Deep)) { r.columns.forEach { Cell(it, true) } }
                                r.rows.forEachIndexed { i, row ->
                                    Row(Modifier.background(if (i % 2 == 0) Color.White else Color(0xFFF8FAFC))) { row.forEach { Cell(it, false) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, header: Boolean) = Text(text, Modifier.width(170.dp).padding(8.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, color = if (header) Color.White else Color(0xFF1E293B), fontFamily = if (header) null else FontFamily.Monospace)
