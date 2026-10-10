package ao.novataxi.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.time.Instant

private val NovaOrange = Color(0xFFF97316)
private val NovaInk = Color(0xFF242424)
private val NovaCream = Color(0xFFFFF7ED)
private val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
) {
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
data class DriverProfile(
    @SerialName("id") val id: String,
    @SerialName("aprovado") val approved: Boolean = false,
    @SerialName("disponivel") val available: Boolean = false
)

@Serializable
data class RidePayload(
    @SerialName("passageiro_id") val passengerId: String,
    @SerialName("categoria") val category: String,
    @SerialName("origem_texto") val originText: String,
    @SerialName("destino_texto") val destinationText: String,
    @SerialName("origem_lat") val originLat: Double,
    @SerialName("origem_lng") val originLng: Double,
    @SerialName("destino_lat") val destinationLat: Double,
    @SerialName("destino_lng") val destinationLng: Double,
    @SerialName("estado") val state: String = "solicitada",
    @SerialName("metodo_pagamento") val paymentMethod: String
)

@Serializable
data class RideRecord(
    @SerialName("id") val id: String,
    @SerialName("categoria") val category: String,
    @SerialName("origem_texto") val origin: String,
    @SerialName("destino_texto") val destination: String,
    @SerialName("estado") val state: String,
    @SerialName("criado_em") val createdAt: String? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var activeDriverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = NovaOrange, onPrimary = Color.White, secondary = Color(0xFFEA580C), background = Color.White, surface = Color.White, onSurface = NovaInk)) { NovaTaxiApp(this) } }
    }

    fun readCurrentLocation(onResult: (Location?, String?) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult(null, "Permissão de localização não concedida.")
            return
        }
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location -> onResult(location, if (location == null) "Não foi possível obter GPS. Ative a localização e tente novamente." else null }
                .addOnFailureListener { onResult(null, it.message ?: "Falha ao obter localização.") }
        } catch (e: SecurityException) { onResult(null, "Permissão de localização não concedida.") }
    }

    fun startDriverGps(driverId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        activeDriverId = driverId
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateIntervalMillis(3_000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val uid = activeDriverId ?: return
                result.locations.forEach { loc ->
                    if (loc.isMock) return@forEach
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            supabase.from("nova_taxi_driver_locations").upsert(
                                mapOf("motorista_id" to uid, "latitude" to loc.latitude, "longitude" to loc.longitude,
                                    "accuracy_m" to if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                                    "heading" to if (loc.hasBearing()) loc.bearing.toDouble() else null,
                                    "captured_at" to Instant.ofEpochMilli(loc.time).toString())
                            )
                        } catch (_: Exception) { }
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
    var role by remember { mutableStateOf("passageiro") }
    var isLogin by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var loggedRole by remember { mutableStateOf("passageiro") }
    var loggedUid by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var driverApproved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var originText by remember { mutableStateOf("") }
    var destinationText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("cool") }
    var payment by remember { mutableStateOf("cash") }
    var originLat by remember { mutableStateOf<Double?>(null) }
    var originLng by remember { mutableStateOf<Double?>(null) }
    var destinationLat by remember { mutableStateOf<Double?>(null) }
    var destinationLng by remember { mutableStateOf<Double?>(null) }
    var rides by remember { mutableStateOf<List<RideRecord>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) activity.readCurrentLocation { loc, err ->
            if (loc != null) {
                originLat = loc.latitude; originLng = loc.longitude
                if (originText.isBlank()) originText = "Localização atual"
                message = "Origem definida com GPS real."
            } else message = err ?: "Não foi possível obter GPS."
        } else message = "Autorize a localização para definir a origem da viagem."
    }

    fun refreshRides() {
        scope.launch {
            try {
                rides = supabase.from("nova_taxi_rides").select {
                    filter { eq("passageiro_id", loggedUid) }
                }.decodeList<RideRecord>()
                message = "Histórico atualizado a partir do Supabase."
            } catch (e: Exception) { message = "Falha ao consultar corridas: ${e.message ?: "erro de rede"}" }
        }
    }

    if (!logged) {
        Column(Modifier.fillMaxSize().background(NovaCream).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NOVA", color = NovaOrange, style = MaterialTheme.typography.labelLarge)
            Text("Táxi Personalizado", color = NovaInk, style = MaterialTheme.typography.headlineLarge)
            Text("Pedimos. Chegamos.", color = NovaInk, style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (isLogin) "Entrar na sua conta" else "Criar conta", style = MaterialTheme.typography.titleLarge)
                    if (!isLogin) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("Quero usar a NOVA Táxi como")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = role == "passageiro", onClick = { role = "passageiro" }, label = { Text("Passageiro") })
                            FilterChip(selected = role == "motorista", onClick = { role = "motorista" }, label = { Text("Motorista") })
                        }
                    }
                    OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text("E-mail") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Palavra-passe (mín. 8 caracteres)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = !busy && email.contains("@") && password.length >= 8 && (isLogin || name.isNotBlank()), onClick = {
                        busy = true; message = if (isLogin) "A autenticar…" else "A criar conta no Supabase…"
                        scope.launch {
                            try {
                                if (!isLogin) {
                                    supabase.auth.signUpWith(Email) {
                                        this.email = email.trim()
                                        this.password = password
                                        data = buildJsonObject { put("tipo_utilizador", role); put("nome", name.trim()) }
                                    }
                                } else {
                                    supabase.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
                                }
                                val uid = supabase.auth.currentUserOrNull()?.id
                                if (uid == null) {
                                    if (!isLogin) { isLogin = true; message = "Pedido de registo enviado. Se a confirmação de e-mail estiver ativa, confirme o e-mail antes de entrar." }
                                    else error("Não foi criada uma sessão. Confirme o e-mail e verifique as definições de autenticação.")
                                } else {
                                    val profile = supabase.from("nova_taxi_profiles").select { filter { eq("id", uid) } }.decodeList<TaxiProfile>().firstOrNull()
                                        ?: error("A conta autenticou, mas não existe perfil em nova_taxi_profiles. Verifique o trigger de registo.")
                                    loggedUid = uid; loggedRole = profile.role; displayName = profile.fullName
                                    if (profile.role == "motorista") {
                                        driverApproved = try { supabase.from("nova_taxi_driver_profiles").select { filter { eq("id", uid) } }.decodeList<DriverProfile>().firstOrNull()?.approved == true } catch (_: Exception) { false }
                                    }
                                    logged = true
                                    message = if (loggedRole == "motorista" && !driverApproved) "Conta criada. O motorista precisa de aprovação administrativa antes de ficar operacional." else "Sessão e perfil confirmados no Supabase."
                                }
                            } catch (e: Exception) { message = e.message ?: "Falha de autenticação. Verifique a configuração do Supabase." }
                            finally { busy = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "A processar…" else if (isLogin) "Entrar" else "Criar conta") }
                    TextButton(onClick = { isLogin = !isLogin; message = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (isLogin) "Ainda não tenho conta — registar" else "Já tenho conta — entrar") }
                    if (message.isNotBlank()) Text(message, color = NovaInk, style = MaterialTheme.typography.bodySmall)
                    Text("O cadastro não solicita dados nem método de pagamento.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("NOVA Táxi", color = NovaOrange, style = MaterialTheme.typography.headlineMedium)
        Text("Olá, ${displayName.ifBlank { "utilizador" }}", style = MaterialTheme.typography.titleLarge)
        Text(if (loggedRole == "motorista") "Área do motorista" else "Peça a sua corrida", style = MaterialTheme.typography.titleMedium)
        if (loggedRole == "motorista") {
            if (!driverApproved) {
                Text("A conta aguarda aprovação. O GPS e as operações de motorista permanecem bloqueados até à aprovação.", color = Color(0xFF9A3412))
            } else {
                Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); activity.startDriverGps(loggedUid) }, modifier = Modifier.fillMaxWidth()) { Text("Ativar GPS do motorista") }
                Text("O GPS usa a localização real do dispositivo; não são criadas posições fictícias.")
            }
        } else {
            OutlinedTextField(value = originText, onValueChange = { originText = it }, label = { Text("Local de partida") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth()) { Text(if (originLat == null) "Definir partida com GPS real" else "Atualizar localização GPS") }
            if (originLat != null && originLng != null) Text("GPS de partida: %.5f, %.5f".format(originLat, originLng), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = destinationText, onValueChange = { destinationText = it }, label = { Text("Destino") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Toque no mapa para marcar o destino real. Não será enviada uma corrida sem coordenadas.", style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { ctx ->
                MapView(ctx).apply {
                    onCreate(null)
                    getMapAsync { map ->
                        map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                        map.cameraPosition = CameraPosition.Builder().target(LatLng(-8.8390, 13.2894)).zoom(11.0).build()
                        map.addOnMapClickListener { point ->
                            destinationLat = point.latitude; destinationLng = point.longitude
                            if (destinationText.isBlank()) destinationText = "Destino marcado no mapa"
                            message = "Destino selecionado no mapa: %.5f, %.5f".format(point.latitude, point.longitude)
                            true
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(260.dp))
            if (destinationLat != null && destinationLng != null) Text("Destino: %.5f, %.5f".format(destinationLat, destinationLng), style = MaterialTheme.typography.bodySmall)
            Text("Categoria")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == "cool", onClick = { category = "cool" }, label = { Text("Cool") })
                FilterChip(selected = category == "executivo", onClick = { category = "executivo" }, label = { Text("Executivo") })
            }
            Text("Pagamento (escolha ao pedir a corrida)")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("cash" to "Dinheiro", "kwik" to "KWiK", "multicaixa_express" to "Multicaixa Express", "referencia" to "Referência").forEach { (value, label) ->
                    FilterChip(selected = payment == value, onClick = { payment = value }, label = { Text(label) })
                }
            }
            Button(enabled = !busy && originLat != null && originLng != null && destinationLat != null && destinationLng != null && originText.isNotBlank() && destinationText.isNotBlank(), onClick = {
                busy = true; message = "A enviar pedido de corrida real…"
                scope.launch {
                    try {
                        supabase.from("nova_taxi_rides").insert(RidePayload(loggedUid, category, originText.trim(), destinationText.trim(), originLat!!, originLng!!, destinationLat!!, destinationLng!!, paymentMethod = payment))
                        message = "Pedido enviado ao Supabase. A disponibilidade depende de motoristas aprovados e online."
                        refreshRides()
                    } catch (e: Exception) { message = "Não foi possível criar a corrida: ${e.message ?: "erro Supabase/RLS"}. Nenhum pedido fictício foi criado." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "A enviar…" else "Pedir corrida") }
            OutlinedButton(onClick = { refreshRides() }, modifier = Modifier.fillMaxWidth()) { Text("Atualizar histórico de corridas") }
            if (rides.isNotEmpty()) {
                Text("As suas corridas", style = MaterialTheme.typography.titleMedium)
                rides.forEach { ride ->
                    Card(colors = CardDefaults.cardColors(containerColor = NovaCream), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${ride.category.uppercase()} • ${ride.state}")
                            Text("${ride.origin} → ${ride.destination}")
                            Text("ID: ${ride.id}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = NovaInk)
        TextButton(onClick = { scope.launch { try { supabase.auth.signOut() } catch (_: Exception) {}; logged = false; message = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Terminar sessão") }
    }
}
