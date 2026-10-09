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
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
private const val SUPABASE_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) {
    install(Auth)
    install(Postgrest)
}

@Serializable
data class InterprovincialSchedule(
    val id: String,
    @SerialName("route_id") val routeId: String,
    @SerialName("departure_at") val departureAt: String,
    @SerialName("arrival_at") val arrivalAt: String? = null,
    @SerialName("price_aoa") val priceAoa: Double? = null,
    @SerialName("seats_total") val seatsTotal: Int,
    val status: String,
    @SerialName("payment_cash_enabled") val cashEnabled: Boolean = true,
    @SerialName("payment_integrated_enabled") val integratedEnabled: Boolean = false
)
@Serializable
data class InterprovincialRoute(
    val id: String,
    @SerialName("company_id") val companyId: String,
    @SerialName("origin_province") val origin: String,
    @SerialName("destination_province") val destination: String,
    @SerialName("transport_mode") val transportMode: String,
    val status: String
)
@Serializable
data class InterprovincialCompany(val id: String, @SerialName("display_name") val displayName: String, val status: String)

@Serializable
data class TaxiProfile(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("full_name") val fullName: String,
    val email: String? = null,
    val phone: String? = null
)

@Serializable
data class TaxiDriverProfile(@SerialName("user_id") val userId: String)

