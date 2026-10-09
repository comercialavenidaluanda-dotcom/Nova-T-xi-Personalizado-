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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
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
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) { install(Auth) }

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
        setContent { NovaTaxiTheme { NovaTaxiApp(this) } }
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
        var selectedService by remember { mutableStateOf("🚗 Corrida Cool") }
        var origin by remember { mutableStateOf("Minha localização") }
        var destination by remember { mutableStateOf("") }
        var paymentMethod by remember { mutableStateOf("Dinheiro") }
        var requestMessage by remember { mutableStateOf("") }
        val services = listOf(
            "🚗 Corrida Cool" to "Carro para o dia a dia",
            "✨ Executivo" to "Viagem com mais conforto",
            "✈️ Transfer aeroporto/hotel" to "Transfer privado e agendado",
            "📦 Entregas" to "Documentos e pequenas encomendas",
            "🏍️ Moto" to "Deslocações e entregas rápidas",
            "🛍️ Compras" to "Compras de lojas e supermercados",
            "🛠️ Assistência rodoviária" to "Apoio quando o veículo avaria",
            "🏢 NOVA Empresas" to "Gestão de viagens para equipas",
            "🚘 Aluguer de viaturas" to "Aluguer sujeito a disponibilidade",
            "🚌 Transporte coletivo" to "Viagens coletivas e lugares agendados"
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("NOVA", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Pedimos. Chegamos.", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = {
                    logged = false
                    message = ""
                }) { Text("Sair") }
            }
            Text(
                if (loggedRole == "DRIVER") "Área do motorista" else "O que precisa hoje?",
                style = MaterialTheme.typography.titleLarge
            )
            if (loggedRole == "DRIVER") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Painel do motorista", style = MaterialTheme.typography.titleMedium)
                        Text("Ative a localização apenas quando estiver pronto para trabalhar. O estado operacional depende da aprovação no servidor.")
                        Button(onClick = {
                            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Ativar GPS") }
                    }
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Onde vamos buscar-lhe?", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = origin,
                            onValueChange = { origin = it },
                            label = { Text("Origem") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = { Text("Destino ou morada de entrega") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Escolha um serviço", style = MaterialTheme.typography.titleMedium)
                        services.chunked(2).forEach { rowServices ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowServices.forEach { item ->
                                    val title = item.first
                                    val subtitle = item.second
                                    Card(
                                        onClick = {
                                            selectedService = title
                                            requestMessage = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selectedService == title) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(title, style = MaterialTheme.typography.titleSmall)
                                            Text(subtitle, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                if (rowServices.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (selectedService.contains("Corrida Cool") || selectedService.contains("Executivo") || selectedService.contains("Transfer aeroporto")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E8))
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Pagamento seguro no fim da corrida", style = MaterialTheme.typography.titleSmall, color = Color(0xFFB83A08))
                                    Text("Depois de o motorista encerrar a corrida, poderá escolher Multicaixa Express ou Referência Multicaixa. KWiK e IBAN serão ativados após configurar os respetivos canais.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(
                            onClick = {
                                requestMessage = if (destination.isBlank()) {
                                    "Indique primeiro o destino para continuar."
                                } else {
                                    "Serviço selecionado: $selectedService. A interface está preparada, mas o pedido real ainda precisa de ser ligado à função segura de despacho e às tabelas do Supabase; não foi criada uma corrida fictícia."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Continuar com $selectedService") }
                        if (requestMessage.isNotBlank()) Text(requestMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Mapa e localização", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth().height(250.dp)) {
                AndroidView(factory = { ctx ->
                    MapView(ctx).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.setStyle("https://tiles.openfreemap.org/styles/liberty")
                            map.cameraPosition = CameraPosition.Builder().target(LatLng(-8.8390, 13.2894)).zoom(11.0).build()
                        }
                    }
                }, modifier = Modifier.fillMaxSize())
            }
            Text("Serviços NOVA", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB83A08))
            Text("Corridas, transfer aeroporto/hotel, entregas, moto, compras, assistência rodoviária, NOVA Empresas, aluguer de viaturas e transporte coletivo.")
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            Text("A disponibilidade real, tarifas, motoristas, encomendas e pagamentos devem vir do backend; esta interface não apresenta dados de demonstração.", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun NovaTaxiTheme(content: @Composable () -> Unit) {
    val novaColors = lightColorScheme(
        primary = Color(0xFFFF5A1F),
        onPrimary = Color.White,
        secondary = Color(0xFFFFB21A),
        onSecondary = Color(0xFF2A160B),
        tertiary = Color(0xFF14A878),
        background = Color(0xFFFFFAF6),
        surface = Color.White,
        surfaceVariant = Color(0xFFFFF0E6),
        secondaryContainer = Color(0xFFFFDCC8),
        onSecondaryContainer = Color(0xFF5B2100)
    )
    MaterialTheme(colorScheme = novaColors, content = content)
}
