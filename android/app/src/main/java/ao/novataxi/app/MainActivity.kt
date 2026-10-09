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
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

private const val SUPABASE_URL = "https://vgbnnikfsmprcpvtypuh.supabase.co"
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val NovaOrange = Color(0xFFE97928)
private val NovaInk = Color(0xFF24212B)
private val NovaCream = Color(0xFFFFFBF7)
private val NovaViolet = Color(0xFF873DB5)
private val NovaGreen = Color(0xFF24864A)
private val NovaMuted = Color(0xFF77717D)

private val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_URL,
    supabaseKey = SUPABASE_KEY
) { install(Auth) }

@Serializable
data class ProfileUpdate(val nome: String? = null, val tipo_utilizador: String)

@Serializable
data class DriverProfile(val id: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent {
            val colors = lightColorScheme(
                primary = NovaOrange,
                onPrimary = Color.White,
                secondary = NovaViolet,
                background = NovaCream,
                surface = Color.White,
                onSurface = NovaInk,
                onBackground = NovaInk
            )
            MaterialTheme(colorScheme = colors, typography = Typography()) {
                NovaTaxiApp()
            }
        }
    }
}

@Composable
private fun NovaTaxiApp() {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("passageiro") }
    var sent by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("cool") }
    var payment by remember { mutableStateOf("cash") }
    var bookingStep by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (logged) {
        if (role == "motorista") {
            DriverHome(onSignOut = {
                scope.launch { supabase.auth.signOut() }
                logged = false
                sent = false
                message = ""
            })
        } else {
            PassengerHome(
                destination = destination,
                onDestinationChange = { destination = it },
                category = category,
                onCategoryChange = { category = it },
                payment = payment,
                onPaymentChange = { payment = it },
                step = bookingStep,
                onStepChange = { bookingStep = it },
                message = message,
                onConfirm = {
                    message = "Ainda não enviado: falta ligar este ecrã à função segura de criação de corrida no Supabase."
                },
                onSignOut = {
                    scope.launch { supabase.auth.signOut() }
                    logged = false
                    sent = false
                    bookingStep = 0
                    message = ""
                }
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaCream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BrandHeader()
        Text("A sua cidade. O seu caminho.", color = NovaMuted, fontSize = 16.sp)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Comece pelo seu telefone", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Vamos enviar um código por SMS para confirmar a sua conta.", color = NovaMuted)
                if (!sent) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Telefone com indicativo (+244)") },
                        placeholder = { Text("+244 9XX XXX XXX") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Text("Quero usar o NOVA como", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = role == "passageiro",
                            onClick = { role = "passageiro" },
                            label = { Text("Passageiro") }
                        )
                        FilterChip(
                            selected = role == "motorista",
                            onClick = { role = "motorista" },
                            label = { Text("Motorista") }
                        )
                    }
                    Button(
                        onClick = {
                            if (phone.isBlank()) {
                                message = "Introduza o seu número de telefone."
                            } else {
                                message = "A enviar código..."
                                scope.launch {
                                    try {
                                        supabase.auth.signInWith(OTP) { this.phone = phone.trim() }
                                        sent = true
                                        message = "Código enviado. Verifique as suas mensagens SMS."
                                    } catch (e: Exception) {
                                        message = e.message ?: "Não foi possível enviar o código."
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Continuar por telefone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                } else {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it },
                        label = { Text("Código recebido por SMS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome completo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    supabase.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = phone.trim(), token = otp.trim())
                                    val uid = supabase.auth.currentUserOrNull()?.id ?: error("Sessão não criada")
                                    supabase.from("nova_taxi_profiles").update(
                                        ProfileUpdate(nome = name.ifBlank { null }, tipo_utilizador = role)
                                    ) { filter { eq("id", uid) } }
                                    if (role == "motorista") {
                                        supabase.from("nova_taxi_driver_profiles").upsert(DriverProfile(uid))
                                    }
                                    logged = true
                                    message = ""
                                } catch (e: Exception) {
                                    message = e.message ?: "Não foi possível validar o código."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Verificar e entrar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { sent = false; otp = ""; message = "" }) {
                        Text("Corrigir número de telefone")
                    }
                }
                if (message.isNotBlank()) Text(message, color = if (message.contains("enviado")) NovaGreen else NovaMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconText("shield-check", "Conta protegida por verificação telefónica")
        }
        Text("Ao continuar, confirma que tem autorização para utilizar este número.", color = NovaMuted, fontSize = 12.sp)
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(54.dp).background(NovaOrange, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("N", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        Column {
            Text("NOVA Táxi", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = NovaInk)
            Text("Pedimos. Chegamos.", color = NovaViolet, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PassengerHome(
    destination: String,
    onDestinationChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    payment: String,
    onPaymentChange: (String) -> Unit,
    step: Int,
    onStepChange: (Int) -> Unit,
    message: String,
    onConfirm: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(NovaCream)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BrandHeader()
            TextButton(onClick = onSignOut) { Text("Sair", color = NovaMuted) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
            AndroidView(
                factory = { context ->
                    MapView(context).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(-8.8390, 13.2894))
                                .zoom(12.0)
                                .build()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Card(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Text("Luanda", Modifier.padding(horizontal = 14.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (step == 0) {
                    Text("Para onde vamos?", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = destination,
                        onValueChange = onDestinationChange,
                        label = { Text("Introduza o destino") },
                        placeholder = { Text("Ex.: Talatona, Maianga...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Button(
                        onClick = { onStepChange(1) },
                        enabled = destination.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Escolher viatura", fontSize = 16.sp) }
                } else {
                    Text("Escolha a sua viagem", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    RideOption(
                        title = "Cool",
                        subtitle = "Viagem confortável para o dia a dia",
                        marker = "C",
                        selected = category == "cool",
                        onClick = { onCategoryChange("cool") }
                    )
                    RideOption(
                        title = "Executivo",
                        subtitle = "Uma experiência mais premium",
                        marker = "E",
                        selected = category == "executivo",
                        onClick = { onCategoryChange("executivo") }
                    )
                    Text("Como pretende pagar?", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = payment == "cash", onClick = { onPaymentChange("cash") }, label = { Text("Dinheiro") })
                        FilterChip(selected = payment == "kwik", onClick = { onPaymentChange("kwik") }, label = { Text("KWiK") })
                        FilterChip(selected = payment == "multicaixa", onClick = { onPaymentChange("multicaixa") }, label = { Text("Multicaixa") })
                    }
                    Text("Destino: $destination", color = NovaMuted, fontSize = 13.sp)
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("Confirmar pedido", fontSize = 16.sp) }
                    TextButton(onClick = { onStepChange(0) }) { Text("Alterar destino") }
                    if (message.isNotBlank()) {
                        Text(message, color = NovaViolet, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RideOption(
    title: String,
    subtitle: String,
    marker: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, NovaOrange) else null,
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFFFF3E8) else Color(0xFFF9F7F5))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(48.dp).background(if (selected) NovaOrange else Color(0xFFEDE8E2), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(marker, color = if (selected) Color.White else NovaInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = NovaMuted, fontSize = 12.sp)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun DriverHome(onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(NovaCream).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            BrandHeader()
            TextButton(onClick = onSignOut) { Text("Sair") }
        }
        Text("Área do motorista", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bem-vindo ao NOVA", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("O acesso a pedidos de corrida será mostrado depois da aprovação da conta de motorista.", color = NovaMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(NovaGreen, RoundedCornerShape(50)))
                    Text("A aprovação é necessária para receber corridas", fontSize = 13.sp)
                }
            }
        }
        Text("Os pedidos serão carregados do Supabase quando a integração do modo motorista estiver concluída.", color = NovaMuted, fontSize = 13.sp)
    }
}

@Composable
private fun IconText(icon: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✓", color = NovaGreen, fontWeight = FontWeight.Bold)
        Text(label, color = NovaMuted, fontSize = 13.sp)
    }
}
