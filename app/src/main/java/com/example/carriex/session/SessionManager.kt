package com.example.carriex.session

/**
 * Interface for managing session states between users.
 */
interface SessionManager {
    fun getSession(userId: String): Session
    fun updateSession(userId: String, session: Session)
}
