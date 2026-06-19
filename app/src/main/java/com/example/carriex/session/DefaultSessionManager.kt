package com.example.carriex.session

/**
 * Simple in-memory implementation of SessionManager.
 */
class DefaultSessionManager : SessionManager {
    private val sessions = mutableMapOf<String, Session>()

    override fun getSession(userId: String): Session {
        return sessions[userId] ?: Session(SessionState.NONE)
    }

    override fun updateSession(userId: String, session: Session) {
        sessions[userId] = session
    }
}
