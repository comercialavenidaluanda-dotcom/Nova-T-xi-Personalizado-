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
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
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

private const val SUPABASE_URL = "https://earucsaqqtbllnqsxvlb.supabase.co"
private const val SUPABASE_KEY = "%%SUPABASE_PUBLISHABLE_KEY%%"

private val supabase = createSupabaseClient(supabaseUrl = SUPABASE_URL, supabaseKey = SUPABASE_KEY) { install(Auth) }

@Serializable
data class TaxiProfile(
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("full_name") val fullName: String,
    val phone: String
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
private fun NovaTaxiApp(activity: MainActivity) {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("PASSENGER") }
    var sent by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }
    var loggedRole by remember { mutableStateOf("PASSENGER") }
    var loggedUid by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted && loggedRole == "DRIVER") {
            activity.startDriverGps(loggedUid)
            message = "GPS ativado. O servidor só aceitará posições após a aprovação do motorista."
        } else if (!granted) message = "Permita a localização para enviar o GPS do motorista."
    }

    if (logged) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NOVA Táxi", style = MaterialTheme.typography.headlineMedium)
            Text(if (loggedRole == "DRIVER") "Conta de motorista" else "Conta de passageiro")
            if (loggedRole == "DRIVER") {
                Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth()) { Text("Ativar GPS em tempo real") }
                Text("O GPS é enviado enquanto a aplicação está aberta. O servidor bloqueia posições operacionais até a aprovação do motorista.", style = MaterialTheme.typography.bodySmall)
            } else Text("Sessão autenticada. Registo ligado ao Supabase NOVA Táxi.")
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
        if (!sent) {
            OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Telefone (+244...)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            Text("Registar como")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "PASSENGER", onClick = { role = "PASSENGER" }, label = { Text("Passageiro") })
                FilterChip(selected = role == "DRIVER", onClick = { role = "DRIVER" }, label = { Text("Motorista") })
            }
            Button(enabled = !busy && phone.isNotBlank(), onClick = {
                busy = true; message = "A enviar código SMS…"
                scope.launch {
                    try {
                        supabase.auth.signInWith(OTP) { this.phone = phone.trim() }
                        sent = true; message = "Código enviado. Verifique o SMS."
                    } catch (e: Exception) { message = e.message ?: "Não foi possível enviar o código." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "A aguardar…" else "Continuar por telefone") }
        } else {
            OutlinedTextField(value = otp, onValueChange = { otp = it }, label = { Text("Código recebido por SMS") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && otp.isNotBlank() && name.isNotBlank(), onClick = {
                busy = true; message = "A validar e a guardar o perfil…"
                scope.launch {
                    try {
                        supabase.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = phone.trim(), token = otp.trim())
                        val uid = supabase.auth.currentUserOrNull()?.id ?: error("O Supabase não criou uma sessão válida.")
                        supabase.from("nova_taxi_profiles").upsert(TaxiProfile(userId = uid, role = role, fullName = name.trim(), phone = phone.trim())) { onConflict = "user_id" }
                        if (role == "DRIVER") supabase.from("nova_taxi_driver_profiles").upsert(TaxiDriverProfile(userId = uid)) { onConflict = "user_id" }
                        loggedUid = uid; loggedRole = role; logged = true
                        message = if (role == "DRIVER") "Perfil criado. É necessária aprovação antes do envio de GPS operacional." else "Registo concluído."
                    } catch (e: Exception) { message = e.message ?: "Não foi possível concluir o registo. Verifique Auth e políticas Supabase." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "A validar…" else "Verificar código e registar") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
