package org.objectiveliberty.askani

data class Project(
    val name: String,
    val sessions: MutableList<Session> = mutableListOf(),
    var isExpanded: Boolean = false
)

data class Session(
    val name: String,
    val project: String,
    var isSelected: Boolean = false
)

// Sealed class for RecyclerView items (can be either Project or Session)
sealed class VaultItem {
    data class ProjectItem(val project: Project) : VaultItem()
    data class SessionItem(val session: Session) : VaultItem()
}
