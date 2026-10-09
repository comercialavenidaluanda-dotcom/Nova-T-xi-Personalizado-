package ao.novataxi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.time.Instant

private const val SUPABASE_URL = "https://vgbnnikfsmprcpvtypuh.supabase.co"
private const val SUPABASE_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) {
    install(Auth)
    install(Postgrest)
}

@Serializable
data class TaxiProfile(
    @SerialName("id") val id: String,
    @SerialName("tipo_utilizador") val tipoUtilizador: String,
    @SerialName("nome") val nome: String? = null,
    @SerialName("telefone") val telefone: String? = null,
    @SerialName("ativo") val ativo: Boolean = true
)

@Serializable
data class TaxiDriverProfile(
    @SerialName("id") val id: String,
    @SerialName("aprovado") val aprovado: Boolean = false,
    @SerialName("disponivel") val disponivel: Boolean = false
)

@Serializable
data class DriverLocationPayload(
    @SerialName("motorista_id") val motoristaId: String,
    val latitude: Double,
    val longitude: Double,
    val heading: Double?,
    @SerialName("accuracy_m") val accuracyM: Double?,
    @SerialName("captured_at") val capturedAt: String
)

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var activeDriverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContent { NovaTaxiApp(this) }
    }

    fun startDriverGps(driverId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        activeDriverId = driverId
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(3_000L).setWaitForAccurateLocation(false).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val uid = activeDriverId ?: return
                result.locations.forEach { location ->
                    if (location.isMock) return@forEach
                    val payload = DriverLocationPayload(
                        driverId = uid, lat = location.latitude, lng = location.longitude,
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                        capturedAt = Instant.ofEpochMilli(location.time).toString(),
                        sequenceNo = System.currentTimeMillis()
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            supabase.from("nova_taxi_driver_locations").insert(payload)
                        } catch (e: Exception) {
                            android.util.Log.w("NOVA_TAXI_GPS", "Falha ao enviar localização do motorista", e)
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    "Não foi possível sincronizar o GPS. Verifique a ligação e as permissões da conta.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }
        try { fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper()) }
        catch (_: SecurityException) { locationCallback = null }
    }

    override fun onPause() {
        super.onPause()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }
}

