package com.example.evaluacion2

import android.app.Application
import com.google.firebase.Firebase
import com.google.firebase.database.database

class AlertaPuertaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Guarda los datos en el celular para que la app funcione sin internet
        Firebase.database.setPersistenceEnabled(true)
    }
}