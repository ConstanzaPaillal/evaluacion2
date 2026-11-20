package com.example.evaluacion2

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.database
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Un evento del historial: abierta o cerrada, y a qué hora ocurrió
data class Evento(val id: String, val estado: String, val hora: Long)

object MonitorPuerta {
    private const val CANAL = "alertas_puerta"

    val eventos = mutableStateListOf<Evento>()

    var estado by mutableStateOf("cargando")
        private set

    // Estado de la conexión (fail-safe)
    var conectado by mutableStateOf(true)
        private set
    var ultimaConexion by mutableLongStateOf(System.currentTimeMillis())
        private set
    var pendientes by mutableIntStateOf(0)
        private set

    private var consultaConexion: Query? = null
    private var listenerConexion: ValueEventListener? = null
    private var consultaEstado: Query? = null
    private var listenerEstado: ValueEventListener? = null
    private var consultaHistorial: Query? = null
    private var listenerHistorial: ChildEventListener? = null
    private var activo = false

    fun crearCanal(contexto: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL, "Alertas de la puerta", NotificationManager.IMPORTANCE_HIGH
            )
            contexto.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(canal)
        }
    }

    // Guarda el nuevo estado y agrega un evento al historial.
    // Sin internet, Firebase lo deja en cola y lo envía al reconectar.
    // (Más adelante, esto mismo lo hará el ESP32)
    fun registrarEvento(nuevoEstado: String) {
        val raiz = Firebase.database.reference.child("puerta")
        pendientes++
        raiz.child("estado").setValue(nuevoEstado)
        raiz.child("historial").push()
            .setValue(mapOf("estado" to nuevoEstado, "hora" to System.currentTimeMillis()))
            .addOnCompleteListener { pendientes-- } // se ejecuta cuando el servidor lo confirma
    }

    fun iniciar(contexto: Context) {
        if (activo) return
        activo = true
        val app = contexto.applicationContext
        val raiz = Firebase.database.reference.child("puerta")

        // 0) Conexión con Firebase
        val qConexion: Query = Firebase.database.getReference(".info/connected")
        val lConexion = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enLinea = snapshot.getValue(Boolean::class.java) ?: false
                if (!enLinea && conectado) ultimaConexion = System.currentTimeMillis()
                conectado = enLinea
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        qConexion.addValueEventListener(lConexion)
        consultaConexion = qConexion
        listenerConexion = lConexion

        // 1) Estado actual de la puerta
        val qEstado: Query = raiz.child("estado")
        qEstado.keepSynced(true) // mantiene una copia en el celular
        val lEstado = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                estado = snapshot.getValue(String::class.java) ?: "desconocido"
            }
            override fun onCancelled(error: DatabaseError) {
                estado = "error"
            }
        }
        qEstado.addValueEventListener(lEstado)
        consultaEstado = qEstado
        listenerEstado = lEstado

        // 2) Historial: los últimos 50 eventos
        val qHistorial: Query = raiz.child("historial").limitToLast(50)
        qHistorial.keepSynced(true)
        var cargaInicial = true
        val lHistorial = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.key ?: return
                val est = snapshot.child("estado").getValue(String::class.java) ?: return
                val hora = snapshot.child("hora").getValue(Long::class.java) ?: 0L
                val evento = Evento(id, est, hora)
                eventos.add(0, evento) // el más reciente arriba
                // Solo notifica los eventos nuevos, no los que ya existían al abrir
                if (!cargaInicial) notificar(app, evento)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.key ?: return
                val hora = snapshot.child("hora").getValue(Long::class.java) ?: return
                val i = eventos.indexOfFirst { it.id == id }
                if (i >= 0) eventos[i] = eventos[i].copy(hora = hora)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                eventos.removeAll { it.id == snapshot.key }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        qHistorial.addChildEventListener(lHistorial)
        // Este aviso llega cuando terminó de cargar el historial existente
        qHistorial.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { cargaInicial = false }
            override fun onCancelled(error: DatabaseError) {}
        })
        consultaHistorial = qHistorial
        listenerHistorial = lHistorial
    }

    // Se llama al cerrar sesión: deja de escuchar y limpia los datos
    fun detener() {
        listenerConexion?.let { consultaConexion?.removeEventListener(it) }
        listenerEstado?.let { consultaEstado?.removeEventListener(it) }
        listenerHistorial?.let { consultaHistorial?.removeEventListener(it) }
        consultaEstado?.keepSynced(false)
        consultaHistorial?.keepSynced(false)
        consultaConexion = null
        listenerConexion = null
        consultaEstado = null
        listenerEstado = null
        consultaHistorial = null
        listenerHistorial = null
        eventos.clear()
        estado = "cargando"
        conectado = true
        activo = false
    }

    @SuppressLint("MissingPermission")
    private fun notificar(contexto: Context, evento: Evento) {
        // Desde Android 13 hay que tener el permiso concedido
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val titulo = if (evento.estado == "abierta") "¡Puerta abierta!" else "Puerta cerrada"
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(evento.hora))

        // Al tocar la notificación se abre la app
        val intent = Intent(contexto, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendiente = PendingIntent.getActivity(
            contexto, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificacion = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titulo)
            .setContentText("Evento registrado a las $hora")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendiente)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(contexto).notify(evento.id.hashCode(), notificacion)
    }
}