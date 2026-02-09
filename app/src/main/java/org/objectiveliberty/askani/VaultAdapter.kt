package org.objectiveliberty.askani

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VaultAdapter(
    private val items: MutableList<VaultItem>,
    private val onProjectClick: (Project) -> Unit,
    private val onSessionToggle: (Session) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_PROJECT = 0
        private const val VIEW_TYPE_SESSION = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is VaultItem.ProjectItem -> VIEW_TYPE_PROJECT
            is VaultItem.SessionItem -> VIEW_TYPE_SESSION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PROJECT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_project, parent, false)
                ProjectViewHolder(view)
            }
            VIEW_TYPE_SESSION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_session, parent, false)
                SessionViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is VaultItem.ProjectItem -> (holder as ProjectViewHolder).bind(item.project)
            is VaultItem.SessionItem -> (holder as SessionViewHolder).bind(item.session)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ProjectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.txt_project_name)

        fun bind(project: Project) {
            val icon = if (project.isExpanded) "📂" else "📁"
            textView.text = "$icon ${project.name}"
            itemView.setOnClickListener { onProjectClick(project) }
        }
    }

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkBox: CheckBox = itemView.findViewById(R.id.checkbox_session)
        private val textView: TextView = itemView.findViewById(R.id.txt_session_name)

        fun bind(session: Session) {
            textView.text = session.name
            checkBox.isChecked = session.isSelected
            
            // Handle checkbox clicks
            checkBox.setOnClickListener {
                session.isSelected = checkBox.isChecked
                onSessionToggle(session)
            }
            
            // Handle row clicks (toggle checkbox)
            itemView.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
                session.isSelected = checkBox.isChecked
                onSessionToggle(session)
            }
        }
    }

    fun updateItems(newItems: List<VaultItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