@Composable
private fun NovaTaxiApp(activity: MainActivity) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("PASSENGER") }
    var isLogin by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var loggedRole by remember { mutableStateOf("PASSENGER") }
    var loggedUid by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showPromo by remember { mutableStateOf(!activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).getBoolean("promo_seen_v1", false)) }
    val scope = rememberCoroutineScope()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && loggedRole == "DRIVER") {
            scope.launch {
                try {
                    val driver = supabase.from("nova_taxi_driver_profiles").select {
                        filter { eq("id", loggedUid) }
                    }.decodeList<TaxiDriverProfile>().firstOrNull()
                    if (driver?.aprovado == true) {
                        activity.startDriverGps(loggedUid)
                        message = "GPS iniciado. A localização será sincronizada enquanto a aplicação estiver aberta."
                    } else {
                        message = "A conta de motorista ainda aguarda aprovação administrativa. O GPS operacional não foi iniciado."
                    }
                } catch (e: Exception) {
                    message = e.message ?: "Não foi possível validar a aprovação do motorista."
                }
            }
        } else if (!granted) message = "Permita a localização para enviar o GPS do motorista."
    }

    if (showPromo && !logged) {
        AlertDialog(
            onDismissRequest = {
                showPromo = false
                activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("promo_seen_v1", true).apply()
            },
            title = { Text("NOVA Táxi — Vamos juntos!") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Motoristas: oportunidade de ganhar até 140.000 Kz por semana*.")
                    Text("Passageiros: 5% de desconto nas 3 primeiras corridas*.")
                    Text("Junte-se à NOVA Táxi e faça parte da mobilidade em Angola.")
                    Text("*Ganhos não garantidos. Valor indicativo sujeito à procura, horas trabalhadas e condições da campanha.")
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        role = "PASSENGER"
                        isLogin = false
                        showPromo = false
                        activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("promo_seen_v1", true).apply()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Quero viajar — 5% de desconto") }
                    Button(onClick = {
                        role = "DRIVER"
                        isLogin = false
                        showPromo = false
                        activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("promo_seen_v1", true).apply()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Quero ser motorista") }
                    TextButton(onClick = {
                        showPromo = false
                        activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("promo_seen_v1", true).apply()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Agora não") }
                }
            }
        )
    }

    if (logged) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NOVA Táxi", style = MaterialTheme.typography.headlineMedium)
            Text(if (loggedRole == "DRIVER") "Conta de motorista" else "Conta de passageiro")
            Text("Conta autenticada por e-mail e perfil guardado no Supabase.")
            if (loggedRole == "DRIVER") {
                Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth()) { Text("Ativar GPS em tempo real") }
                Text("O GPS é enviado enquanto a aplicação está aberta. O servidor bloqueia posições operacionais até à aprovação do motorista.", style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message)
            AndroidView(factory = { ctx ->
                MapView(ctx).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                        map.cameraPosition = CameraPosition.Builder().target(LatLng(-8.8390, 13.2894)).zoom(11.0).build()
                    }
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("NOVA Táxi", style = MaterialTheme.typography.headlineLarge)
        Text("Pedimos. Chegamos.")
        Text(if (isLogin) "Entrar com e-mail" else "Criar conta NOVA Táxi")
        if (!isLogin) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth())
            Text("Registar como")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "PASSENGER", onClick = { role = "PASSENGER" }, label = { Text("Passageiro") })
                FilterChip(selected = role == "DRIVER", onClick = { role = "DRIVER" }, label = { Text("Motorista") })
            }
        }
        OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text("E-mail") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Palavra-passe (mínimo 8 caracteres)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.contains("@") && password.length >= 8 && (isLogin || name.isNotBlank()), onClick = {
            busy = true
            message = if (isLogin) "A autenticar…" else "A criar a conta no Supabase…"
            scope.launch {
                try {
                    if (!isLogin) {
                        supabase.auth.signUpWith(Email) {
                            this.email = email.trim().lowercase()
                            this.password = password
                            data = buildJsonObject {
                                put("tipo_utilizador", if (role == "DRIVER") "motorista" else "passageiro")
                                put("nome", name.trim())
                            }
                        }
                    } else {
                        supabase.auth.signInWith(Email) {
                            this.email = email.trim()
                            this.password = password
                        }
                    }

                    val uid = supabase.auth.currentUserOrNull()?.id
                    if (uid == null) {
                        if (!isLogin) {
                            isLogin = true
                            message = "Conta solicitada. Confirme o e-mail enviado pelo Supabase e depois entre com o mesmo e-mail e palavra-passe para concluir o perfil."
                        } else {
                            error("A autenticação não devolveu uma sessão. Confirme o e-mail e verifique as definições de Auth no Supabase.")
                        }
                    } else {
                        val existing = supabase.from("nova_taxi_profiles").select {
                            filter { eq("id", uid) }
                        }.decodeList<TaxiProfile>().firstOrNull()
                            ?: error("A conta autenticou, mas o perfil ainda não existe no servidor. Confirme a migration de criação de perfis e tente entrar novamente.")

                        if (!existing.ativo) error("Esta conta está desativada. Contacte o suporte NOVA Táxi.")
                        val effectiveRole = if (existing.tipoUtilizador == "motorista") "DRIVER" else "PASSENGER"
                        loggedUid = uid
                        loggedRole = effectiveRole
                        logged = true
                        message = if (effectiveRole == "DRIVER") {
                            val driver = supabase.from("nova_taxi_driver_profiles").select {
                                filter { eq("id", uid) }
                            }.decodeList<TaxiDriverProfile>().firstOrNull()
                            if (driver?.aprovado == true) "Conta de motorista aprovada. Pode solicitar ativação do GPS."
                            else "Conta criada. O motorista aguarda aprovação administrativa; o GPS operacional permanece bloqueado."
                        } else {
                            "Sessão iniciada com sucesso. Bem-vindo à NOVA Táxi."
                        }
                    }
                } catch (e: Exception) {
                    message = e.message ?: "Não foi possível concluir a operação. Verifique a configuração de Auth e as políticas RLS."
                } finally {
                    busy = false
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "A processar…" else if (isLogin) "Entrar" else "Criar conta")
        }
        TextButton(onClick = {
            isLogin = !isLogin
            message = ""
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isLogin) "Ainda não tenho conta — criar conta" else "Já tenho conta — entrar")
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("Cadastro por e-mail. Não é pedido método de pagamento nesta etapa.", style = MaterialTheme.typography.bodySmall)
    }
}
