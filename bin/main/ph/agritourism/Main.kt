package ph.agritourism

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import ph.agritourism.data.Database
import ph.agritourism.ui.*

fun main() {
    val db = Database()
    application {
        val windowState = rememberWindowState(placement = WindowPlacement.Floating, size = androidx.compose.ui.unit.DpSize(1440.dp, 900.dp))
        Window(
            onCloseRequest = { db.close(); exitApplication() },
            state = windowState,
            title = "AgriTourism Philippines · Farm. Connect. Experience.",
            icon = painterResource("icons/pwa-512x512.png"),
        ) {
            val scope = rememberCoroutineScope()
            val state = remember { AppState(db, scope) }
            MenuBar {
                Menu("File", mnemonic = 'F') {
                    Item("Print Window…", shortcut = KeyShortcut(Key.P, ctrl = true)) { Printing.printWindow(window) }
                    Item("Save Window Snapshot (PNG)…", shortcut = KeyShortcut(Key.S, ctrl = true)) {
                        Printing.saveSnapshot(window)?.let { state.showToast("Saved snapshot to: ${it.absolutePath}") }
                    }
                    Item("Reload Data from SQLite", shortcut = KeyShortcut(Key.R, ctrl = true)) { state.reloadFromDb() }
                    Separator()
                    Item("Exit") { db.close(); exitApplication() }
                }
                Menu("View", mnemonic = 'V') {
                    Item(if (state.isSidebarCollapsed) "Expand Left Dock" else "Collapse Left Dock") { state.isSidebarCollapsed = !state.isSidebarCollapsed }
                    if (state.isLoggedIn) Item("Go to Workspace Dashboard") { state.navigate(Page.DASHBOARD) }
                    Item("Reset Search & Filters") { state.searchQuery = ""; state.marketFilter = "All"; state.showToast("Filters cleared.") }
                    Item("Design Mockups Gallery") { state.isMockupDrawerOpen = true }
                }
            }
            MaterialTheme(colorScheme = AppColors) { AppShell(state) }
        }
    }
}
