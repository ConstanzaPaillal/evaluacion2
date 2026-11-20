package com.example.evaluacion2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    // Para pedir el permiso de notificaciones
    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MonitorPuerta.crearCanal(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var logueado by remember {
                    mutableStateOf(Firebase.auth.currentUser?.isEmailVerified == true)
                }

                // Al entrar empieza a escuchar; al cerrar sesión se detiene
                LaunchedEffect(logueado) {
                    if (logueado) MonitorPuerta.iniciar(applicationContext)
                    else MonitorPuerta.detener()
                }

                if (logueado) {
                    PantallaPrincipal(onCerrarSesion = {
                        Firebase.auth.signOut()
                        logueado = false
                    })
                } else {
                    PantallaLogin(onExito = { logueado = true })
                }
            }
        }
    }
}

// Devuelve un mensaje de error si la clave es débil, o null si es válida
fun validarClave(clave: String): String? {
    if (clave.length < 8) return "La contraseña debe tener al menos 8 caracteres"
    if (!clave.any { it.isUpperCase() }) return "Incluye al menos una mayúscula"
    if (!clave.any { it.isLowerCase() }) return "Incluye al menos una minúscula"
    if (!clave.any { it.isDigit() }) return "Incluye al menos un número"
    if (clave.all { it.isLetterOrDigit() }) return "Incluye al menos un símbolo (por ejemplo ! # $ %)"
    return null
}

@Composable
fun PantallaLogin(onExito: () -> Unit) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(false) }
    var verClave by remember { mutableStateOf(false) }
    var intentosFallidos by remember { mutableIntStateOf(0) }
    var bloqueadoHasta by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Alerta Puerta", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it },
            label = { Text("Correo") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = if (verClave) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { verClave = !verClave }) {
                    Text(if (verClave) "Ocultar" else "Mostrar")
                }
            },
            supportingText = { Text("Mínimo 8 caracteres, con mayúscula, minúscula, número y símbolo") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !cargando,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val ahora = System.currentTimeMillis()
                if (ahora < bloqueadoHasta) {
                    val seg = (bloqueadoHasta - ahora) / 1000 + 1
                    mensaje = "Demasiados intentos. Espera $seg segundos"
                } else if (correo.isBlank() || clave.isBlank()) {
                    mensaje = "Completa el correo y la contraseña"
                } else {
                    cargando = true
                    Firebase.auth.signInWithEmailAndPassword(correo.trim(), clave)
                        .addOnCompleteListener { tarea ->
                            cargando = false
                            val usuario = Firebase.auth.currentUser
                            if (tarea.isSuccessful && usuario?.isEmailVerified == true) {
                                intentosFallidos = 0
                                onExito()
                            } else if (tarea.isSuccessful) {
                                Firebase.auth.signOut()
                                mensaje = "Debes verificar tu correo antes de entrar. Revisa tu bandeja (y el spam)"
                            } else {
                                intentosFallidos++
                                if (intentosFallidos >= 5) {
                                    bloqueadoHasta = System.currentTimeMillis() + 30_000
                                    intentosFallidos = 0
                                    mensaje = "Demasiados intentos. Espera 30 segundos"
                                } else {
                                    mensaje = "Correo o contraseña incorrectos"
                                }
                            }
                        }
                }
            }
        ) { Text("Iniciar sesión") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            enabled = !cargando,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val error = validarClave(clave)
                if (correo.isBlank()) {
                    mensaje = "Escribe tu correo"
                } else if (error != null) {
                    mensaje = error
                } else {
                    cargando = true
                    Firebase.auth.createUserWithEmailAndPassword(correo.trim(), clave)
                        .addOnCompleteListener { tarea ->
                            cargando = false
                            if (tarea.isSuccessful) {
                                Firebase.auth.currentUser?.sendEmailVerification()
                                Firebase.auth.signOut()
                                clave = ""
                                mensaje = "Cuenta creada. Te enviamos un correo de verificación: confírmalo y luego inicia sesión"
                            } else {
                                mensaje = "No se pudo crear la cuenta. Revisa los datos"
                            }
                        }
                }
            }
        ) { Text("Crear cuenta") }

        TextButton(onClick = {
            if (correo.isBlank()) {
                mensaje = "Escribe tu correo para recuperar la contraseña"
            } else {
                Firebase.auth.sendPasswordResetEmail(correo.trim())
                mensaje = "Si el correo está registrado, recibirás un enlace para restablecer la contraseña"
            }
        }) { Text("¿Olvidaste tu contraseña?") }

        if (mensaje.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(mensaje, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun PantallaPrincipal(onCerrarSesion: () -> Unit) {
    val estado = MonitorPuerta.estado
    val sinConexion = !MonitorPuerta.conectado
    val pendientes = MonitorPuerta.pendientes

    val color = when (estado) {
        "abierta" -> Color(0xFFC62828)
        "cerrada" -> Color(0xFF2E7D32)
        else -> Color.Gray
    }
    val texto = when (estado) {
        "abierta" -> "¡PUERTA ABIERTA!"
        "cerrada" -> "Puerta cerrada"
        "cargando" -> "Cargando..."
        "error" -> "Sin acceso a los datos"
        else -> "Estado desconocido"
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Aviso de fail-safe: solo aparece si no hay conexión o hay eventos por enviar
        if (sinConexion || pendientes > 0) {
            val formatoHora = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            val colorTexto = if (sinConexion) Color.Black else Color.White
            Surface(
                color = if (sinConexion) Color(0xFFF9A825) else Color(0xFF1565C0),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (sinConexion) "Sin conexión" else "Sincronizando...",
                        color = colorTexto,
                        fontWeight = FontWeight.Bold
                    )
                    if (sinConexion) {
                        Text(
                            "Mostrando los últimos datos guardados. Última conexión: " +
                                    formatoHora.format(Date(MonitorPuerta.ultimaConexion)),
                            color = colorTexto,
                            fontSize = 13.sp
                        )
                    }
                    if (pendientes > 0) {
                        Text(
                            "$pendientes evento(s) pendiente(s) de envío",
                            color = colorTexto,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text("Alerta Puerta", style = MaterialTheme.typography.headlineMedium)
        Text(Firebase.auth.currentUser?.email ?: "")
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(texto, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = { MonitorPuerta.registrarEvento("abierta") }) { Text("Simular apertura") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { MonitorPuerta.registrarEvento("cerrada") }) { Text("Simular cierre") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Historial de eventos",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(MonitorPuerta.eventos, key = { it.id }) { evento ->
                FilaEvento(evento)
            }
        }

        OutlinedButton(onClick = onCerrarSesion) { Text("Cerrar sesión") }
    }
}

@Composable
fun FilaEvento(evento: Evento) {
    val formato = remember { SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()) }
    val abierta = evento.estado == "abierta"

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (abierta) Color(0xFFC62828) else Color(0xFF2E7D32))
        )
        Spacer(Modifier.width(12.dp))
        Text(if (abierta) "Puerta abierta" else "Puerta cerrada", modifier = Modifier.weight(1f))
        Text(formato.format(Date(evento.hora)), style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}