@Serializable
data class DriverLocationPayload(
    @SerialName("driver_id") val driverId: String,
    val lat: Double,
    val lng: Double,
    @SerialName("accuracy_m") val accuracyM: Double?,
    @SerialName("speed_mps") val speedMps: Double?,
    @SerialName("bearing_deg") val bearingDeg: Double?,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("sequence_no") val sequenceNo: Long,
    val source: String = "FUSED",
    @SerialName("mock_location") val mockLocation: Boolean = false
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
                        try { supabase.from("nova_taxi_driver_live_locations").insert(payload) }
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
private fun InterprovincialPanel(onBack: () -> Unit) {
    var origin by remember { mutableStateOf("Luanda") }
    var destination by remember { mutableStateOf("Benguela") }
    var payment by remember { mutableStateOf("cash") }
    var schedules by remember { mutableStateOf<List<InterprovincialSchedule>>(emptyList()) }
    var routes by remember { mutableStateOf<List<InterprovincialRoute>>(emptyList()) }
    var companies by remember { mutableStateOf<List<InterprovincialCompany>>(emptyList()) }
    var message by remember { mutableStateOf("Pesquise partidas reais publicadas.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun searchTrips() {
        busy = true
        message = "A consultar partidas confirmadas no Supabase…"
        scope.launch {
            try {
                val now = Instant.now().toString()
                val foundSchedules = supabase.from("nova_trip_schedules").select {
                    filter { eq("status", "published"); gt("departure_at", now) }
                }.decodeList<InterprovincialSchedule>()
                routes = supabase.from("nova_interprovincial_routes").select().decodeList<InterprovincialRoute>()
                companies = supabase.from("nova_transport_companies").select().decodeList<InterprovincialCompany>()
                val activeIds = companies.filter { it.status == "active" }.map { it.id }.toSet()
                val validRoutes = routes.filter { it.status == "published" && it.companyId in activeIds &&
                    it.origin.equals(origin.trim(), true) && it.destination.equals(destination.trim(), true) }
                val validIds = validRoutes.map { it.id }.toSet()
                schedules = foundSchedules.filter { it.routeId in validIds }
                message = if (schedules.isEmpty()) "Não há partidas reais publicadas para esta pesquisa. Nenhum horário ou preço foi inventado."
                    else "${schedules.size} partida(s) real(is) encontrada(s)."
            } catch (e: Exception) {
                schedules = emptyList()
                message = "Catálogo ainda não activado ou erro de acesso: ${e.message ?: "verifique migração e RLS"}"
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Viagens interprovinciais", style = MaterialTheme.typography.headlineSmall)
        Text("Transportadoras parceiras · sem dados fictícios")
        OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("Origem") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text("Destino") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = payment == "cash", onClick = { payment = "cash" }, label = { Text("Dinheiro") })
            FilterChip(selected = payment == "integrated", onClick = { payment = "integrated" }, label = { Text("Pagamento integrado") })
        }
        Button(onClick = { searchTrips() }, enabled = !busy && origin.isNotBlank() && destination.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "A pesquisar…" else "Pesquisar partidas reais")
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            schedules.forEach { schedule ->
                val route = routes.firstOrNull { it.id == schedule.routeId }
                val company = route?.let { r -> companies.firstOrNull { it.id == r.companyId } }
                val paymentEnabled = if (payment == "cash") schedule.cashEnabled else schedule.integratedEnabled
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(company?.displayName ?: "Transportadora parceira", style = MaterialTheme.typography.titleMedium)
                        Text("${route?.origin ?: origin} → ${route?.destination ?: destination}")
                        Text(if (route?.transportMode == "bus") "Autocarro" else "Viatura privada com motorista")
                        Text("Partida: ${schedule.departureAt}")
                        Text("Preço: ${schedule.priceAoa?.let { String.format("%,.0f Kz", it) } ?: "por confirmar"}")
                        Text("Capacidade registada: ${schedule.seatsTotal} lugares")
                        Button(onClick = {
                            if (schedule.priceAoa == null) {
                                message = "A reserva exige preço real confirmado."
                            } else if (!paymentEnabled) {
                                message = "Esta modalidade de pagamento não está activa para a partida."
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        supabase.postgrest.rpc("book_nova_interprovincial_trip", parameters = buildJsonObject {
                                            put("p_schedule_id", schedule.id)
                                            put("p_seat_count", 1)
                                            put("p_payment_method", payment)
                                        })
                                        message = "Pedido de reserva enviado ao Supabase. Aguarde confirmação da transportadora/pagamento."
                                    } catch (e: Exception) {
                                        message = "Reserva não concluída: ${e.message ?: "verifique a migração, os lugares e as políticas RLS"}"
                                    } finally { busy = false }
                                }
                            }
                        }, enabled = paymentEnabled && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (payment == "cash") "Reservar com dinheiro" else "Reservar com pagamento integrado")
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Voltar") }
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
    var showInterprovincial by remember { mutableStateOf(false) }
    var tripOrigin by remember { mutableStateOf("Luanda") }
    var tripDestination by remember { mutableStateOf("Benguela") }
    var tripDate by remember { mutableStateOf("") }
    var tripPassengers by remember { mutableStateOf("1") }
    var tripSearchMessage by remember { mutableStateOf("") }
    var showInterprovincial by remember { mutableStateOf(false) }\n    var showPromo by remember { mutableStateOf(!activity.getSharedPreferences("nova_taxi_prefs", android.content.Context.MODE_PRIVATE).getBoolean("promo_seen_v1", false)) }
    val scope = rememberCoroutineScope()
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
            if (loggedRole != "DRIVER") {
                Button(onClick = {
                    showInterprovincial = !showInterprovincial
                    tripSearchMessage = ""
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showInterprovincial) "Fechar viagens interprovinciais" else "Viagens interprovinciais")
                }
                if (showInterprovincial) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Viajar entre províncias", style = MaterialTheme.typography.titleLarge)
                            Text("Indique a rota e a data pretendida.", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = tripOrigin,
                                onValueChange = { tripOrigin = it },
                                label = { Text("Província de origem") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = tripDestination,
                                onValueChange = { tripDestination = it },
                                label = { Text("Província de destino") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = tripDate,
                                onValueChange = { tripDate = it },
                                label = { Text("Data (AAAA-MM-DD)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = tripPassengers,
                                onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 2) tripPassengers = value },
                                label = { Text("Número de passageiros") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    tripSearchMessage = when {
                                        tripOrigin.isBlank() || tripDestination.isBlank() -> "Indique a origem e o destino."
                                        tripOrigin.trim().equals(tripDestination.trim(), ignoreCase = true) -> "A origem e o destino devem ser diferentes."
                                        !tripDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> "Indique a data no formato AAAA-MM-DD."
                                        tripPassengers.toIntOrNull() !in 1..20 -> "Indique entre 1 e 20 passageiros."
                                        else -> "Pesquisa preparada para ${tripOrigin.trim()} → ${tripDestination.trim()} em $tripDate. Ainda não há horários reais publicados para consultar. Não foi criada nenhuma reserva nem efectuado pagamento."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Procurar viagens") }
                            if (tripSearchMessage.isNotBlank()) {
                                Text(tripSearchMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Text(
                                "A disponibilidade, os preços e as reservas só serão apresentados após a integração dos dados reais das transportadoras.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
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
        Text(if (isLogin) "Entrar com e-mail" else "Criar conta de teste por e-mail")
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
                            this.email = email.trim()
                            this.password = password
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
                            filter { eq("user_id", uid) }
                        }.decodeList<TaxiProfile>().firstOrNull()
                        val effectiveRole: String
                        if (existing == null) {
                            val profile = TaxiProfile(userId = uid, role = role, fullName = name.trim(), email = email.trim().lowercase())
                            supabase.from("nova_taxi_profiles").upsert(profile) { onConflict = "user_id" }
                            if (role == "DRIVER") {
                                supabase.from("nova_taxi_driver_profiles").upsert(TaxiDriverProfile(userId = uid)) { onConflict = "user_id" }
                            }
                            effectiveRole = role
                        } else {
                            effectiveRole = existing.role
                            if (existing.email.isNullOrBlank()) {
                                supabase.from("nova_taxi_profiles").update({
                                    set("email", email.trim().lowercase())
                                }) {
                                    filter { eq("user_id", uid) }
                                }
                            }
                        }
                        loggedUid = uid
                        loggedRole = effectiveRole
                        logged = true
                        message = if (effectiveRole == "DRIVER") "Perfil guardado. A aprovação administrativa é necessária antes do GPS operacional." else "Registo concluído e guardado no Supabase."
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
        Text("Registo de teste por e-mail. Não é pedido método de pagamento no cadastro.", style = MaterialTheme.typography.bodySmall)
    }
}
