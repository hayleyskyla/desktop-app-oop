package ph.agritourism.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.agritourism.model.Role
import ph.agritourism.ui.*

@Composable
private fun AuthLayout(s: AppState, form: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(720.dp).clip(RoundedCornerShape(28.dp)).background(Color.White)) {
        Box(Modifier.weight(1.1f).fillMaxHeight()) {
            Crossfade(s.currentSlide, animationSpec = tween(900)) { i ->
                // The photo is the same farmer whose story is shown below.
                val f = s.farmers[i]
                AsyncImage(f.photoUrl.ifBlank { f.farmBannerUrl }, Modifier.fillMaxSize(), contentDescription = f.name, fallback = s.images.farmerProfileBanner)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent, Color(0xCC000000)))))
            // Farmer story for the current slide
            Crossfade(s.currentSlide, Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 48.dp), animationSpec = tween(600)) { i ->
                val f = s.farmers[i]
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FARMER STORY", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFBBF7D0), letterSpacing = 1.5.sp)
                    Text("“${f.storyQuote}”", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(f.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Text("${f.farmName} · ${f.province}", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(20.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Eco, null, Modifier.size(16.dp), tint = Palette.Forest); Spacer(Modifier.width(6.dp))
                Text("AgriTourism Philippines", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Deep)
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.farmers.indices.forEach { i ->
                    val w by androidx.compose.animation.core.animateDpAsState(if (i == s.currentSlide) 24.dp else 8.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "dot-w")
                    val c by animateColorAsState(if (i == s.currentSlide) Color.White else Color.White.copy(alpha = 0.5f), tween(300), label = "dot-c")
                    Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c).handCursor().clickable { s.currentSlide = i })
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.Center, content = form)
            Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)) {
                TextAction("Back", Modifier.align(Alignment.CenterEnd).testTag("back-to-opening")) { s.navigate(Page.HOME) }
            }
        }
    }
}

@Composable
private fun Brand() = Row(verticalAlignment = Alignment.CenterVertically) {
    AsyncImage("res:icons/pwa-192x192.png", Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
    Spacer(Modifier.width(10.dp)); Text("AgriTourism Philippines", fontWeight = FontWeight.ExtraBold, color = Palette.Deep)
}

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Composable
fun SignupPage(s: AppState) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var farmName by remember { mutableStateOf("") }
    var province by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(if (s.userRole == Role.ADMIN) Role.USER else s.userRole) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }
    // Field-level errors appear once the user has tried to submit, then update live as they type.
    val nameErr = if (attempted && name.isBlank()) "Enter your full name" else null
    val emailErr = when { !attempted -> null; email.isBlank() -> "Enter your email"; !EMAIL_RE.matches(email.trim()) -> "That email doesn't look right"; else -> null }
    val phoneErr = if (attempted && phone.isNotBlank() && phone.count(Char::isDigit) < 7) "Enter a valid phone number" else null
    val pwErr = when { !attempted -> null; pw.isBlank() -> "Create a password"; pw.length < 6 -> "At least 6 characters"; else -> null }
    val pw2Err = if (attempted && pw2 != pw) "Passwords don't match" else null
    val farmErr = if (attempted && role == Role.FARMER && farmName.isBlank()) "Enter your farm name" else null
    val submit = {
        attempted = true
        val valid = name.isNotBlank() && EMAIL_RE.matches(email.trim()) && (phone.isBlank() || phone.count(Char::isDigit) >= 7) &&
            pw.length >= 6 && pw == pw2 && (role != Role.FARMER || farmName.isNotBlank())
        error = if (valid) s.signup(name, email, phone, pw, role, farmName, province) else null
    }
    AuthLayout(s) {
        Brand(); Spacer(Modifier.height(18.dp))
        H1("Create Your Account"); Sub("Join our community and support local farmers!")
        Spacer(Modifier.height(18.dp))
        Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Full Name", name, { name = it }, placeholder = "Juan Dela Cruz", icon = Icons.Outlined.Person, error = nameErr, onSubmit = submit)
            Field("Email Address", email, { email = it.trim() }, placeholder = "you@email.com", icon = Icons.Outlined.Mail, error = emailErr, onSubmit = submit)
            Field("Phone Number", phone, { v -> phone = v.filter { it.isDigit() || it in "+ -()" } }, placeholder = "Optional",
                icon = Icons.Outlined.Phone, error = phoneErr, onSubmit = submit)
            Field("Password", pw, { pw = it }, placeholder = "At least 6 characters", password = true, icon = Icons.Outlined.Lock, error = pwErr, onSubmit = submit)
            Field("Confirm Password", pw2, { pw2 = it }, placeholder = "Re-enter password", password = true, icon = Icons.Outlined.Lock, error = pw2Err, onSubmit = submit)
            Dropdown("I am a…", listOf(Role.USER, Role.FARMER), role, {
                when (it) { Role.USER -> "Tourist (Book Tours & Buy Fruits)"; Role.FARMER -> "Farmer (Manage Farm & List Produce)"; Role.ADMIN -> "Administrator (Platform Overview)" }
            }) { role = it }
            AnimatedVisibility(role == Role.FARMER, enter = fadeIn(tween(200)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)), exit = fadeOut(tween(120)) + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Farm Name", farmName, { farmName = it }, Modifier.fillMaxWidth(), "e.g. Sunrise Orchard", icon = Icons.Outlined.Agriculture, error = farmErr, onSubmit = submit)
                    Field("Province", province, { province = it }, Modifier.fillMaxWidth(), "e.g. Batangas", icon = Icons.Outlined.Place, onSubmit = submit)
                }
            }
        }
        ErrorBanner(error)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Create Account", Modifier.fillMaxWidth(), Icons.Outlined.PersonAdd) { submit() }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Sub("Already have an account?"); TextButton({ s.navigate(Page.LOGIN) }, Modifier.handCursor()) { Text("Log In", fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
fun LoginPage(s: AppState) {
    val canSubmit = s.loginEmail.isNotBlank() && s.loginPassword.isNotBlank()
    val submit = { if (canSubmit) s.login() }
    AuthLayout(s) {
        Brand(); Spacer(Modifier.height(18.dp))
        H1("Welcome Back"); Sub("Log in to manage bookings, harvest produce, or book farm tours")
        Spacer(Modifier.height(18.dp))
        Field("Email Address", s.loginEmail, { s.loginEmail = it.trim() }, placeholder = "you@email.com", icon = Icons.Outlined.Mail, onSubmit = submit)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Password"); Spacer(Modifier.weight(1f))
            Text("Forgot password?", fontSize = 11.sp, color = Palette.Forest, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).handCursor().clickable {
                    s.showToast(if (s.db.findUser(s.loginEmail) != null) "Please contact an administrator to reset the password for ${s.loginEmail}."
                        else if (s.loginEmail.isBlank()) "Enter your email first, then tap Forgot password."
                        else "No account is registered with ${s.loginEmail}.")
                }.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(5.dp))
        Field("", s.loginPassword, { s.loginPassword = it }, placeholder = "Enter your password", password = true, icon = Icons.Outlined.Lock, onSubmit = submit)
        Spacer(Modifier.height(16.dp))
        ErrorBanner(s.loginError)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Log In", Modifier.fillMaxWidth(), Icons.AutoMirrored.Outlined.Login, enabled = canSubmit) { submit() }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Sub("Don't have an account yet?"); TextButton({ s.navigate(Page.SIGNUP) }, Modifier.handCursor()) { Text("Create Account", fontWeight = FontWeight.ExtraBold) }
        }
    }
}
