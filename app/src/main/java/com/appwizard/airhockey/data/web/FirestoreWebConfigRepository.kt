package com.appwizard.airhockey.data.web

import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class FirestoreWebConfigRepository(
    private val firebaseConfigInitializer: FirebaseConfigInitializer
) : WebConfigRepository {

    override suspend fun getWebViewUrl(): String? {
        if (!firebaseConfigInitializer.ensureInitialized()) return null

        return suspendCancellableCoroutine { continuation ->
            FirebaseFirestore.getInstance()
                .collection("config")
                .document("app")
                .get()
                .addOnSuccessListener { document ->
                    if (continuation.isActive) {
                        continuation.resume(document?.getString("url"))
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }
}
