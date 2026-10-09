package ao.novataxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

private const val SUPABASE_URL = "https://vgbnnikfsmprcpvtypuh.supabase.co"
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val NovaOrange = Color(0xFFFF6B35)
private val NovaLime = Color(0xFFC8F169)
private val NovaInk = Color(0xFF20211F)
private val NovaCream = Color(0xFFFFFCF5)

private val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_URL,
    supabaseKey = SUPABASE_KEY
) {
    install(Auth)
    install(Postgrest)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { NovaTaxiApp() }
    }
}

@Composable
private fun NovaTaxiApp() {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("passenger") }
    var sent by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = NovaOrange,
            onPrimary = Color.White,
            secondary = NovaLime,
            background = NovaCream,
            surface = Color.White,
            onSurface = NovaInk
        )
    ) {
        if (logged) {
            NovaHome(
                role = role,
                onSignOut = {
                    scope.launch {
                        try {
                            supabase.auth.signOut()
                            logged = false
                            sent = false
                            otp = ""
                            message = "Sessão terminada."
                        } catch (e: Exception) {
                            message = e.message ?: "Não foi possível terminar a sessão."
                        }
                    }
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(NovaCream, Color.White)))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(NovaOrange, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("N", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                    Column {
                        Text("NOVA Táxi", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = NovaInk)
                        Text("Mobilidade feita para Angola", color = Color(0xFF676760))
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vamos contigo.", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = NovaInk)
                    Text(
                        "Entra com o teu número de telefone. Sem cartão ou método de pagamento obrigatório no cadastro.",
                        color = Color(0xFF676760),
                        lineHeight = 22.sp
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Quero usar a NOVA como", fontWeight = FontWeight.Bold, color = NovaInk)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RoleChoice(
                                title = "Passageiro",
                                selected = role == "passenger",
                                modifier = Modifier.weight(1f),
                                onClick = { role = "passenger" }
                            )
                            RoleChoice(
                                title = "Motorista",
                                selected = role == "driver",
                                modifier = Modifier.weight(1f),
                                onClick = { role = "driver" }
                            )
                        }

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                            label = { Text("Telefone com indicativo (+244...)") },
                            placeholder = { Text("+244923000000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )

                        if (sent) {
                            OutlinedTextField(
                                value = otp,
                                onValueChange = { otp = it.filter(Char::isDigit).take(8) },
                                label = { Text("Código recebido por SMS") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(120) },
                                label = { Text("Nome completo") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }

                        Button(
                            enabled = !loading && phone.startsWith("+") && phone.count { it.isDigit() } >= 9 &&
                                (!sent || (otp.length >= 4 && name.trim().length >= 2)),
                            onClick = {
                                loading = true
                                message = if (sent) "A validar código e a guardar o perfil..." else "A pedir o código SMS..."
                                scope.launch {
                                    try {
                                        if (!sent) {
                                            supabase.auth.signInWith(OTP) { this.phone = phone.trim() }
                                            sent = true
                                            message = "Se o serviço SMS estiver ativo, receberás o código neste número."
                                        } else {
                                            supabase.auth.verifyPhoneOtp(
                                                type = OtpType.Phone.SMS,
                                                phone = phone.trim(),
                                                token = otp.trim()
                                            )
                                            supabase.postgrest.rpc(
                                                function = "nova_taxi_v1_complete_onboarding",
                                                parameters = buildJsonObject {
                                                    put("p_full_name", name.trim())
                                                    put("p_user_type", role)
                                                }
                                            )
                                            logged = true
                                            message = if (role == "driver") {
                                                "Cadastro enviado. A conta de motorista aguarda aprovação."
                                            } else {
                                                "Cadastro concluído."
                                            }
                                        }
                                    } catch (e: Exception) {
                                        message = e.message ?: "Não foi possível concluir. Confirma a ligação e tenta novamente."
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(if (sent) "Verificar e criar conta" else "Receber código SMS", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (sent) {
                            TextButton(
                                onClick = { sent = false; otp = ""; message = "" },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) { Text("Alterar número") }
                        }

                        if (message.isNotBlank()) {
                            Text(message, color = if (message.contains("não foi", true) || message.contains("falha", true)) Color(0xFFB3261E) else Color(0xFF55564F))
                        }
                    }
                }

                Text(
                    "A tua segurança começa com uma conta verificada. Motoristas só ficam elegíveis depois da aprovação.",
                    color = Color(0xFF77776F),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun RoleChoice(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) NovaLime else Color(0xFFF6F4EE)
    Surface(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E0D8))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = NovaInk)
        }
    }
}

@Composable
private fun NovaHome(role: String, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().background(NovaCream)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("NOVA Táxi", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = NovaInk)
                Text(if (role == "driver") "Área do motorista" else "A tua próxima viagem começa aqui", color = Color(0xFF676760))
            }
            TextButton(onClick = onSignOut) { Text("Sair") }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = NovaOrange),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (role == "driver") "Bem-vindo à NOVA Driver" else "Chega lá à tua maneira.",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    if (role == "driver")
                        "O perfil de motorista precisa de aprovação e veículo verificado antes de receber corridas."
                    else
                        "Escolhe Cool ou Executivo quando o pedido de corrida estiver disponível na tua conta.",
                    color = Color.White,
                    lineHeight = 21.sp
                )
            }
        }

        if (role == "passenger") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ServiceCard("NOVA Cool", "Viagens urbanas", NovaLime, Modifier.weight(1f))
                ServiceCard("Executivo", "Mais conforto", Color(0xFFFFD6A5), Modifier.weight(1f))
            }
        } else {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Estado do motorista", fontWeight = FontWeight.Bold, color = NovaInk)
                    Text("Pendente de aprovação", color = Color(0xFF8A4B08))
                    Text("Não podes ficar online até a equipa verificar o perfil e o veículo.", color = Color(0xFF676760))
                }
            }
        }

        Text("Luanda", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp), fontWeight = FontWeight.Bold, color = NovaInk)
        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(-8.8390, 13.2894))
                            .zoom(11.0)
                            .build()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun ServiceCard(title: String, subtitle: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, color = NovaInk)
            Text(subtitle, color = NovaInk.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}
