package ao.novataxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.OTP
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

private const val SUPABASE_URL = "https://vgbnnikfsmprcpvtypuh.supabase.co"
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_URL,
    supabaseKey = SUPABASE_KEY
) {
    install(Auth)
}

@Serializable
data class ProfileUpdate(val nome: String? = null, val tipo_utilizador: String)

@Serializable
data class DriverProfile(val id: String)

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
    var role by remember { mutableStateOf("passageiro") }
    var sent by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    if (logged) {
        Column(Modifier.fillMaxSize()) {
            Text("NOVA Táxi", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
            Text("Ligado ao Tudoaqui+ • sessão autenticada", modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
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
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NOVA Táxi", style = MaterialTheme.typography.headlineLarge)
        Text("Pedimos. Chegamos.")
        if (!sent) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Telefone (+244...)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            Text("Entrar como")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "passageiro", onClick = { role = "passageiro" }, label = { Text("Passageiro") })
                FilterChip(selected = role == "motorista", onClick = { role = "motorista" }, label = { Text("Motorista") })
            }
            Button(
                onClick = {
                    message = "A enviar código..."
                    scope.launch {
                        try {
                            supabase.auth.signInWith(OTP) { this.phone = phone }
                            sent = true
                            message = "Código enviado. Verifique o SMS."
                        } catch (e: Exception) { message = e.message ?: "Falha ao enviar OTP" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continuar por telefone") }
        } else {
            OutlinedTextField(value = otp, onValueChange = { otp = it }, label = { Text("Código OTP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    kotlinx.coroutines.MainScope().launch {
                        try {
                            supabase.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phoneNumber = phone, token = otp)
                            val uid = supabase.auth.currentUserOrNull()?.id ?: error("Sessão não criada")
                            supabase.from("nova_taxi_profiles").update(
                                ProfileUpdate(nome = name.ifBlank { null }, tipo_utilizador = role)
                            ) { filter { eq("id", uid) } }
                            if (role == "motorista") {
                                supabase.from("nova_taxi_driver_profiles").upsert(DriverProfile(uid))
                            }
                            logged = true
                            message = "Sessão iniciada."
                        } catch (e: Exception) { message = e.message ?: "Código inválido" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Verificar e entrar") }
        }
        if (message.isNotBlank()) Text(message)
    }
}
