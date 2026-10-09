package ao.novataxi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
private const val SUPABASE_KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY

private val NovaOrange = Color(0xFFFF6A00)
private val NovaInk = Color(0xFF242424)

private val supabase = createSupabaseClient(
    supabaseUrl = SUPABASE_URL,
    supabaseKey = SUPABASE_KEY
) { install(Auth) }

@Serializable
data class TaxiProfile(
    val id: String,
    @SerialName("tipo_utilizador") val tipoUtilizador: String,
    @SerialName("nome") val nome: String,
    @SerialName("telefone") val telefone: String? = null,
    @SerialName("ativo") val ativo: Boolean = true
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = NovaOrange,
                    onPrimary = Color.White,
                    secondary = Color(0xFFFF9B45),
                    background = Color(0xFFFFFBF7),
                    surface = Color.White,
                    onSurface = NovaInk
                )
            ) { NovaTaxiApp() }
        }
    }
}

@Composable
private fun NovaTaxiApp() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("passageiro") }
    var isLogin by remember { mutableStateOf(false) }
    var loggedProfile by remember { mutableStateOf<TaxiProfile?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("NOVA Táxi", style = MaterialTheme.typography.headlineLarge, color = NovaOrange)
        Text("Pedimos. Chegamos.", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isLogin) "Entrar na sua conta" else "Criar conta de passageiro ou motorista",
            style = MaterialTheme.typography.titleLarge
        )

        if (!isLogin) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Tipo de conta", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }

        OutlinedTextField(
            value = email, onValueChange = { email = it.trim() },
            label = { Text("E-mail") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Palavra-passe (mínimo 8 caracteres)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Button(
            enabled = !busy && email.contains("@") && password.length >= 8 &&
                (isLogin || name.isNotBlank()),
            onClick = {
                busy = true
                message = if (isLogin) "A autenticar…" else "A criar conta no Supabase…"
                scope.launch {
                    try {
                        if (isLogin) {
                            supabase.auth.signInWith(Email) {
                                this.email = email.trim()
                                this.password = password
                            }
                        } else {
                            supabase.auth.signUpWith(Email) {
                                this.email = email.trim()
                                this.password = password
                                data = buildJsonObject {
                                    put("tipo_utilizador", role)
                                    put("nome", name.trim())
                                }
                            }
                        }

                        val uid = supabase.auth.currentUserOrNull()?.id
                        if (uid == null) {
                            if (isLogin) {
                                error("O Supabase não devolveu uma sessão. Confirme o e-mail e as definições de Auth.")
                            } else {
                                isLogin = true
                                message = "Registo enviado. Se a confirmação por e-mail estiver ativa, confirme o e-mail e depois entre."
                            }
                        } else {
                            // O trigger do banco cria o perfil. O APK apenas lê; não faz upsert direto.
                            val profile = supabase.from("nova_taxi_profiles")
                                .select {
                                    filter { eq("id", uid) }
                                }
                                .decodeList<TaxiProfile>()
                                .firstOrNull()
                                ?: error("A conta foi autenticada, mas o trigger ainda não disponibilizou o perfil. Contacte o suporte.")

                            loggedProfile = profile
                            message = if (profile.tipoUtilizador.equals("motorista", true)) {
                                "Sessão iniciada. O motorista permanece pendente até aprovação administrativa."
                            } else {
                                "Sessão iniciada com o perfil real do Supabase."
                            }
                        }
                    } catch (e: Exception) {
                        message = e.message?.take(350)
                            ?: "Falha na ligação ao Supabase. Verifique a URL, a chave pública e a resposta REST."
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "A processar…" else if (isLogin) "Entrar" else "Criar conta")
        }

        TextButton(
            onClick = { isLogin = !isLogin; message = ""; loggedProfile = null },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLogin) "Ainda não tenho conta — registar" else "Já tenho conta — entrar")
        }

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }

        loggedProfile?.let { profile ->
            HorizontalDivider()
            Text("Conta ligada ao Supabase", style = MaterialTheme.typography.titleMedium)
            Text("Nome: ${profile.nome}")
            Text("Perfil: ${profile.tipoUtilizador}")
            Text("Estado: ${if (profile.ativo) "ativo" else "inativo"}")
            if (profile.tipoUtilizador.equals("motorista", true)) {
                Text("A aprovação administrativa é obrigatória antes de iniciar operações.")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        supabase.auth.signOut()
                        loggedProfile = null
                        message = "Sessão terminada."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Terminar sessão") }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "O cadastro não solicita método de pagamento. Nunca partilhe códigos de autenticação.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
