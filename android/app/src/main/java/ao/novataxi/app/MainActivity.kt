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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.time.Instant

private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
private const val SUPABASE_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) {
    install(Auth)
    install(Postgrest)
}

@Serializable
data class TaxiProfile(
    @SerialName("id") val userId: String,
    @SerialName("tipo_utilizador") val role: String,
    @SerialName("nome") val fullName: String,
    @SerialName("telefone") val phone: String? = null,
    @SerialName("ativo") val active: Boolean = true
)

@Serializable
data class CompletedRide(
    @SerialName("id") val id: String,
    @SerialName("valor_final") val finalAmount: Long? = null,
    @SerialName("estado") val state: String
)

@Serializable
data class DriverLocationPayload(
    @SerialName("motorista_id") val driverId: String,
    @SerialName("latitude") val lat: Double,
    @SerialName("longitude") val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Double?,
    @SerialName("heading") val bearingDeg: Double?,
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
                        bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
                        capturedAt = Instant.ofEpochMilli(location.time).toString()
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        try { supabase.from("nova_taxi_driver_locations").insert(payload) }
                        catch (_: Exception) { /* The server rejects GPS until the driver is approved. */ }
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
    val completedRides = remember { mutableStateListOf<CompletedRide>() }
    var selectedRideId by remember { mutableStateOf("") }
    var expressMobile by remember { mutableStateOf("") }
    var paymentBusy by remember { mutableStateOf(false) }
    var paymentMessage by remember { mutableStateOf("") }

    fun loadCompletedRides() {
        scope.launch {
            try {
                val rides = withContext(Dispatchers.IO) {
                    supabase.from("nova_taxi_rides").select {
                        filter {
                            eq("passageiro_id", loggedUid)
                            eq("estado", "concluida")
                        }
                    }.decodeList<CompletedRide>()
                }
                completedRides.clear()
                completedRides.addAll(rides.filter { (it.finalAmount ?: 0L) > 0L })
                if (completedRides.none { it.id == selectedRideId }) selectedRideId = completedRides.firstOrNull()?.id.orEmpty()
                paymentMessage = if (completedRides.isEmpty()) "Não há corridas concluídas e por pagar disponíveis nesta conta." else "Corridas concluídas carregadas do Supabase."
            } catch (e: Exception) {
                paymentMessage = "Não foi possível carregar as corridas: " + (e.message ?: "erro de ligação")
            }
        }
    }

    fun startPayment(method: String) {
        if (selectedRideId.isBlank()) {
            paymentMessage = "Selecione uma corrida concluída."
            return
        }
        if (method == "multicaixa_express" && !Regex("^9\\d{8}$").matches(expressMobile.filter { it.isDigit() })) {
            paymentMessage = "Introduza um número móvel angolano válido com 9 dígitos, começado por 9."
            return
        }
        paymentBusy = true
        paymentMessage = "A solicitar instruções de pagamento no BitPay sandbox…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val token = supabase.auth.currentAccessTokenOrNull() ?: error("Sessão expirada. Entre novamente.")
                    val conn = (URL("$SUPABASE_URL/functions/v1/nova-taxi-create-payment-intent").openConnection() as HttpURLConnection)
                    try {
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 15000
                        conn.readTimeout = 20000
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.setRequestProperty("apikey", SUPABASE_KEY)
                        val body = JSONObject().put("ride_id", selectedRideId).put("payment_method", method)
                        if (method == "multicaixa_express") body.put("mobile", expressMobile.filter { it.isDigit() })
                        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                        val status = conn.responseCode
                        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        Pair(status, JSONObject(raw.ifBlank { "{}" }))
                    } finally { conn.disconnect() }
                }
                val status = result.first
                val json = result.second
                val payment = json.optJSONObject("payment")
                val refEntity = payment?.optString("entidade_referencia", "").orEmpty()
                val refNumber = payment?.optString("numero_referencia", "").orEmpty()
                val state = payment?.optString("estado", "").orEmpty()
                paymentMessage = when {
                    status in 200..299 && (refNumber.isNotBlank() || refEntity.isNotBlank()) ->
                        "Pedido criado no sandbox. Estado: $state. Entidade: $refEntity. Referência: $refNumber. O pagamento só fica confirmado após validação do webhook."
                    status in 200..299 -> "Pedido de pagamento recebido no sandbox. Estado ainda não confirmado. " + json.optString("error", "")
                    status == 202 -> "Estado do pagamento desconhecido. Não crie outro pagamento; atualize/verifique a mesma tentativa. " + json.optString("error", "")
                    else -> "BitPay não aceitou o pedido (HTTP $status): " + json.optString("error", json.optString("message", "erro não especificado"))
                }
            } catch (e: Exception) {
                paymentMessage = "Não foi possível iniciar o pagamento: " + (e.message ?: "erro de ligação") + ". Confirme o estado antes de tentar novamente."
            } finally {
                paymentBusy = false
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && loggedRole == "DRIVER") {
            activity.startDriverGps(loggedUid)
            message = "Pedido de GPS iniciado. O servidor só aceitará posições após a aprovação do motorista."
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
            if (loggedRole == "PASSENGER") {
                Text("Pagamentos de corridas", style = MaterialTheme.typography.titleLarge)
                Text("Os pagamentos estão em modo de teste (sandbox). Não são cobranças reais.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { loadCompletedRides() }, enabled = !paymentBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Atualizar corridas concluídas")
                }
                if (completedRides.isEmpty()) {
                    Text("Nenhuma corrida concluída carregada. Atualize depois de uma corrida válida.", style = MaterialTheme.typography.bodySmall)
                } else {
                    completedRides.forEach { ride ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = selectedRideId == ride.id, onClick = { selectedRideId = ride.id })
                            Column(Modifier.weight(1f)) {
                                Text("Corrida " + ride.id.take(8).uppercase())
                                Text("Valor final: " + (ride.finalAmount ?: 0L) + " AOA", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = expressMobile,
                        onValueChange = { expressMobile = it.filter(Char::isDigit).take(9) },
                        label = { Text("Número Multicaixa Express (9 dígitos)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { startPayment("multicaixa_express") }, enabled = !paymentBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (paymentBusy) "A processar…" else "Pagar com Multicaixa Express")
                    }
                    OutlinedButton(onClick = { startPayment("multicaixa_reference") }, enabled = !paymentBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("Gerar pagamento por referência")
                    }
                }
                if (paymentMessage.isNotBlank()) Text(paymentMessage, style = MaterialTheme.typography.bodySmall)
            }
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
        Text(if (isLogin) "Entrar com e-mail" else "Criar conta por e-mail")
        if (!isLogin) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth())
            Text("Registar como")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "PASSENGER", onClick = { role = "PASSENGER" }, label = { Text("Passageiro") })
                FilterChip(selected = role == "DRIVER", onClick = { role = "DRIVER" }, label = { Text("Motorista") })
            }
        }
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-mail") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Palavra-passe (mínimo 8 caracteres)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.contains("@") && password.length >= 8 && (isLogin || name.isNotBlank()), onClick = {
            busy = true
            message = if (isLogin) "A autenticar…" else "A criar a conta no Supabase…"
            scope.launch {
                try {
                    if (!isLogin) {
                        supabase.auth.signUpWith(Email) {
                            this.email = email.trim()
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
                        val profile = supabase.from("nova_taxi_profiles").select {
                            filter { eq("id", uid) }
                        }.decodeList<TaxiProfile>().firstOrNull()
                            ?: error("A autenticação foi concluída, mas o perfil não foi criado pelo trigger do Supabase. Verifique o trigger de cadastro.")

                        loggedUid = uid
                        loggedRole = if (profile.role.equals("motorista", ignoreCase = true)) "DRIVER" else "PASSENGER"
                        logged = true
                        if (loggedRole == "PASSENGER") loadCompletedRides()
                        message = if (loggedRole == "DRIVER") {
                            "Conta ligada ao Supabase. A aprovação administrativa é necessária antes do GPS operacional."
                        } else {
                            "Registo concluído e perfil real confirmado no Supabase."
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
        Text("Registo por e-mail. Não é pedido método de pagamento no cadastro.", style = MaterialTheme.typography.bodySmall)
    }
}
