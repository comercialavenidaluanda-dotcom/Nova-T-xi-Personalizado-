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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import java.time.Instant
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val SUPABASE_URL = "https://vgbnnikfsmprcpvtypuh.supabase.co"
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) { install(Auth) }

@Serializable
data class TaxiProfile(
    val id: String,
    @SerialName("tipo_utilizador") val role: String,
    @SerialName("nome") val fullName: String? = null,
    @SerialName("telefone") val phone: String? = null,
    @SerialName("ativo") val active: Boolean = true,
    @SerialName("criado_em") val createdAt: String? = null,
    @SerialName("atualizado_em") val updatedAt: String? = null
)

@Serializable
data class TaxiProfilePayload(
    val id: String,
    @SerialName("tipo_utilizador") val role: String,
    @SerialName("nome") val fullName: String,
    @SerialName("telefone") val phone: String? = null
)

@Serializable
data class TaxiDriverProfile(val id: String)

@Serializable
data class CompletedRide(
    val id: String,
    @SerialName("valor_final") val finalAmount: Double? = null,
    @SerialName("origem_texto") val origin: String? = null,
    @SerialName("destino_texto") val destination: String? = null,
    @SerialName("criado_em") val createdAt: String? = null,
    val estado: String = "concluida"
)

@Serializable
data class DriverLocationPayload(
    @SerialName("motorista_id") val driverId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyM: Double?,
    val heading: Double?,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("updated_at") val updatedAt: String
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
                    val timestamp = Instant.ofEpochMilli(location.time).toString()
                    val payload = DriverLocationPayload(
                        driverId = uid,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                        heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                        capturedAt = timestamp,
                        updatedAt = Instant.now().toString()
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        try { supabase.from("nova_taxi_driver_locations").upsert(payload) { onConflict = "motorista_id" } }
                        catch (_: Exception) { /* Server accepts GPS only for an approved, available driver. */ }
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
    var phone by remember { mutableStateOf("") }
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5A1F))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            NovaMark(Modifier.size(64.dp))
                            Text("PEDIMOS. CHEGAMOS.", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text("A tua cidade. O teu caminho. A tua NOVA.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E6))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("🚗 PASSAGEIROS", style = MaterialTheme.typography.titleSmall, color = Color(0xFFB83A08))
                            Text("Poupe 5% nas três primeiras corridas elegíveis.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6D9))) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("🧡 MOTORISTAS", style = MaterialTheme.typography.titleSmall, color = Color(0xFF7A3B00))
                            Text("Campanha indicativa de ganhos potenciais até 140.000 Kz por semana.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("Descontos sujeitos às condições da campanha. Ganhos não garantidos; variam com procura, horas trabalhadas e despesas.")
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
        var paymentMethodChoice by remember { mutableStateOf("multicaixa_reference") }
        var paymentMobile by remember { mutableStateOf("") }
        var paymentDetails by remember { mutableStateOf("") }
        var paying by remember { mutableStateOf(false) }
        var completedRide by remember { mutableStateOf<CompletedRide?>(null) }
        LaunchedEffect(loggedUid, loggedRole) {
            if (loggedRole == "PASSENGER" && loggedUid.isNotBlank()) {
                completedRide = try { loadLatestCompletedRide(loggedUid) } catch (_: Exception) { null }
            } else {
                completedRide = null
            }
        }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NovaMark(Modifier.size(48.dp))
                    Column {
                        Text("NOVA", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Pedimos. Chegamos.", style = MaterialTheme.typography.bodyMedium)
                    }
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
            if (loggedRole == "PASSENGER") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1E8))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pagamentos seguros", style = MaterialTheme.typography.titleMedium, color = Color(0xFFB83A08))
                        if (completedRide == null) {
                            Text("As opções de pagamento aparecem aqui depois de uma corrida ser concluída.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                scope.launch {
                                    paymentDetails = ""
                                    completedRide = try { loadLatestCompletedRide(loggedUid) } catch (_: Exception) { null }
                                    if (completedRide == null) paymentDetails = "Ainda não encontrámos uma corrida concluída para esta conta."
                                }
                            }) { Text("Atualizar corridas concluídas") }
                        } else {
                            Text("Corrida concluída: ${completedRide!!.origin ?: "Origem"} → ${completedRide!!.destination ?: "Destino"}", style = MaterialTheme.typography.bodyMedium)
                            Text("Total: ${completedRide!!.finalAmount?.toLong() ?: 0L} Kz", style = MaterialTheme.typography.titleLarge, color = Color(0xFFB83A08))
                            Text("Escolha o método para iniciar a cobrança no sandbox.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterChip(
                                    selected = paymentMethodChoice == "multicaixa_express",
                                    onClick = { paymentMethodChoice = "multicaixa_express"; paymentDetails = "" },
                                    label = { Text("Express") }
                                )
                                FilterChip(
                                    selected = paymentMethodChoice == "multicaixa_reference",
                                    onClick = { paymentMethodChoice = "multicaixa_reference"; paymentDetails = "" },
                                    label = { Text("Referência") }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("KWiK · em configuração") })
                                FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("IBAN · em configuração") })
                            }
                            if (paymentMethodChoice == "multicaixa_express") {
                                OutlinedTextField(
                                    value = paymentMobile,
                                    onValueChange = { paymentMobile = it.filter { it.isDigit() }.take(9) },
                                    label = { Text("Número Multicaixa Express") },
                                    placeholder = { Text("9XXXXXXXX") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            Button(
                                enabled = !paying && (paymentMethodChoice != "multicaixa_express" || paymentMobile.length == 9),
                                onClick = {
                                    val ride = completedRide ?: return@Button
                                    paying = true
                                    paymentDetails = "A solicitar cobrança segura à BitPay sandbox…"
                                    scope.launch {
                                        try {
                                            val token = supabase.auth.currentAccessTokenOrNull()
                                                ?: error("A sessão expirou. Entre novamente.")
                                            val result = withContext(Dispatchers.IO) {
                                                requestNovaPayment(ride.id, paymentMethodChoice, paymentMobile, token)
                                            }
                                            val statusCode = result.first
                                            val payload = result.second
                                            val payment = payload.optJSONObject("payment")
                                            val refNumber = payment?.optString("numero_referencia").orEmpty().takeUnless { it == "null" || it.isBlank() }
                                            val entity = payment?.optString("entidade_referencia").orEmpty().takeUnless { it == "null" || it.isBlank() }
                                            paymentDetails = when {
                                                statusCode == 202 -> "O estado está em reconciliação. Não crie outra cobrança; atualize o estado mais tarde."
                                                statusCode !in 200..299 -> "Não foi possível iniciar o pagamento: ${payload.optString("error", "erro desconhecido")}."
                                                paymentMethodChoice == "multicaixa_reference" && refNumber != null -> "Referência criada no sandbox. Entidade: ${entity ?: "—"}. Referência: $refNumber. Só ficará pago após confirmação assinada do prestador."
                                                paymentMethodChoice == "multicaixa_express" -> "Pedido enviado ao Multicaixa Express sandbox. Confirme no telemóvel de teste; o pagamento só será confirmado pelo webhook."
                                                else -> "Pedido de pagamento registado. Estado: ${payment?.optString("estado") ?: "pendente"}."
                                            }
                                        } catch (e: Exception) {
                                            paymentDetails = e.message ?: "Não foi possível contactar o serviço de pagamentos."
                                        } finally {
                                            paying = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (paying) "A processar…" else "Pagar com segurança") }
                            TextButton(onClick = {
                                scope.launch {
                                    completedRide = try { loadLatestCompletedRide(loggedUid) } catch (_: Exception) { completedRide }
                                }
                            }) { Text("Atualizar corrida") }
                        }
                        if (paymentDetails.isNotBlank()) Text(paymentDetails, style = MaterialTheme.typography.bodySmall)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NovaMark(Modifier.size(56.dp))
            Column {
                Text("NOVA Táxi", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text("Pedimos. Chegamos.")
            }
        }
        Text(if (isLogin) "Entrar com e-mail" else "Criar conta de teste por e-mail")
        if (!isLogin) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(13) },
                label = { Text("Telefone") },
                placeholder = { Text("+244 9XXXXXXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Registar como")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "PASSENGER", onClick = { role = "PASSENGER" }, label = { Text("Passageiro") })
                FilterChip(selected = role == "DRIVER", onClick = { role = "DRIVER" }, label = { Text("Motorista") })
            }
        }
        OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text("E-mail") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Palavra-passe (mínimo 8 caracteres)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.contains("@") && password.length >= 8 && (isLogin || (name.isNotBlank() && phone.count { it.isDigit() } >= 9)), onClick = {
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
                            filter { eq("id", uid) }
                        }.decodeList<TaxiProfile>().firstOrNull()
                        val effectiveRole: String
                        if (existing == null) {
                            val dbRole = if (role == "DRIVER") "motorista" else "passageiro"
                            val profile = TaxiProfilePayload(id = uid, role = dbRole, fullName = name.trim(), phone = phone.trim().ifBlank { null })
                            supabase.from("nova_taxi_profiles").upsert(profile) { onConflict = "id" }
                            if (role == "DRIVER") {
                                supabase.from("nova_taxi_driver_profiles").upsert(TaxiDriverProfile(id = uid)) { onConflict = "id" }
                            }
                            effectiveRole = role
                        } else {
                            effectiveRole = if (existing.role == "motorista") "DRIVER" else "PASSENGER"
                            if (existing.fullName.isNullOrBlank() && name.isNotBlank()) {
                                supabase.from("nova_taxi_profiles").update({
                                    set("nome", name.trim())
                                }) { filter { eq("id", uid) } }
                            }
                            if (existing.phone.isNullOrBlank() && phone.isNotBlank()) {
                                supabase.from("nova_taxi_profiles").update({
                                    set("telefone", phone.trim())
                                }) { filter { eq("id", uid) } }
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
        Text(if (isLogin) "Entre com a conta NOVA Táxi. Se acabou de confirmar o e-mail, use a mesma conta." else "Nome e telefone são obrigatórios no cadastro. Não pedimos método de pagamento nesta etapa.", style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
private fun NovaMark(modifier: Modifier = Modifier.size(48.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0xFFFF5A1F),
            cornerRadius = CornerRadius(w * 0.26f, h * 0.26f)
        )
        val road = Path().apply {
            moveTo(w * 0.28f, h * 0.78f)
            cubicTo(w * 0.39f, h * 0.61f, w * 0.47f, h * 0.47f, w * 0.70f, h * 0.23f)
        }
        drawPath(road, Color.White, style = Stroke(width = w * 0.105f, cap = StrokeCap.Round))
        drawCircle(Color(0xFFFFC247), radius = w * 0.085f, center = Offset(w * 0.72f, h * 0.22f))
        drawLine(Color.White, Offset(w * 0.32f, h * 0.60f), Offset(w * 0.40f, h * 0.52f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
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

private suspend fun loadLatestCompletedRide(userId: String): CompletedRide? {
    return supabase.from("nova_taxi_rides").select {
        filter {
            eq("passageiro_id", userId)
            eq("estado", "concluida")
        }
    }.decodeList<CompletedRide>()
        .filter { (it.finalAmount ?: 0.0) > 0.0 }
        .maxByOrNull { it.createdAt.orEmpty() }
}

private fun requestNovaPayment(rideId: String, method: String, mobile: String, accessToken: String): Pair<Int, JSONObject> {
    val connection = (URL("$SUPABASE_URL/functions/v1/nova-taxi-create-payment-intent").openConnection() as HttpURLConnection)
    connection.requestMethod = "POST"
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    connection.setRequestProperty("Authorization", "Bearer $accessToken")
    connection.setRequestProperty("apikey", SUPABASE_KEY)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true
    val body = JSONObject().put("ride_id", rideId).put("payment_method", method)
    if (method == "multicaixa_express") body.put("mobile", mobile)
    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val status = connection.responseCode
    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
    val responseText = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
    connection.disconnect()
    val json = try { JSONObject(responseText) } catch (_: Exception) { JSONObject().put("error", "invalid_server_response") }
    return status to json
}
